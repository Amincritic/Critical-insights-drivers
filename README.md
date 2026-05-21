# OpenICE Headless JSON Gateway — Philips + Draeger

This package is a headless, no-GUI OpenICE-based gateway for:

- Philips IntelliVue over LAN/UDP
- Philips IntelliVue over MIB/RS232
- Draeger MEDIBUS over serial or TCP
- Philips + Draeger in one combined bedside process

It reuses the OpenICE protocol parsers and publishes normalized JSON to stdout, JSONL files, and/or HTTP POST endpoints.

> This is still gateway/integration software, not a certified medical device application. Validate against real devices and your hospital/network requirements before use.

---

## Quick start

```bash
# 1. Install JDK 17 (skip if already installed)
sudo apt update && sudo apt install -y openjdk-17-jdk

# 2. Setup (downloads Gradle, builds, compiles test tools)
./setup.sh

# 3. Test without real devices
./test.sh

# 4. Run with real devices
./run.sh --draeger-serial /dev/ttyS1 --stdout
./run.sh --philips-serial /dev/ttyS0 --stdout
./run.sh --multi --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1 --stdout

# 5. Deploy as a systemd service
sudo bash deploy/deploy.sh
sudo nano /etc/systemd/system/openice-multidevice.service   # edit COM ports
sudo systemctl enable --now openice-multidevice
sudo journalctl -u openice-multidevice -f
```

### Script reference

| Script | Purpose |
|--------|---------|
| `./setup.sh` | One-time setup: detects JDK, downloads Gradle, builds everything, compiles test tools |
| `./test.sh` | Runs a fake Draeger device + gateway for 30 seconds, reports events captured |
| `./test.sh 9100 60` | Same but on port 9100 for 60 seconds |
| `./run.sh --help` | Shows all run options with examples |
| `./run.sh [options]` | Runs the gateway with real devices (see `--help` for modes) |
| `sudo bash deploy/deploy.sh` | Production deployment: creates user, installs service, logrotate |

See sections below for detailed options and configuration.

---

## What was fixed in this version

- Added Gradle `application` packaging for runnable modules, so `installDist` now produces runnable scripts.
- Removed the invalid `https://mvnrepository.com` repository entry from the root Gradle build.
- Added an SLF4J runtime backend using `slf4j-simple` so logs are visible without extra setup.
- Fixed Philips LAN/UDP reconnect logic by recreating `NetworkLoop`, channel, and adapter on every reconnect attempt.
- Fixed Philips MIB/RS232 reconnect logic by recreating the serial bridge, UDP loopback pair, `NetworkLoop`, channel, and adapter on every reconnect attempt.
- Added bounded publish retry/backoff in the queued JSON publisher.
- Added optional JSONL dead-letter output for events that still fail after all publish attempts.
- Fixed publisher shutdown so it stops accepting new events, drains queued events, and only then closes downstream sinks.
- Added HTTP custom headers through repeated `--http-header 'Name: value'` options.
- Fixed `SerialProviderFactory` logging precedence bug.
- Added Draeger `unit` / `unitCode` fields where a safe heuristic can infer units.
- Updated README commands to match the patched Gradle/application layout.

---

## Device prerequisites

Before connecting, ensure the following settings on the physical devices:

**Philips MX800 (MIB/RS232):**
- Go to Configuration > Network > MIB/RS232 and enable data export
- Serial settings are fixed at 115200 baud, 8N1, no flow control (set automatically by the driver)
- Use a straight-through RS-232 cable (not null-modem)

**Draeger ventilator (MEDIBUS):**
- Enable the MEDIBUS protocol on the ventilator (consult Draeger service manual)
- Default baud rate is 19200 (adjustable with `--draeger-baud`)
- Serial settings: 8 data bits, no parity, 1 stop bit, no flow control

**Advantech AIR-021 (or other edge gateway):**
- Identify your COM ports: `dmesg | grep tty` (typically `/dev/ttyS0` and `/dev/ttyS1`)
- Ensure your user has serial access: `sudo usermod -aG dialout $USER`
- Use JDK 17: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`

---

## Directory layout

```text
openice-headless-json-gateway-patched/
  settings.gradle
  build.gradle
  gradlew
  gradle/wrapper/

  devices/
    common/
    philips/
    draeger/
    multidevice/

  interop-lab/
    purejavacomm/
    demo-purejavacomm/
```

This is a no-GUI package. Do not use the original OpenICE demo-app entry point:

```bash
./gradlew :interop-lab:demo-apps:run
```

Use the headless modules below instead.

---

## Java requirement

Use JDK 17.

Ubuntu x86_64:

```bash
sudo apt update
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

Jetson ARM64:

```bash
sudo apt update
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$JAVA_HOME/bin:$PATH
```

Verify:

```bash
java -version
javac -version
```

Expected major version: Java 17.

If Gradle was previously run using a newer Java version:

```bash
./gradlew --stop
rm -rf ~/.gradle/caches/7.6/scripts
```

---

## Build

From the package root:

```bash
cd openice-headless-json-gateway-patched
```

Compile everything:

```bash
./gradlew clean build
```

Build runnable distributions:

```bash
./gradlew :devices:philips:installDist
./gradlew :devices:draeger:installDist
./gradlew :devices:multidevice:installDist
```

Generated launchers:

```text
devices/philips/build/install/philips/bin/philips
devices/philips/build/install/philips/bin/philips-serial
devices/draeger/build/install/draeger/bin/draeger
devices/multidevice/build/install/multidevice/bin/multidevice
```

For quick development runs without installing distributions:

```bash
./gradlew :devices:philips:runHeadlessPhilips --args="--help"
./gradlew :devices:philips:runHeadlessPhilipsSerial --args="--help"
./gradlew :devices:draeger:runHeadlessDraeger --args="--help"
./gradlew :devices:multidevice:runHeadlessMultiDevice --args="--help"
```

---

## Shared output options

All launchers support these common output options:

```text
--stdout true|false
--jsonl <path>
--dead-letter-jsonl <path>
--http-url <url>
--http-header 'Name: value'
--http-timeout-ms <milliseconds>
--queue-capacity <count>
--publish-attempts <count>
--publish-retry-backoff-ms <milliseconds>
--shutdown-drain-timeout-ms <milliseconds>
--reconnect-ms <milliseconds>
```

Behavior:

- If no `--jsonl` and no `--http-url` are provided, stdout JSON is enabled by default.
- If `--jsonl` or `--http-url` is provided, stdout is disabled unless you explicitly pass `--stdout true`.
- HTTP failures are retried by the queued publisher.
- After all publish attempts fail, the event is written to `--dead-letter-jsonl` if configured.
- If no dead-letter file is configured, the event is dropped after retries and an error is logged.

Example HTTP auth header:

```bash
--http-header 'Authorization: Bearer YOUR_TOKEN'
```

Multiple headers are allowed:

```bash
--http-header 'Authorization: Bearer YOUR_TOKEN' \
--http-header 'X-Gateway-ID: jetson_nicu_01'
```

---

## Philips over LAN/UDP

Use when the Philips IntelliVue monitor is reachable by IP.

Development run:

```bash
./gradlew :devices:philips:runHeadlessPhilips --args="\
  --host 192.168.10.50 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id philips_monitor_01 \
  --jsonl /var/log/openice/philips-events.jsonl \
  --dead-letter-jsonl /var/log/openice/philips-dead-letter.jsonl \
  --stdout true"
```

Installed launcher:

```bash
devices/philips/build/install/philips/bin/philips \
  --host 192.168.10.50 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id philips_monitor_01 \
  --jsonl /var/log/openice/philips-events.jsonl \
  --dead-letter-jsonl /var/log/openice/philips-dead-letter.jsonl
```

Important Philips LAN/UDP options:

```text
--host <monitor-ip>          required
--port <port>                default: Philips IntelliVue default unicast port
--local-port <port>          default: Philips IntelliVue default unicast port
```

`--host` is required. The app intentionally refuses to default to localhost for a bedside gateway.

---

## Philips over MIB/RS232

Use when the Philips monitor is connected through MIB/RS232, usually through a USB serial adapter.

Typical Linux serial paths:

```text
/dev/ttyUSB0
/dev/ttyS0
/dev/philips_monitor_01     recommended stable udev symlink
```

Development run:

```bash
./gradlew :devices:philips:runHeadlessPhilipsSerial --args="\
  --serial /dev/philips_monitor_01 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id philips_monitor_01 \
  --jsonl /var/log/openice/philips-events.jsonl \
  --dead-letter-jsonl /var/log/openice/philips-dead-letter.jsonl"
```

Installed launcher:

```bash
devices/philips/build/install/philips/bin/philips-serial \
  --serial /dev/philips_monitor_01 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id philips_monitor_01 \
  --jsonl /var/log/openice/philips-events.jsonl \
  --dead-letter-jsonl /var/log/openice/philips-dead-letter.jsonl
```

Philips MIB/RS232 settings are fixed by the OpenICE `RS232Adapter`:

```text
115200 baud, 8 data bits, no parity, 1 stop bit, no flow control
```

The serial launcher now fully rebuilds the serial bridge and loopback UDP parser path after failure.

---

## Draeger MEDIBUS

Draeger can run over serial or TCP.

Serial development run:

```bash
./gradlew :devices:draeger:runHeadlessDraeger --args="\
  --serial /dev/draeger_vent_01 \
  --serial-baud 19200 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/openice/draeger-events.jsonl \
  --dead-letter-jsonl /var/log/openice/draeger-dead-letter.jsonl"
```

TCP development run:

```bash
./gradlew :devices:draeger:runHeadlessDraeger --args="\
  --tcp-host 192.168.10.60 \
  --tcp-port 4001 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/openice/draeger-events.jsonl"
```

Installed launcher:

```bash
devices/draeger/build/install/draeger/bin/draeger \
  --serial /dev/draeger_vent_01 \
  --serial-baud 19200 \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/openice/draeger-events.jsonl
```

Important Draeger options:

```text
--serial <device>              serial mode
--serial-baud <baud>           default: 19200
--tcp-host <ip> --tcp-port <p> TCP mode
--poll-ms <milliseconds>       default: 1000
```

Choose either serial or TCP, not both.

Draeger JSON now includes `unit` and `unitCode` when a safe metric-name heuristic can infer the unit. Unknown units are emitted as `null` rather than guessed aggressively.

---

## Philips + Draeger together

Use the multidevice launcher when one Jetson/edge box should run both device adapters into one shared queue/publisher.

Philips LAN/UDP + Draeger serial:

```bash
./gradlew :devices:multidevice:runHeadlessMultiDevice --args="\
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --philips-host 192.168.10.50 \
  --philips-device-id philips_monitor_01 \
  --draeger-serial /dev/draeger_vent_01 \
  --draeger-device-id draeger_vent_01 \
  --jsonl /var/log/openice/bed12-events.jsonl \
  --dead-letter-jsonl /var/log/openice/bed12-dead-letter.jsonl \
  --http-url https://hospital.example/api/events \
  --stdout true"
```

Philips MIB/RS232 + Draeger serial:

```bash
./gradlew :devices:multidevice:runHeadlessMultiDevice --args="\
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --philips-serial /dev/philips_monitor_01 \
  --philips-device-id philips_monitor_01 \
  --draeger-serial /dev/draeger_vent_01 \
  --draeger-device-id draeger_vent_01 \
  --jsonl /var/log/openice/bed12-events.jsonl \
  --dead-letter-jsonl /var/log/openice/bed12-dead-letter.jsonl"
```

Installed launcher:

```bash
devices/multidevice/build/install/multidevice/bin/multidevice \
  --gateway-id jetson_nicu_01 \
  --bed-id bed_12 \
  --philips-serial /dev/philips_monitor_01 \
  --philips-device-id philips_monitor_01 \
  --draeger-serial /dev/draeger_vent_01 \
  --draeger-device-id draeger_vent_01 \
  --jsonl /var/log/openice/bed12-events.jsonl \
  --dead-letter-jsonl /var/log/openice/bed12-dead-letter.jsonl
```

Multidevice Philips options:

```text
--philips-host <ip>          Philips LAN/UDP mode
--philips-serial <device>    Philips MIB/RS232 mode
--philips-port <port>
--philips-local-port <port>
--philips-device-id <id>
```

Choose only one Philips transport: `--philips-host` or `--philips-serial`.

Multidevice Draeger options:

```text
--draeger-serial <device>
--draeger-device-id <id>
--draeger-baud <baud>        default: 19200
--draeger-poll-ms <ms>       default: 1000
```

---

## Production deployment

### Automated deployment

The simplest way to deploy is with the included script:

```bash
sudo bash deploy/deploy.sh
```

This script:

1. Checks for JDK 17
2. Creates the `openice` service user with `dialout` group access
3. Creates `/var/log/openice/` with restricted permissions
4. Downloads Gradle 7.6 if needed and builds the project
5. Installs to `/opt/openice-headless/`
6. Installs logrotate and systemd configs

After deployment, edit the service file to match your COM ports and device IDs, then start:

```bash
sudo nano /etc/systemd/system/openice-multidevice.service
sudo systemctl daemon-reload
sudo systemctl enable openice-multidevice
sudo systemctl start openice-multidevice
```

### Manual deployment

Create a service user and log directory:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin openice || true
sudo usermod -aG dialout openice
sudo mkdir -p /opt/openice-headless /var/log/openice
sudo chown openice:openice /var/log/openice
sudo chmod 750 /var/log/openice
```

Copy this package to `/opt/openice-headless`, build it, then install the service and logrotate config:

```bash
sudo cp openice-multidevice.service /etc/systemd/system/
sudo cp deploy/logrotate-openice /etc/logrotate.d/openice
```

### Advantech AIR-021 setup

The AIR-021 has two built-in RS-232 COM ports, typically `/dev/ttyS0` and `/dev/ttyS1`. Identify which port connects to which device:

```bash
dmesg | grep tty
setserial -g /dev/ttyS*
```

Example for Philips MX800 on COM1 + Draeger ventilator on COM2:

```bash
devices/multidevice/build/install/multidevice/bin/multidevice \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --philips-serial /dev/ttyS0 \
  --philips-device-id philips_mx800_01 \
  --draeger-serial /dev/ttyS1 \
  --draeger-device-id draeger_vent_01 \
  --draeger-baud 19200 \
  --jsonl /var/log/openice/bed01.jsonl \
  --dead-letter-jsonl /var/log/openice/bed01-dead-letter.jsonl
```

The AIR-021 runs x86_64 Linux, so use:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### Systemd service (hardened)

The included `openice-multidevice.service` has security hardening enabled:

- `ProtectSystem=strict` — filesystem is read-only except `/var/log/openice`
- `ProtectHome=yes`, `PrivateTmp=yes`, `NoNewPrivileges=yes` — sandboxing
- `DeviceAllow=/dev/ttyS0 rw`, `DeviceAllow=/dev/ttyS1 rw` — only the two serial ports are accessible
- `MemoryMax=512M`, `TasksMax=64` — resource limits
- `WatchdogSec=120` — systemd restarts the process if it hangs for 2 minutes
- `Restart=always`, `RestartSec=5` — auto-restart on crash

Edit the `DeviceAllow` lines if your serial ports are different (e.g. `/dev/ttyUSB0`).

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable openice-multidevice
sudo systemctl start openice-multidevice
sudo journalctl -u openice-multidevice -f
```

---

## Monitoring

### Heartbeat logging

The multi-device gateway logs a `HEARTBEAT` line to stderr every 60 seconds:

```
HEARTBEAT | threads: draeger-medibus-supervisor=alive philips-mib-rs232-supervisor=alive | devices: draeger_vent_01=connected(last_data=3s_ago) philips_mx800_01=connected(last_data=1s_ago)
```

Device state changes are logged immediately:

```
DEVICE_STATE draeger_vent_01: connected
DEVICE_STATE philips_mx800_01: disconnected
```

View live logs:

```bash
sudo journalctl -u openice-multidevice -f
```

Filter for heartbeats or state changes:

```bash
sudo journalctl -u openice-multidevice | grep HEARTBEAT
sudo journalctl -u openice-multidevice | grep DEVICE_STATE
```

### What to watch for

- `last_data` growing beyond a few seconds: the device may be powered off or the cable disconnected
- Thread state `DEAD`: a supervisor thread has exited unexpectedly
- `DEVICE_STATE ... disconnected` without a subsequent `connected`: check the physical connection

### Log rotation

Logs are rotated daily by the included logrotate config (`deploy/logrotate-openice`):

- 30 days retention
- Compressed after 1 day
- `copytruncate` so the running process does not need a restart
- New files created with `0640` permissions (owner read/write, group read only)

Install manually if you did not use `deploy/deploy.sh`:

```bash
sudo cp deploy/logrotate-openice /etc/logrotate.d/openice
```

### File permissions

JSONL output files are created with `0640` permissions (owner read/write, group read only). Patient data is not world-readable.

### Service health check

```bash
sudo systemctl status openice-multidevice
ls -la /var/log/openice/
du -sh /var/log/openice/
```

---

## Stable serial-device names with udev

USB serial devices can move between `/dev/ttyUSB0`, `/dev/ttyUSB1`, etc. Use udev symlinks for stable service startup.

Find adapter attributes:

```bash
udevadm info -a -n /dev/ttyUSB0 | less
```

Example rule:

```udev
SUBSYSTEM=="tty", ATTRS{idVendor}=="067b", ATTRS{idProduct}=="2303", SYMLINK+="philips_monitor_01", GROUP="dialout", MODE="0660"
```

Reload rules:

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger
```

Then use:

```text
/dev/philips_monitor_01
/dev/draeger_vent_01
```

Make sure the service user is in the `dialout` group if needed:

```bash
sudo usermod -aG dialout openice
```

---

## JSON shape

Common fields:

```json
{
  "gatewayId": "jetson_nicu_01",
  "bedId": "bed_12",
  "deviceId": "philips_monitor_01",
  "vendor": "philips",
  "protocol": "intellivue",
  "eventType": "vital",
  "timestamp": "2026-05-21T12:00:00Z"
}
```

Philips numeric example:

```json
{
  "eventType": "numeric",
  "metric": "MDC_PULS_OXIM_SAT_O2",
  "metricCode": "...",
  "unit": "%",
  "unitCode": "%",
  "value": 98
}
```

Draeger measured-data example:

```json
{
  "eventType": "vital",
  "vendor": "draeger",
  "protocol": "medibus",
  "metric": "Paw",
  "metricCode": "Paw",
  "unit": "cmH2O",
  "unitCode": "cmH2O",
  "rawValue": "12",
  "value": 12
}
```

If a Draeger unit cannot be inferred safely, `unit` and `unitCode` are `null`.

---

## Testing without devices

A fake Draeger MEDIBUS device simulator is included in `test-tools/`. It responds to MEDIBUS poll commands with synthetic vital signs (tidal volume, respiratory rate, airway pressure, FiO2, PEEP, compliance, resistance) that vary over time.

### Compile the simulator

```bash
javac test-tools/FakeDraegerDevice.java
```

### Run in two terminals

**Terminal 1** — start the fake device on TCP port 9100:

```bash
java -cp test-tools FakeDraegerDevice 9100
```

You should see:

```
FakeDraegerDevice listening on port 9100
Waiting for gateway connection...
```

**Terminal 2** — connect the gateway:

```bash
devices/draeger/build/install/draeger/bin/draeger \
  --tcp-host 127.0.0.1 --tcp-port 9100 \
  --gateway-id test_gw --bed-id test_bed \
  --device-id test_draeger --stdout true
```

### Expected output

The gateway will print JSON events every second:

```json
{"gatewayId":"test_gw","bedId":"test_bed","deviceId":"test_draeger","vendor":"draeger","protocol":"medibus","eventType":"vital","metric":"BreathingPressure_(in_mbar)","unit":"cmH2O","rawValue":"  23","value":23}
```

The fake device terminal will log each command it receives:

```
Gateway connected from /127.0.0.1:54321
  Received command: 0x52 (ReqDeviceId) [cycle 1]
    -> Device ID: Evita V500  SN:FAKE001  SW:03.20n
  Received command: 0x44 (ReqDateTime) [cycle 2]
    -> DateTime: 210526143000
  Received command: 0x24 (ReqMeasuredCP1) [cycle 3]
    -> CP1: VT=450mL RR=16 Paw=22cmH2O FiO2=40%
```

### Testing with JSONL output

```bash
devices/draeger/build/install/draeger/bin/draeger \
  --tcp-host 127.0.0.1 --tcp-port 9100 \
  --gateway-id test_gw --bed-id test_bed \
  --device-id test_draeger \
  --jsonl /tmp/test-draeger.jsonl --stdout true
```

Then inspect the file:

```bash
tail -f /tmp/test-draeger.jsonl
wc -l /tmp/test-draeger.jsonl   # count events received
```

### Custom port

```bash
java -cp test-tools FakeDraegerDevice 9200
```

Then use `--tcp-port 9200` in the gateway command.

---

## Troubleshooting

### `installDist` not found

Use this updated package. The runnable modules now apply Gradle's `application` plugin.

Build distributions with:

```bash
./gradlew :devices:philips:installDist :devices:draeger:installDist :devices:multidevice:installDist
```

### No logs appear

This package includes `slf4j-simple`. You should see log output on stderr/stdout. For more verbose logs, set JVM/system properties as needed.

### Permission denied on `/dev/ttyUSB0`

Add your user or service user to `dialout`:

```bash
sudo usermod -aG dialout $USER
sudo usermod -aG dialout openice
```

Log out/in or restart the service.

### HTTP endpoint is down

The queued publisher retries failed publishes. Configure a dead-letter file so failed events are not silently lost:

```bash
--dead-letter-jsonl /var/log/openice/dead-letter.jsonl
```

### Philips reconnects repeatedly

Check:

- correct monitor IP or serial path
- firewall/network VLAN
- serial cable and USB adapter
- Philips MIB/RS232 physical connection
- local port conflict if using LAN/UDP

### Draeger has no data

Check:

- correct serial path
- baud rate, usually `19200`
- MEDIBUS protocol enabled on the ventilator
- service user has serial permissions
- poll interval is not too aggressive

---

## Notes on `demo-purejavacomm`

This package avoids the original OpenICE GUI/demo app, but still keeps `interop-lab:demo-purejavacomm` because the serial-provider implementation is reused by the headless serial launchers.

So “no demo app” means no GUI/demo runtime path, not removal of every source folder with `demo` in its name.
