# CriticalInsights Gateway -- Architecture

## System diagram

```
                                    ┌──────────────────────────────────────────────────────┐
┌─────────────────┐                 │              Gateway (Java 17)                       │
│ Philips MX800   │                 │                                                      │
│ Patient Monitor │──RS-232/UDP──→  │  ┌──────────────┐    ┌─────────────────────┐         │
└─────────────────┘  115200 8N1     │  │ Philips       │    │                     │         │   ┌───────────┐
                                    │  │ IntelliVue    │──→ │                     │─────────────→│ stdout    │
                                    │  │ Parser        │    │  QueuedJson         │         │   └───────────┘
                                    │  └──────────────┘    │  Publisher           │         │   ┌───────────┐
┌─────────────────┐                 │                       │                     │─────────────→│ .jsonl    │
│ Draeger         │                 │  ┌──────────────┐    │  - retry queue      │         │   └───────────┘
│ Ventilator      │──RS-232/TCP──→  │  │ Draeger       │    │  - backoff          │         │   ┌───────────┐
└─────────────────┘  19200 8E1      │  │ MEDIBUS       │──→ │  - dead-letter      │─────────────→│ HTTP POST │
                                    │  │ Parser        │    │                     │         │   └───────────┘
                                    │  └──────────────┘    │                     │         │   ┌───────────┐
                                    │                       │                     │─────────────→│ Web       │
                                    │                       └─────────────────────┘         │   │ Dashboard │
                                    │                                                      │   │ (SSE)     │
                                    └──────────────────────────────────────────────────────┘   └───────────┘
```

## Data flow

### 1. Auto-detect COM ports

When started with `--auto`, `PortDetector` probes each serial port:

1. Configure port at 19200 baud, send MEDIBUS DeviceId request, wait 3 seconds for SOH response
2. Port that responds is Draeger
3. Configure remaining ports at 115200 baud, listen for data
4. Port with data is Philips

Result: `DRAEGER=/dev/ttyS1  PHILIPS=/dev/ttyS0`

### 2. Connect and decode

Each device adapter runs in a supervisor loop that connects, reads data, and reconnects on failure:

```
while (running) {
    connect to device
    set state -> "connected"
    read data loop (blocks until disconnect or error)
    set state -> "disconnected"
    sleep(reconnectMs)
}
```

### 3. Publish events

Decoded vitals flow through the shared publishing pipeline to all configured sinks.

---

## Draeger MEDIBUS protocol

The MEDIBUS protocol is command-response over a byte-framed serial link.

**Communication sequence:**

```
Gateway                             Draeger Ventilator
  │                                        │
  │── ESC 51 checksum CR ───────────────→  │  ICC (Initialize Communication)
  │←── SOH 51 ... checksum CR ──────────── │
  │                                        │
  │── ESC 52 checksum CR ───────────────→  │  Request Device ID
  │←── SOH 52 "Evita V500..." chk CR ──── │
  │                                        │
  │── ESC 24 checksum CR ───────────────→  │  Request Measured Data CP1
  │←── SOH 24 "01 450 02 016..." chk CR ── │  VT=450, RR=16, ...
  │                                        │
  (repeats every --poll-ms, default 1000ms)
```

**Frame format:**

```
Command:  ESC <cmd-byte> <checksum-hi> <checksum-lo> CR
Response: SOH <cmd-byte> <data...> <checksum-hi> <checksum-lo> CR
```

**Key protocol requirements (Rev 6.00):**

- **ICC handshake** (0x51): must be sent before polling
- **Even parity** (8E1): default for Evita Channel A; configurable via `--draeger-parity`
- **3-second timeout**: any pause >3 seconds terminates the link
- **DC1/DC3 flow control**: XON/XOFF handled at protocol level
- Measured data: 6-byte records (2-byte metric code + 4-byte ASCII value)

---

## Philips IntelliVue protocol

### LAN/UDP mode

The gateway communicates directly via UDP datagrams.

### MIB/RS232 mode

The IntelliVue protocol is UDP-based even over serial. The `RS232Adapter` bridges the gap:

```
Serial Port              RS232Adapter              IntelliVue Parser
(/dev/ttyS0)            (serial-to-UDP bridge)     (UDP protocol)
     │                        │                          │
     │── raw bytes ────────→  │── UDP packets ─────────→ │
     │                        │   (loopback 127.0.0.1)   │
     │←── raw bytes ────────  │←── UDP responses ──────  │── parsed data → JSON
```

**Protocol sequence:**

1. Association Request/Response handshake
2. MDS Create Event (system model info)
3. Extended Poll Data Result with numeric observed values
4. Keep-alive polls sent automatically if no data for 10 seconds

**Key details:**

- FLOAT-Type encoding: 8-bit exponent + 24-bit mantissa
- Big-endian byte order
- Max 1 message per second per poll type (rate limiting enforced by the driver)
- Serial framing: `BOF(0xC0) | Header | User Data | FCS(CRC-CCITT) | EOF(0xC1)`

---

## Publishing pipeline

```
Device Adapter
     │
     ▼
QueuedJsonPublisher
     │  LinkedBlockingQueue (capacity: 10,000)
     │  Worker thread polls every 1 second
     ▼
publishWithRetry()
     │
     ├── attempt 1 → success → done
     ├── attempt 2 → fail → backoff 1000ms
     ├── attempt 3 → fail → backoff 1500ms
     ├── ...up to maxPublishAttempts (default 5)
     └── all failed → writeDeadLetter()
```

The downstream `MultiJsonPublisher` fans out to all configured sinks independently:

| Sink | Class | Behavior |
|------|-------|----------|
| stdout (JSON) | `StdoutJsonPublisher` | Print one JSON object per line |
| stdout (compact) | `CompactStdoutPublisher` | One-line summary per second: `00:20:06 philips_mx800_01 | HR=72 bpm | SpO2=97%` |
| File | `FileJsonPublisher` | Append to `.jsonl` file, flush after each event, `0640` permissions |
| HTTP | `HttpJsonPublisher` | POST to endpoint with auth headers, HTTPS enforced by default |
| Web dashboard | `WebDashboardPublisher` | SSE push to browser clients on same HTTP port |

Each sink is independent. If HTTP fails but file succeeds, the event is not replayed into the file sink.

---

## Web dashboard architecture

`WebDashboardPublisher` runs a `com.sun.net.httpserver.HttpServer` on a single port:

| Endpoint | Description |
|----------|-------------|
| `GET /` | Embedded HTML dashboard (dark patient monitor UI) |
| `GET /events` | SSE stream -- each event sent as `data: {json}\n\n` |
| `GET /api/latest` | Most recent event as JSON |

SSE clients are tracked in a `ConcurrentHashMap`. Each published event is written to all connected clients. Up to 100 recent events are buffered for late-joining clients.

No WebSocket, no separate port, no external dependencies.

---

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
  ├── json-publisher (worker thread for queued publishing)
  │
  ├── heartbeat-timer (logs HEARTBEAT every 60 seconds)
  │
  └── web-dashboard-pool (if --web-port; cached thread pool for HTTP/SSE)
```

---

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

---

## File layout

```
openice-headless-json-gateway-patched/
├── setup.sh                    # One-time build script
├── test.sh                     # Test with Draeger simulator (TCP)
├── hil-test.sh                 # HIL test (virtual serial ports via socat)
├── run.sh                      # Run with real devices or simulators
├── package.sh                  # Build distributable runtime package
│
├── deploy/
│   ├── deploy.sh               # Production deployment script
│   └── logrotate-openice       # Log rotation config
│
├── openice-multidevice.service # Hardened systemd service file
│
├── test-tools/
│   ├── CriticalInsightsMonitor.java   # GUI: dark patient monitor with waveforms
│   ├── MedibusSimulatorV2.java        # CLI: Draeger MEDIBUS simulator
│   ├── IntellivueSimulatorV2.java     # CLI: Philips IntelliVue simulator
│   ├── IntellivueSerialSimulator.java # CLI: Philips MIB/RS232 serial simulator
│   ├── PortDetector.java              # Auto-detect devices on COM ports
│   ├── MedibusSimulator.java          # V1 Draeger simulator
│   ├── IntellivueSimulator.java       # V1 Philips simulator
│   ├── FakeDraegerDevice.java         # Basic TCP Draeger simulator
│   └── FakeDraegerSerial.java         # Serial Draeger simulator (for HIL)
│
├── devices/
│   ├── common/                 # Shared code
│   │   └── src/main/java/org/mdpnp/devices/
│   │       ├── headless/
│   │       │   ├── JsonPublisher.java          # Publisher interface
│   │       │   ├── StdoutJsonPublisher.java    # Print JSON to stdout
│   │       │   ├── CompactStdoutPublisher.java # One-line compact output
│   │       │   ├── FileJsonPublisher.java      # Append to .jsonl (0640)
│   │       │   ├── HttpJsonPublisher.java      # HTTP POST with retry
│   │       │   ├── WebDashboardPublisher.java  # SSE web dashboard
│   │       │   ├── MultiJsonPublisher.java     # Fan-out to multiple sinks
│   │       │   ├── QueuedJsonPublisher.java    # Async queue with retry
│   │       │   └── JsonUtil.java               # JSON serialization
│   │       └── serial/
│   │           ├── SerialProviderFactory.java  # Serial port provider
│   │           └── TCPSerialProvider.java      # TCP-to-serial adapter
│   │
│   ├── draeger/                # Draeger MEDIBUS driver
│   │   └── src/main/java/.../draeger/medibus/
│   │       ├── Medibus.java                       # Protocol parser
│   │       ├── HeadlessDraegerMedibus.java        # Draeger -> JSON adapter
│   │       ├── ChecksumOutputStream.java          # MEDIBUS checksum
│   │       └── headless/
│   │           └── HeadlessDraegerGatewayApp.java # Standalone launcher
│   │
│   ├── philips/                # Philips IntelliVue driver
│   │   └── src/main/java/.../philips/intellivue/
│   │       ├── Intellivue.java                            # Protocol core
│   │       ├── CompoundProtocol.java                      # Frame parser
│   │       ├── RS232Adapter.java                          # Serial-to-UDP bridge
│   │       └── headless/
│   │           ├── HeadlessPhilipsGatewayApp.java         # LAN/UDP launcher
│   │           └── HeadlessPhilipsSerialGatewayApp.java   # Serial launcher
│   │
│   └── multidevice/            # Combined launcher
│       └── src/main/java/.../headless/multidevice/
│           └── HeadlessMultiDeviceGatewayApp.java # Both devices in one process
│
└── interop-lab/
    └── purejavacomm/           # Serial port library (JNA-based)
```

---

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

```json
{
  "gatewayId": "air021_01",
  "bedId": "bed_01",
  "deviceId": "draeger_vent_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "eventType": "vital",
  "source": "measured_data_cp1",
  "metric": "Paw",
  "metricCode": "Paw",
  "unit": "cmH2O",
  "unitCode": "cmH2O",
  "rawValue": "12",
  "value": 12,
  "timestamp": "2026-05-21T12:00:00Z"
}
```

If a unit cannot be inferred safely, `unit` and `unitCode` are `null`.

### Philips numeric event

```json
{
  "gatewayId": "air021_01",
  "bedId": "bed_01",
  "deviceId": "philips_mx800_01",
  "vendor": "philips",
  "protocol": "intellivue",
  "eventType": "numeric",
  "metric": "MDC_PULS_OXIM_SAT_O2",
  "metricCode": "...",
  "unit": "%",
  "unitCode": "%",
  "value": 98,
  "timestamp": "2026-05-21T12:00:00Z"
}
```

---

## Security model

| Layer | Protection |
|-------|-----------|
| Serial ports | `DeviceAllow` restricts systemd to specific ports only |
| Filesystem | `ProtectSystem=strict` -- read-only except `/var/log/openice` |
| Process | `NoNewPrivileges`, `PrivateTmp`, `ProtectHome` |
| Network | HTTPS enforced by default (`--allow-insecure-http` required for HTTP) |
| Files | JSONL files created with `0640` permissions |
| Resources | `MemoryMax=512M`, `TasksMax=64` prevents runaway |
| Recovery | `WatchdogSec=120`, `Restart=always` |

---

## Testing layers

| Layer | Command | What it tests |
|-------|---------|---------------|
| Unit tests | `./gradlew test` | Protocol parsing, checksum, data types |
| Integration (TCP) | `./test.sh` | End-to-end Draeger path over TCP loopback |
| HIL (serial) | `./hil-test.sh` | Serial port lifecycle via socat virtual PTYs |
| Port detection | `java -cp test-tools PortDetector` | COM port scanning and device identification |
| Simulator (GUI) | `java -cp test-tools CriticalInsightsMonitor` | Interactive testing with waveforms and alarms |
| Production | `./run.sh --auto` | Real devices on real serial ports |
