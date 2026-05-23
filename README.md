# CriticalInsights Medical Device Gateway

A headless gateway that reads vital signs from Draeger ventilators (MEDIBUS) and Philips IntelliVue patient monitors (LAN/UDP and MIB/RS232) over RS-232 serial ports and publishes normalized JSON to stdout, files, HTTP endpoints, and a built-in web dashboard.

## What's included

- **Draeger MEDIBUS driver** — serial and TCP, ICC handshake, even parity, realtime data
- **Philips IntelliVue driver** — LAN/UDP and MIB/RS232, association negotiation, poll rate limiting
- **Multi-device launcher** — both devices in one process on one edge gateway
- **Auto-detect** — probes COM ports to identify which device is on which port
- **Web dashboard** — built-in dark patient monitor UI served via SSE on a single HTTP port
- **Compact CLI output** — one-line vital sign summaries instead of raw JSON
- **Device simulators** — spec-compliant CLI and GUI simulators for testing without hardware
- **Production hardening** — heartbeat monitoring, log rotation, systemd service, file permissions
- **Runtime packaging** — 3.4MB deployable bundle (no Gradle/source needed on target)

## Documentation

| Document | What it covers |
|----------|---------------|
| **[README.md](README.md)** (this file) | Quick start, scripts, output options, deployment |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | System diagram, data flow, protocol details, thread model, JSON schema |
| **[HARDWARE.md](HARDWARE.md)** | Pin mappings, cable wiring (RJ-45 to DB-9), device configuration steps |
| **[SIMULATOR.md](SIMULATOR.md)** | CLI and GUI simulators, testing without hardware, HIL testing |
| **[FIXES_APPLIED.md](FIXES_APPLIED.md)** | All bug fixes, protocol compliance changes, and new features |

---

## Quick start

```bash
# 1. Install JDK 17
sudo apt update && sudo apt install -y openjdk-17-jdk

# 2. Build everything
./setup.sh

# 3. Test without real devices (runs a Draeger simulator for 30 seconds)
./test.sh

# 4. Simulate with the GUI monitor
java -cp test-tools CriticalInsightsMonitor

# 5. Run with real devices (auto-detect COM ports)
./run.sh --auto --stdout

# 6. Deploy as a systemd service
sudo bash deploy/deploy.sh
```

---

## Script reference

| Script | Purpose |
|--------|---------|
| `./setup.sh` | One-time setup: detects JDK, downloads Gradle, builds everything, compiles test tools |
| `./test.sh` | Automated test: runs Draeger simulator + gateway for 30 seconds (accepts `./test.sh 9100 60` for custom port/duration) |
| `./run.sh --help` | Shows all run options with examples |
| `./run.sh --auto --stdout` | Auto-detect devices on COM ports and run |
| `./hil-test.sh` | HIL test with virtual serial ports via `socat` (accepts `--duration 120`, `--multi`, `--loopback`) |
| `./package.sh` | Build a distributable runtime package (~3.4 MB) |
| `sudo bash deploy/deploy.sh` | Production deployment: creates service user, installs systemd service and logrotate |

---

## Output options

All launchers share these output flags:

| Flag | Description |
|------|-------------|
| `--stdout true` | Print JSON events to stdout (enabled by default if no file/HTTP sink is set) |
| `--stdout compact` | Print a compact one-line summary per second instead of raw JSON |
| `--jsonl <path>` | Append JSON events to a JSONL file |
| `--dead-letter-jsonl <path>` | Write events that fail all publish attempts to a dead-letter file |
| `--http-url <url>` | POST JSON events to an HTTP endpoint (HTTPS required unless `--allow-insecure-http true`) |
| `--http-header 'Name: value'` | Add HTTP headers (repeatable, e.g. `--http-header 'Authorization: Bearer TOKEN'`) |
| `--web-port <port>` | Start the built-in web dashboard on this port |
| `--queue-capacity <n>` | Internal publish queue size (default 10,000) |
| `--publish-attempts <n>` | Max retry attempts per event (default 5) |
| `--reconnect-ms <ms>` | Reconnection delay after device disconnect (default 5000) |

When `--jsonl` or `--http-url` is set, stdout is disabled unless you explicitly pass `--stdout true` or `--stdout compact`.

---

## Running with simulators

### CLI simulators

```bash
# Terminal 1 -- Draeger simulator
java -cp test-tools MedibusSimulatorV2 --tcp 9100

# Terminal 2 -- connect gateway
./run.sh --draeger-tcp 127.0.0.1:9100 --stdout
```

```bash
# Terminal 1 -- Philips simulator
java -cp test-tools IntellivueSimulatorV2 --port 24105

# Terminal 2 -- connect gateway
./run.sh --philips-host 127.0.0.1 --stdout
```

### GUI simulator (CriticalInsightsMonitor)

The primary GUI provides a dark patient-monitor interface with real-time waveforms, adjustable sliders, alarm triggers, and clinical scenarios.

```bash
java -cp test-tools CriticalInsightsMonitor
```

Then connect the gateway to both simulators:

```bash
./run.sh --multi --philips-host 127.0.0.1 --draeger-tcp 127.0.0.1:9100 --stdout
```

See [SIMULATOR.md](SIMULATOR.md) for the full simulator reference.

---

## Running with real devices

### Auto-detect COM ports (recommended)

```bash
./run.sh --auto --jsonl /var/log/openice/bed01.jsonl --stdout
```

The auto-detect probes each port: sends a MEDIBUS request at 19200 baud (Draeger responds), then checks remaining ports at 115200 baud for data (Philips). You can also run detection standalone:

```bash
java -cp test-tools PortDetector
```

### Manual port selection

```bash
./run.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --jsonl /var/log/openice/bed01.jsonl --stdout
```

### AIR-021 setup

The Advantech AIR-021 has two built-in RS-232 COM ports, typically `/dev/ttyS0` and `/dev/ttyS1`.

```bash
# Identify ports
dmesg | grep tty

# Ensure serial access
sudo usermod -aG dialout $USER

# Set JDK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

See [HARDWARE.md](HARDWARE.md) for cable wiring, pin mappings, and device configuration steps.

---

## Web dashboard

Start the gateway with `--web-port` to serve a built-in patient monitor dashboard:

```bash
./run.sh --auto --stdout --web-port 8080
```

Then open `http://localhost:8080` in a browser. The dashboard uses Server-Sent Events (SSE) on the same HTTP port -- no WebSocket or additional ports needed.

Endpoints:

| Path | Description |
|------|-------------|
| `GET /` | HTML dashboard page (dark patient monitor UI) |
| `GET /events` | SSE stream of JSON vital sign events |
| `GET /api/latest` | Most recent event as JSON |

Works with both real devices and simulators.

---

## Production deployment

### Automated deployment

```bash
sudo bash deploy/deploy.sh
```

This script checks for JDK 17, creates an `openice` service user with `dialout` group access, builds the project, installs to `/opt/openice-headless/`, and configures logrotate and systemd.

After deployment, edit the service file for your COM ports and start:

```bash
sudo nano /etc/systemd/system/openice-multidevice.service
sudo systemctl daemon-reload
sudo systemctl enable --now openice-multidevice
sudo journalctl -u openice-multidevice -f
```

### Packaging for transfer

```bash
./package.sh
```

Produces a ~3.4 MB archive that can be copied to target machines.

---

## Monitoring

### Heartbeat

The gateway logs a `HEARTBEAT` line to stderr every 60 seconds:

```
HEARTBEAT | threads: draeger-medibus-supervisor=alive philips-mib-rs232-supervisor=alive | devices: draeger_vent_01=connected(last_data=3s_ago) philips_mx800_01=connected(last_data=1s_ago)
```

Device state changes are logged immediately:

```
DEVICE_STATE draeger_vent_01: connected
DEVICE_STATE philips_mx800_01: disconnected
```

### What to watch for

- `last_data` growing beyond a few seconds: device may be off or cable disconnected
- Thread state `DEAD`: a supervisor thread exited unexpectedly
- `DEVICE_STATE ... disconnected` without a subsequent `connected`: check the physical connection

### Log rotation

Logs are rotated daily (30-day retention, compressed after 1 day, `copytruncate`). Install manually if you did not use `deploy/deploy.sh`:

```bash
sudo cp deploy/logrotate-openice /etc/logrotate.d/openice
```

JSONL output files are created with `0640` permissions (not world-readable).

---

## Troubleshooting

**Permission denied on serial port:**
```bash
sudo usermod -aG dialout $USER   # then log out/in
```

**No data from Draeger:**
Check serial path, baud rate (default 19200), that MEDIBUS is enabled on the ventilator, and that the service user has serial permissions.

**Philips reconnects repeatedly:**
Verify correct monitor IP or serial path, firewall/VLAN settings, cable type (straight-through for Philips, null-modem for Draeger), and that MIB/RS232 is enabled on the monitor.

**HTTP endpoint failures:**
Events are retried automatically. Configure `--dead-letter-jsonl` so failed events are not silently lost.

**`installDist` not found:**
Run `./setup.sh` first or build with `./gradlew :devices:multidevice:installDist`.

**Gradle version mismatch:**
```bash
./gradlew --stop
rm -rf ~/.gradle/caches/7.6/scripts
./gradlew clean build
```
