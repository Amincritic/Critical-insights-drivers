# Critical Insights

Critical Insights is a Java gateway and simulator workspace for medical-device data integration. It connects to supported bedside-device protocols, decodes the device-specific traffic, normalizes the result into canonical JSON events, and publishes those events to stdout, JSONL files, HTTP endpoints, and the built-in web dashboard.

The browser UI does not decode Philips or Draeger protocol frames. All protocol decoding happens in the gateway drivers. The UI, replay simulator, and external consumers receive decoded canonical events.

## Repository Layout

```text
critical-insights/
  gateway/      production gateway runtime, protocol drivers, schemas, and web publisher
  simulator/    GUI launcher, protocol simulators, canonical replay, and scenario fixtures
  scripts/      top-level launchers and smoke tests
  docs/         schema, simulator, integration, and development documentation
```

The gateway code is OpenICE-derived, but the active product workflow is the top-level `critical-insights` workspace.

## Which Path Should I Follow?

Use this table before reading the rest of the docs:

| Goal | Start here | Notes |
| --- | --- | --- |
| Run the simulator GUI, gateway, and web dashboard locally | This README, starting at `Quick Start: GUI Simulator, Drivers, And Web Dashboard` | Best first run for development. |
| Run protocol simulators without the GUI | This README, `Quick Start: Headless Protocol Simulators` | Exercises the real drivers. |
| Test the web/API with deterministic JSON only | This README, `Quick Start: Canonical Replay` | Does not test protocol decoding. |
| Test RS232 locally without hardware | This README, `Quick Start: RS232 Simulation Without Hardware` | Requires `socat`. |
| Deploy only drivers on an edge device | `docs/edge-driver-deployment.md` | No simulator and no web dashboard required. |
| Deploy only the Philips driver | `gateway/devices/philips-intellivue/EDGE.md` | Device-specific commands and output examples. |
| Deploy only the Draeger driver | `gateway/devices/draeger-medibus/EDGE.md` | Device-specific commands, profiles, and output examples. |
| Build your own dashboard/backend | `docs/dashboard-integration.md` | Consume canonical events through SSE, JSONL, or HTTP POST. |
| Understand the JSON event fields | `docs/canonical-schema.md` | Canonical topics and field definitions. |
| Wire real RS232 hardware | `gateway/HARDWARE.md` | Cable, serial settings, and safety notes. |
| Add another device later | `docs/adding-devices.md` | Required structure for future device modules. |

## Supported Devices And Transports

| Device family | Protocol | Transport | Gateway driver | Simulator |
| --- | --- | --- | --- | --- |
| Philips IntelliVue / MX series | IntelliVue Data Export | LAN/UDP | Numerics, waveforms, patient alerts, technical alerts, identity, connectivity | Yes |
| Philips IntelliVue / MX series | MIB/RS232 fixed baudrate | Serial RS232 | Numerics, waveforms, patient alerts, technical alerts, identity, connectivity | Yes |
| Draeger ventilators | MEDIBUS | TCP | Numerics, waveforms, alerts, settings, text messages, identity, connectivity | Yes |
| Draeger ventilators | MEDIBUS | Serial RS232 | Numerics, waveforms, alerts, settings, text messages, identity, connectivity | Yes |
| Canonical replay | Canonical JSONL | HTTP POST to gateway web API | Dashboard/API only, no protocol decode | Yes |

Use protocol simulation when you want to exercise the real driver decode path. Use canonical replay when you want deterministic dashboard/API behavior without testing protocol decoding.

## Requirements

- Java 17.
- Bash-compatible shell.
- `socat` for local RS232 simulation without real hardware.
- Real RS232 ports or USB-to-RS232 adapters for real-device validation.

On macOS, the virtual serial helper creates `/tmp/...` symlinks that point to `/dev/ttys*`. If Java rejects the symlink, use the resolved `/dev/ttys*` path printed by `scripts/start-virtual-serial-pairs.sh`.

## Build

Run from this directory:

```bash
cd critical-insights

cd simulator
../gateway/gradlew installDist

cd ../gateway
./gradlew :runtime:installDist

cd ..
```

The simulator currently uses the Gradle wrapper under `gateway/`.

## Quick Start: GUI Simulator, Drivers, And Web Dashboard

Use three terminals.

Terminal 1: start the simulator GUI:

```bash
cd critical-insights
./scripts/run-simulator-gui.sh
```

In the GUI, start the Philips IntelliVue LAN/UDP simulator and the Draeger MEDIBUS TCP simulator. The default simulator endpoints are:

```text
Philips LAN/UDP simulator: UDP 24105
Draeger TCP simulator:    TCP 9100
```

Terminal 2: start the gateway drivers and web dashboard:

```bash
cd critical-insights
./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Terminal 3 or browser: open the dashboard:

```text
http://localhost:8081
```

The dashboard includes a device selector. When multiple devices are running, choose `philips_mx800_01` or `draeger_vent_01` from the selector. You can also open a device directly:

```text
http://localhost:8081/?device=philips_mx800_01
http://localhost:8081/?device=draeger_vent_01
```

## Quick Start: Headless Protocol Simulators

If you do not need the GUI, start the default local protocol simulators:

```bash
cd critical-insights
./scripts/run-simulator.sh --config simulator/config/local.properties
```

In another terminal, start the gateway:

```bash
cd critical-insights
./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Open:

```text
http://localhost:8081
```

## Quick Start: Canonical Replay

Canonical replay bypasses protocol decoding and posts canonical JSON events directly into the web dashboard API. Use it for UI demos, schema checks, or deterministic frontend work.

Terminal 1: start the gateway in web-only mode:

```bash
cd critical-insights
./scripts/run-gateway.sh --web-only \
  --web-port 8099 \
  --stdout false \
  --output-format canonical
```

Terminal 2: start replay:

```bash
cd critical-insights
./scripts/run-simulator.sh --config simulator/config/replay.properties
```

Open:

```text
http://localhost:8099
```

Other replay scenarios are documented in `docs/simulator-scenarios.md`.

## Quick Start: RS232 Simulation Without Hardware

Install `socat`, then create virtual serial pairs:

```bash
cd critical-insights
./scripts/start-virtual-serial-pairs.sh
```

Keep that terminal open. It creates stable names:

```text
/tmp/philips-device   simulator side for Philips
/tmp/philips-gateway  gateway side for Philips
/tmp/draeger-device   simulator side for Draeger
/tmp/draeger-gateway  gateway side for Draeger
```

Terminal 2: start the RS232 protocol simulators:

```bash
cd critical-insights
./scripts/run-simulator.sh --config simulator/config/serial-rs232.properties
```

Terminal 3: start the gateway against the gateway ends of the virtual serial pairs:

```bash
cd critical-insights
./scripts/run-gateway.sh --multi \
  --philips-serial /tmp/philips-gateway \
  --draeger-serial /tmp/draeger-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Open:

```text
http://localhost:8081
```

Automated RS232 smoke test:

```bash
./scripts/test-rs232-simulators.sh
```

The test requires Philips numeric, Philips waveform, Philips alert, and Draeger numeric events to reach the gateway output.

## Running Only One Device

Philips LAN/UDP:

```bash
./scripts/run-gateway.sh --philips-host 127.0.0.1 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Philips MIB/RS232:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Use `--philips-baud 19200` only if the monitor-side MIB/RS232 port is configured for 19200 fixed-baudrate mode. The default is 115200 baud, 8 data bits, no parity, 1 stop bit, no flow control.

Draeger TCP:

```bash
./scripts/run-gateway.sh --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Draeger RS232:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

For Draeger V500 testing, use:

```bash
./scripts/run-gateway.sh --draeger-tcp 127.0.0.1:9100 \
  --draeger-profile v500 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Supported Draeger profiles are `v500`, `intensive-care`, `evita`, `savina`, and `fabius`.

## Gateway Options

Run:

```bash
./scripts/run-gateway.sh --help
```

Common options:

| Option | Meaning |
| --- | --- |
| `--multi` | Run the combined runtime with one or more configured device drivers. |
| `--web-only` | Run only the web/API publisher for canonical replay. |
| `--philips-host <ip>` | Connect Philips IntelliVue LAN/UDP. Use `127.0.0.1` for the simulator. |
| `--philips-serial <port>` | Connect Philips MIB/RS232 over serial. |
| `--philips-baud <baud>` | Optional Philips serial baud override, usually `115200` or `19200`. |
| `--draeger-tcp <host:port>` | Connect Draeger MEDIBUS over TCP. |
| `--draeger-serial <port>` | Connect Draeger MEDIBUS over serial. |
| `--draeger-baud <baud>` | Optional Draeger serial baud override. |
| `--serial-parity none|even|odd` | Optional serial parity override for devices that differ from defaults. |
| `--draeger-profile <profile>` | MEDIBUS command profile. |
| `--gateway-id <id>` | Identifier added to each canonical event. Default: `air021_01`. |
| `--bed-id <id>` | Bed/location identifier added to each canonical event. Default: `bed_01`. |
| `--device-id <id>` | Override single-device ID. In `--multi`, Philips and Draeger also have device-specific IDs internally. |
| `--web-port <port>` | Start built-in dashboard and API on this port. |
| `--stdout true|false|compact` | Print canonical JSON to terminal. `compact` prints a human-readable summary. |
| `--jsonl <path>` | Append one canonical JSON event per line to a file. |
| `--http-url <url>` | POST each canonical JSON event to an external HTTP endpoint. |
| `--http-header '<name>: <value>'` | Add a header to HTTP POST publishing. Can be repeated. |
| `--output-format canonical` | Compatibility flag. Runtime output is canonical only. |

`legacy` and `both` output formats are intentionally not supported at runtime.

## Web Dashboard And API

When the gateway is started with `--web-port`, these endpoints are available:

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/` | `GET` | Built-in web dashboard. |
| `/events` | `GET` | Server-Sent Events stream of canonical JSON events. |
| `/api/latest` | `GET` | Most recent canonical event seen by the web publisher. |
| `/api/events` | `POST` | Accept one canonical JSON event, used by replay and UI tests. |

Example SSE consumer:

```bash
curl -N http://localhost:8081/events
```

Example latest-event check:

```bash
curl http://localhost:8081/api/latest
```

Example replay post:

```bash
curl -X POST http://localhost:8081/api/events \
  -H 'Content-Type: application/json' \
  --data '{"schema_version":"1.0","topic":"DeviceConnectivity","unique_device_identifier":"demo_01","state":"Connected","type":"Replay","presentation_time":"2026-05-24T00:00:00Z"}'
```

For building your own dashboard, consume `/events`, `--jsonl`, or `--http-url` and implement against the canonical event schema in `docs/canonical-schema.md`. Do not parse `--stdout compact`; it is for people reading the terminal.

## Driver Output Format

Drivers publish canonical JSON only. Each event is a complete JSON object with:

- common routing fields such as `schema_version`, `topic`, `unique_device_identifier`, `presentation_time`, `gateway_id`, `bed_id`, `vendor`, and `protocol`
- topic-specific payload fields
- optional protocol metadata when useful

Common topics:

| Topic | Used for |
| --- | --- |
| `DeviceConnectivity` | Connection state and transport status. |
| `DeviceIdentity` | Device model, manufacturer, serial, and build metadata. |
| `Patient` / `PatientDemographics` | Patient context when the protocol provides it. |
| `Numeric` | One scalar observation such as HR, SpO2, RR, FiO2, PEEP, or temperature. |
| `SampleArray` | One decoded waveform/sample-array event. |
| `PatientAlert` | Clinical/patient alarms. |
| `TechnicalAlert` | Device, sensor, lead, communication, or technical alarms. |
| `DeviceSetting` | Decoded ventilator/device setting. |
| `TextMessage` | Decoded device status or text message. |
| `DeviceTime` | Device-reported time. |

Example numeric event:

```json
{
  "schema_version": "1.0",
  "topic": "Numeric",
  "unique_device_identifier": "philips_mx800_01",
  "metric_id": "NOM_PULS_OXIM_SAT_O2",
  "vendor_metric_id": "NOM_PULS_OXIM_SAT_O2",
  "instance_id": 0,
  "unit_id": "NOM_DIM_PERCENT",
  "value": 97.0,
  "device_time": null,
  "presentation_time": "2026-05-24T21:01:03.573649Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "intellivue_udp",
  "schema_valid": true
}
```

Example waveform event:

```json
{
  "schema_version": "1.0",
  "topic": "SampleArray",
  "unique_device_identifier": "philips_mx800_01",
  "metric_id": "NOM_ECG_ELEC_POTL_II",
  "vendor_metric_id": "NOM_ECG_ELEC_POTL_II",
  "instance_id": 0,
  "unit_id": "NOM_DIM_MILLI_BAR",
  "frequency": 500,
  "values": [129, 130, 126, 128],
  "sample_period_us": 2000,
  "sample_size_bits": 8,
  "significant_bits": 8,
  "array_size": 4,
  "presentation_time": "2026-05-24T21:11:46.552516Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

For a complete integration guide, see `docs/dashboard-integration.md`. For field-by-field schema details, see `docs/canonical-schema.md`.

## Data Flow

Live-device path:

```text
device or protocol simulator
  -> protocol transport
  -> gateway protocol driver
  -> decoded observations
  -> canonical JSON event
  -> stdout / JSONL / HTTP POST / SSE / web dashboard
```

Canonical replay path:

```text
canonical JSONL fixture
  -> replay simulator
  -> POST /api/events
  -> web publisher
  -> SSE / dashboard
```

## Verification

Run these after changing drivers, canonical output, simulator behavior, or web rendering:

```bash
./scripts/test-canonical-replay.sh
./scripts/smoke-simulator-gui.sh
./scripts/test-tcp-udp-simulators.sh
./scripts/test-rs232-simulators.sh
```

Run the Java build after code changes:

```bash
cd gateway
./gradlew :runtime:installDist
```

Real hardware validation is separate from simulator success. Use `gateway/HARDWARE.md` before connecting bedside devices.

## More Documentation

- `docs/README.md`: documentation index.
- `docs/canonical-schema.md`: canonical event contract.
- `docs/dashboard-integration.md`: how to consume events in your own dashboard or backend.
- `docs/edge-driver-deployment.md`: how to run only the drivers on an edge device.
- `docs/simulator-scenarios.md`: simulator configs, fixtures, and scenarios.
- `docs/adding-devices.md`: how to add more devices/protocols.
- `gateway/HARDWARE.md`: real RS232 wiring and serial settings.
- `gateway/ARCHITECTURE.md`: gateway internals.
