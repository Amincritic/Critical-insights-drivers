# Fixes and Changes Applied

Complete list of all fixes, improvements, and new features in this package.

---

## 1. Protocol compliance fixes

### Draeger MEDIBUS

- **Even parity (8E1)**: driver now defaults to even parity to match the Evita Channel A spec; configurable via `--serial-parity` / `--draeger-parity` for devices that use different settings
- **ICC handshake**: driver sends Initialize Communication Command (0x51) before polling, as required by the MEDIBUS protocol
- **Checksum fix**: `ChecksumOutputStream` now computes checksums for bulk `write(byte[])` and `write(byte[], int, int)` calls; previously only single-byte `write(int)` updated the checksum, causing incorrect MEDIBUS checksums when array writes were used

### Philips IntelliVue

- **Poll rate limiting**: max 1 message per second per poll type, matching the monitor's 128ms frame processing cycle
- **Keep-alive**: driver sends keep-alive polls automatically if no data is exchanged for 10 seconds
- **OID code mapping**: correct MDC OID codes used for numeric observed values
- **FLOAT-Type decoding**: proper 8-bit exponent + 24-bit mantissa decoding for numeric values

---

## 2. Bug fixes

### Resource leaks

- `TCPSerialProvider.connect()` now closes the `Socket` if `connect()` throws, preventing file descriptor leaks on repeated connection failures
- `HeadlessDraegerGatewayApp.openTransport()` now closes the `Socket` if `getInputStream()` or `getOutputStream()` throws after socket creation
- `SerialProviderFactory.addCandidates()` now closes `BufferedReader` in a finally block so the stream is released even if `readLine()` throws

### Interrupt handling

- `HeadlessDraegerGatewayApp` poller thread now catches `InterruptedException` separately, re-sets the interrupt flag, and exits cleanly instead of swallowing the interrupt
- `HeadlessMultiDeviceGatewayApp` Draeger poller thread has the same fix

### Publishing pipeline

- `MultiJsonPublisher` no longer replays an event into already-successful sinks when another sink fails; prevents duplicate JSONL/stdout rows when HTTP fails after local file output succeeds
- `QueuedJsonPublisher` now rejects `shutdownDrainTimeoutMs <= 0` because `Thread.join(0)` waits forever
- `QueuedJsonPublisher` uses bounded retry backoff multiplication to avoid `long` overflow
- `QueuedJsonPublisher` logs full exception `toString()` instead of only `getMessage()` for publish/dead-letter failures

### Validation and threading

- Multi-device launcher validates device selection before starting publishers/device threads
- Multi-device launcher tracks device supervisor threads, interrupts/joins them on shutdown, then drains/closes the queue
- `SerialProviderFactory.defaultProvider` is now `volatile`, and default-provider initialization is synchronized
- Fixed raw type `Class` to `Class<?>` in `SerialProviderFactory.locateDefaultProvider()`

### Transport

- `TCPSerialProvider.connect()` now uses the supplied connect timeout
- `TCPSerialProvider` validates `host:port` and supports bracketed IPv6 form `[addr]:port`
- `HttpJsonPublisher` refuses plain `http://` by default; use `--allow-insecure-http true` for local/test deployments

---

## 3. Data quality fixes

- Draeger `unitCode` now carries the source metric/code string instead of duplicating the normalized `unit` value
- Draeger numeric parsing handles common forms: `>100`, `<5`, `+12`, `12 %`, `1,234`, and placeholder dashes while preserving `rawValue`
- Draeger events include `unit` and `unitCode` when a safe metric-name heuristic can infer the unit; unknown units are emitted as `null`
- Philips waveform events include explicit `unit` and `unitCode` fields set to `null` because the trimmed `SampleArrayObservedValue` model does not expose units

---

## 4. Build fixes

- Increased Gradle wrapper default heap from 64 MB to 512 MB (`gradlew` line 47) to prevent OOM failures on multi-module builds, especially on constrained devices like Jetson Nano
- Fixed `gradle-wrapper.properties` to use `https://` download URL
- Removed unnecessary JUnit Platform/Vintage configuration; existing tests are JUnit 4 style

---

## 5. Production hardening

- **File permissions**: `FileJsonPublisher` creates JSONL files with `0640` permissions (owner read/write, group read only); patient data is not world-readable
- **Heartbeat logging**: `HeadlessMultiDeviceGatewayApp` logs a `HEARTBEAT` line to stderr every 60 seconds, reporting thread liveness, per-device connection state, and seconds since last data received
- **Device state tracking**: logs `DEVICE_STATE` changes (connected/disconnected) immediately when they occur
- **Systemd hardening**: `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`, `NoNewPrivileges=yes`, `DeviceAllow` restricted to serial ports, `MemoryMax=512M`, `TasksMax=64`, `WatchdogSec=120`
- **Log rotation**: `deploy/logrotate-openice` for daily rotation with 30-day retention, compression, and `copytruncate`
- **Deployment script**: `deploy/deploy.sh` for one-command production deployment (creates service user, builds, installs to `/opt/openice-headless/`, configures logrotate and systemd)

---

## 6. New features

- **Web dashboard**: `WebDashboardPublisher` serves an embedded patient monitor UI on a single HTTP port using Server-Sent Events (SSE); enabled with `--web-port <port>`
- **Compact stdout**: `CompactStdoutPublisher` prints one-line vital sign summaries (e.g. `00:20:06 philips_mx800_01 | HR=72 bpm | SpO2=97%`); enabled with `--stdout compact`
- **Auto-detect COM ports**: `PortDetector` probes serial ports to identify Draeger (19200 baud MEDIBUS response) and Philips (115200 baud data); used by `./run.sh --auto`
- **CriticalInsightsMonitor GUI**: dark patient-monitor simulator with real-time waveforms, adjustable sliders, alarm triggers, clinical scenarios, and noise injection for both Draeger and Philips
- **MedibusSimulatorV2**: enhanced Draeger CLI simulator with multiple device models, realistic vitals variation, and serial support
- **IntellivueSimulatorV2**: enhanced Philips CLI simulator with all 12 vital parameters and realistic variation
- **IntellivueSerialSimulator**: Philips MIB/RS232 serial simulator with full Fixed Baudrate Protocol framing (BOF/EOF, CRC-CCITT, byte escaping)
- **HIL testing**: `hil-test.sh` uses socat virtual serial ports for hardware-in-the-loop testing without physical devices
- **Runtime packaging**: `package.sh` builds a ~3.4 MB distributable archive
- **Multi-device launcher**: `HeadlessMultiDeviceGatewayApp` runs both Philips and Draeger adapters in a single process with shared publisher

---

## Verification

- Full Gradle build (`./gradlew clean build`) passes with all tasks successful and all tests passing
- Changed headless/common publisher classes compiled with `javac`
- Changed Philips/Draeger/multidevice classes compiled with local SLF4J stub to catch syntax/type errors
