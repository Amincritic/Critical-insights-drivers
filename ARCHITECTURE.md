# OpenICE Headless JSON Gateway — Architecture

## Overview

This gateway reads vital signs from bedside medical devices (Philips MX800
patient monitors and Draeger ventilators) over RS-232 serial ports and
outputs structured JSON events. It is designed to run headless on edge
gateways like the Advantech AIR-021.

## System diagram

```
┌─────────────────┐                    ┌──────────────────────────────────────────────┐
│ Philips MX800   │                    │            Gateway (Java)                    │
│ Patient Monitor │──RS-232 COM1──→    │                                              │
│                 │  115200 8N1        │  ┌──────────────┐   ┌────────────────────┐   │
└─────────────────┘                    │  │ Philips      │   │                    │   │
                                       │  │ MIB/RS232    │──→│                    │   │     ┌──────────┐
                                       │  │ Adapter      │   │  QueuedJson        │──────→  │ stdout   │
                                       │  └──────────────┘   │  Publisher          │   │     └──────────┘
                                       │                      │                    │   │     ┌──────────┐
┌─────────────────┐                    │  ┌──────────────┐   │  - retry queue     │──────→  │ .jsonl   │
│ Draeger         │                    │  │ Draeger      │   │  - backoff         │   │     │ file     │
│ Ventilator      │──RS-232 COM2──→    │  │ MEDIBUS      │──→│  - dead-letter     │   │     └──────────┘
│                 │  19200 8N1         │  │ Parser       │   │                    │   │     ┌──────────┐
└─────────────────┘                    │  └──────────────┘   │                    │──────→  │ HTTP     │
                                       │                      │                    │   │     │ POST     │
                                       │                      └────────────────────┘   │     └──────────┘
                                       └──────────────────────────────────────────────┘
```

## Data flow step by step

### 1. Serial port detection

When started with `--auto`, the gateway probes each COM port:

```
PortDetector
  │
  ├── /dev/ttyS0: configure 19200 baud → send MEDIBUS DeviceId request
  │                wait 3s for SOH response → no response
  │
  ├── /dev/ttyS1: configure 19200 baud → send MEDIBUS DeviceId request
  │                wait 3s for SOH response → SOH received → DRAEGER
  │
  └── /dev/ttyS0: configure 115200 baud → listen for data
                   received 4+ bytes → PHILIPS
```

Result: `DRAEGER=/dev/ttyS1  PHILIPS=/dev/ttyS0`

### 2. Draeger MEDIBUS communication

The Draeger protocol is command-response over a byte-framed serial link:

```
Gateway (host)                      Draeger Ventilator
     │                                      │
     │── ESC 52 checksum CR ──────────────→ │  (Request Device ID)
     │                                      │
     │←── SOH 52 "Evita V500..." chk CR ── │  (Response: device name)
     │                                      │
     │── ESC 24 checksum CR ──────────────→ │  (Request Measured Data CP1)
     │                                      │
     │←── SOH 24 "01 450 02 016..." chk CR─ │  (Response: VT=450, RR=16, ...)
     │                                      │
     (repeats every --poll-ms, default 1000ms)
```

Frame format:
```
Command:  ESC <cmd-byte> <checksum-hi> <checksum-lo> CR
Response: SOH <cmd-byte> <data...>     <checksum-hi> <checksum-lo> CR
```

Measured data is 6-byte records: 2-byte metric code + 4-byte ASCII value.

The gateway maps known metric codes to human-readable names and infers
units where possible (e.g. code 05 = Airway Pressure in cmH2O).

### 3. Philips MIB/RS232 communication

The Philips path is more complex because the IntelliVue protocol is
UDP-based, even over serial:

```
Serial Port                RS232Adapter              IntelliVue Parser
(/dev/ttyS0)              (serial-to-UDP bridge)     (UDP protocol)
     │                          │                          │
     │── raw bytes ──────────→  │                          │
     │                          │── UDP packets ─────────→ │
     │                          │   (loopback 127.0.0.1)   │
     │                          │                          │── parsed data ──→ JSON
     │                          │←── UDP responses ────── │
     │←── raw bytes ─────────  │                          │
```

The RS232Adapter:
1. Opens the serial port at 115200 baud, 8N1
2. Creates two ephemeral UDP ports on localhost
3. Bridges serial bytes to/from UDP datagrams
4. The IntelliVue protocol parser works on the UDP side

The IntelliVue parser handles:
- Association requests/responses (connect/disconnect)
- Data export protocol (numerics, waveforms, alarms)
- Compound protocol framing and byte escaping

### 4. JSON event publishing

Both device adapters produce `Map<String, Object>` events that flow through
a shared publishing pipeline:

```
Device Adapter
     │
     ▼
QueuedJsonPublisher
     │
     │  LinkedBlockingQueue (capacity: 10,000)
     │  Worker thread polls every 1 second
     │
     ▼
publishWithRetry()
     │
     ├── attempt 1 → downstream.publish()  → success → done
     │
     ├── attempt 2 → downstream.publish()  → fail → sleep 1000ms
     │
     ├── attempt 3 → downstream.publish()  → fail → sleep 1500ms
     │
     ├── ...up to maxPublishAttempts (default 5)
     │
     └── all failed → writeDeadLetter()
                            │
                            └── append to dead-letter.jsonl
```

The downstream publisher is a `MultiJsonPublisher` that fans out to:

```
MultiJsonPublisher
     │
     ├── StdoutJsonPublisher  →  System.out.println(json)
     │
     ├── FileJsonPublisher    →  append to .jsonl file (flush after each event)
     │
     └── HttpJsonPublisher    →  HTTP POST to endpoint (with auth headers)
```

Each sink is independent. If HTTP fails but file succeeds, the event
is not replayed into the file sink.

### 5. Reconnection

Each device runs in a supervisor thread with a reconnection loop:

```
while (running) {
    try {
        connect to device
        set state → "connected"
        read data loop (blocks until disconnect or error)
    } catch (Exception) {
        set state → "disconnected"
        log error
    } finally {
        close resources
    }
    sleep(reconnectMs)  // default 5 seconds
}
```

The Draeger adapter also has a poller thread that sends commands
periodically. When the reader detects a disconnect, both threads
are stopped and the entire connection is rebuilt.

## Thread model

```
main thread
  │
  ├── shutdown hook (cleanup on SIGTERM/SIGINT)
  │
  ├── philips-intellivue-supervisor (or philips-mib-rs232-supervisor)
  │     └── NetworkLoop thread (reads/writes UDP)
  │           └── RS232Adapter threads (if serial mode)
  │
  ├── draeger-medibus-supervisor
  │     ├── Medibus I/O Multiplexor (reads serial, splits fast/slow bytes)
  │     └── draeger-poller (sends commands every pollMs)
  │
  └── json-publisher (worker thread for queued publishing)
```

## Heartbeat and monitoring

Every 60 seconds the main thread logs:

```
HEARTBEAT | threads: draeger-medibus-supervisor=alive philips-mib-rs232-supervisor=alive | devices: draeger_vent_01=connected(last_data=3s_ago) philips_mx800_01=connected(last_data=1s_ago)
```

Device state changes are logged immediately:

```
DEVICE_STATE draeger_vent_01: connected
DEVICE_STATE philips_mx800_01: disconnected
```

## File layout

```
openice-headless-json-gateway-patched/
│
├── setup.sh                    # One-time build script
├── test.sh                     # Test with fake Draeger (TCP, no hardware)
├── hil-test.sh                 # HIL test (virtual serial ports via socat)
├── run.sh                      # Run with real devices (--auto to detect ports)
│
├── deploy/
│   ├── deploy.sh               # Production deployment script
│   └── logrotate-openice       # Log rotation config
│
├── openice-multidevice.service # Hardened systemd service file
│
├── test-tools/
│   ├── PortDetector.java       # Auto-detect device on COM ports
│   ├── FakeDraegerDevice.java  # TCP Draeger simulator
│   └── FakeDraegerSerial.java  # Serial Draeger simulator (for HIL)
│
├── devices/
│   ├── common/                 # Shared code
│   │   └── src/main/java/org/mdpnp/devices/
│   │       ├── headless/
│   │       │   ├── JsonPublisher.java          # Interface
│   │       │   ├── StdoutJsonPublisher.java    # Print to stdout
│   │       │   ├── FileJsonPublisher.java      # Append to .jsonl
│   │       │   ├── HttpJsonPublisher.java      # HTTP POST
│   │       │   ├── MultiJsonPublisher.java     # Fan-out to multiple sinks
│   │       │   ├── QueuedJsonPublisher.java    # Async queue with retry
│   │       │   └── JsonUtil.java               # JSON serialization
│   │       └── serial/
│   │           ├── SerialProviderFactory.java  # Serial port provider
│   │           └── TCPSerialProvider.java      # TCP-to-serial adapter
│   │
│   ├── draeger/                # Draeger MEDIBUS driver
│   │   └── src/main/java/org/mdpnp/devices/draeger/medibus/
│   │       ├── Medibus.java                    # MEDIBUS protocol parser
│   │       ├── HeadlessDraegerMedibus.java     # Draeger → JSON adapter
│   │       ├── ChecksumOutputStream.java       # MEDIBUS checksum
│   │       └── headless/
│   │           └── HeadlessDraegerGatewayApp.java  # Standalone Draeger launcher
│   │
│   ├── philips/                # Philips IntelliVue driver
│   │   └── src/main/java/org/mdpnp/devices/philips/intellivue/
│   │       ├── Intellivue.java                 # IntelliVue protocol
│   │       ├── CompoundProtocol.java           # Frame parser
│   │       ├── RS232Adapter.java               # Serial-to-UDP bridge
│   │       └── headless/
│   │           ├── HeadlessPhilipsGatewayApp.java       # LAN/UDP launcher
│   │           └── HeadlessPhilipsSerialGatewayApp.java # Serial launcher
│   │
│   └── multidevice/            # Combined launcher
│       └── src/main/java/org/mdpnp/devices/headless/multidevice/
│           └── HeadlessMultiDeviceGatewayApp.java  # Both devices in one process
│
└── interop-lab/
    └── purejavacomm/           # Serial port library (JNA-based, no native code)
```

## JSON event schema

### Common fields (all events)

| Field | Type | Description |
|-------|------|-------------|
| `gatewayId` | string | Edge gateway identifier (e.g. `air021_01`) |
| `bedId` | string | Bed identifier (e.g. `bed_01`) |
| `deviceId` | string | Device identifier (e.g. `draeger_vent_01`) |
| `vendor` | string | `draeger` or `philips` |
| `protocol` | string | `medibus` or `intellivue` |
| `eventType` | string | `vital`, `numeric`, `device_identity`, `alarm`, `waveform` |
| `timestamp` | string | ISO-8601 UTC timestamp |

### Draeger vital event

| Field | Type | Description |
|-------|------|-------------|
| `source` | string | `measured_data_cp1` or `measured_data_cp2` |
| `metric` | string | Metric name (e.g. `BreathingPressure_(in_mbar)`) |
| `metricCode` | string | Raw metric code from device |
| `unit` | string/null | Inferred unit (e.g. `cmH2O`) or null if unknown |
| `unitCode` | string | Source metric code string |
| `rawValue` | string | Raw ASCII value from device |
| `value` | number | Parsed numeric value |

### Philips numeric event

| Field | Type | Description |
|-------|------|-------------|
| `metric` | string | MDC code (e.g. `MDC_PULS_OXIM_SAT_O2`) |
| `metricCode` | string | Numeric MDC code |
| `unit` | string/null | Unit string (e.g. `%`) |
| `unitCode` | string | Unit code |
| `value` | number | Numeric value |

## Security model

| Layer | Protection |
|-------|-----------|
| Serial ports | `DeviceAllow` restricts to specific ports only |
| Filesystem | `ProtectSystem=strict` — read-only except `/var/log/openice` |
| Process | `NoNewPrivileges`, `PrivateTmp`, `ProtectHome` |
| Network | HTTPS enforced by default (`--allow-insecure-http` required for HTTP) |
| Files | JSONL files created with `0640` permissions |
| Resources | `MemoryMax=512M`, `TasksMax=64` prevents runaway |
| Recovery | `WatchdogSec=120`, `Restart=always` |

## Testing layers

| Layer | Command | What it tests |
|-------|---------|---------------|
| Unit tests | `./gradlew test` | Protocol parsing, checksum, data types |
| Integration (TCP) | `./test.sh` | End-to-end Draeger path over TCP loopback |
| HIL (serial) | `./hil-test.sh` | Serial port lifecycle via socat virtual PTYs |
| Port detection | `java -cp test-tools PortDetector` | COM port scanning and device identification |
| Production | `./run.sh --auto` | Real devices on real serial ports |
