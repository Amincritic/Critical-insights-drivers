# Edge Driver Deployment

This guide is for teams that want to run only the Critical Insights gateway drivers on an edge device. It assumes you do not need the simulator GUI, simulator runtime, or built-in web dashboard.

Use this mode when the edge device should only:

- connect to real bedside devices
- decode the device protocols
- publish canonical JSON to a file, stdout, or an HTTP endpoint

The simulator and web dashboard are optional development tools. They are not required for driver-only deployment.

## Driver-Only Data Flow

```text
Philips or Draeger device
  -> serial/TCP/UDP transport
  -> Critical Insights protocol driver
  -> decoded canonical JSON event
  -> stdout / JSONL / HTTP POST
```

The edge driver output is canonical JSON only. External systems should consume the canonical events documented in `canonical-schema.md`.

## What To Build On The Edge Device

From the top-level workspace:

```bash
cd critical-insights/gateway
./gradlew :runtime:installDist
```

The combined runtime and individual driver launchers are installed under:

```text
gateway/runtime/build/install/gateway-runtime/bin/gateway-runtime
gateway/devices/philips-intellivue/build/install/philips/bin/
gateway/devices/draeger-medibus/build/install/draeger/bin/
```

For normal deployment, prefer the top-level wrapper:

```bash
cd critical-insights
./scripts/run-gateway.sh --help
```

The wrapper keeps command lines stable even if the internal Gradle install path changes.

## Do Not Start The Dashboard

The dashboard is enabled only when you pass `--web-port`.

For driver-only deployment, omit `--web-port`:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --jsonl /var/log/critical-insights/philips.jsonl \
  --output-format canonical
```

No simulator or browser is involved in that command.

## Output Channels

Use one or more of these output channels:

| Output | Option | Recommended use |
| --- | --- | --- |
| JSONL file | `--jsonl <path>` | Most useful edge default. One canonical JSON event per line. |
| HTTP POST | `--http-url <url>` | Send events to another service in real time. |
| Full stdout JSON | `--stdout true` | Debugging, systemd journal capture, or local pipelines. |
| Compact stdout | `--stdout compact` | Human-readable terminal summary only. Do not build software against it. |

If no output option is provided, the wrapper enables stdout by default. For production, explicitly configure `--jsonl` and/or `--http-url`.

## Recommended Edge Output Patterns

Local file plus HTTP POST:

```bash
./scripts/run-gateway.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --jsonl /var/log/critical-insights/bed_01.jsonl \
  --http-url https://ingest.example.internal/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

JSONL only:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

HTTP POST only:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --http-url https://ingest.example.internal/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

Debug full JSON to terminal:

```bash
./scripts/run-gateway.sh --draeger-tcp 192.168.10.60:4001 \
  --stdout true \
  --output-format canonical
```

## Multi-Device Edge Deployment

Use `--multi` when one edge device connects to more than one bedside device.

Example AIR-021 with Philips on COM1 and Draeger on COM2:

```bash
./scripts/run-gateway.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --jsonl /var/log/critical-insights/bed_01.jsonl \
  --http-url https://ingest.example.internal/device-events \
  --output-format canonical
```

Each event includes `unique_device_identifier`, `gateway_id`, and `bed_id`, so downstream systems can separate devices and locations.

## Single-Driver Edge Deployment

Each current driver has its own edge deployment document:

| Driver | Edge guide |
| --- | --- |
| Philips IntelliVue | `../gateway/devices/philips-intellivue/EDGE.md` |
| Draeger MEDIBUS | `../gateway/devices/draeger-medibus/EDGE.md` |

Future device drivers should add the same `EDGE.md` file under their module.

## Canonical Output Summary

All drivers publish canonical JSON events. Common fields:

| Field | Meaning |
| --- | --- |
| `schema_version` | Current schema version, `1.0`. |
| `topic` | Event topic, such as `Numeric`, `SampleArray`, or `PatientAlert`. |
| `unique_device_identifier` | Stable device key in the gateway stream. |
| `presentation_time` | Gateway publication time in UTC. |
| `gateway_id` | Edge device ID. |
| `bed_id` | Bed/location ID. |
| `vendor` | Normalized vendor. |
| `protocol` | Normalized protocol. |
| `schema_valid` | Validation marker when present. |

Common topics:

| Topic | Meaning |
| --- | --- |
| `DeviceConnectivity` | Transport and driver state. |
| `DeviceIdentity` | Device identity metadata. |
| `Numeric` | One scalar clinical or device observation. |
| `SampleArray` | One decoded waveform/sample-array event. |
| `PatientAlert` | Clinical/patient alarm. |
| `TechnicalAlert` | Technical/device alarm. |
| `DeviceSetting` | Device or ventilator setting. |
| `TextMessage` | Device status/text message. |
| `DeviceTime` | Device clock when available. |

See `canonical-schema.md` for field-level details and `dashboard-integration.md` for consumer implementation examples.

## JSONL Output

With `--jsonl`, each line is one canonical event:

```json
{"schema_version":"1.0","topic":"Numeric","unique_device_identifier":"draeger_vent_01","metric_id":"TidalVolume","vendor_metric_id":"TidalVolumeFrac","instance_id":0,"unit_id":"mL","value":450,"presentation_time":"2026-05-24T21:01:00.437734Z","gateway_id":"air021_01","bed_id":"bed_01","vendor":"draeger","protocol":"medibus","schema_valid":true}
```

JSONL is the easiest output to test on an edge device:

```bash
tail -f /var/log/critical-insights/bed_01.jsonl
```

## HTTP Output

With `--http-url`, the gateway sends one canonical JSON object per HTTP POST request.

Receiver expectations:

- accept `application/json`
- return a 2xx response for accepted events
- handle repeated events idempotently where possible
- log invalid events with enough context for debugging
- use TLS and authentication in production

Add headers with:

```bash
--http-header 'Authorization: Bearer YOUR_TOKEN'
--http-header 'X-Gateway-ID: air021_01'
```

## Device IDs

Default IDs:

```text
Philips: philips_mx800_01
Draeger: draeger_vent_01
Gateway: air021_01
Bed:     bed_01
```

Override gateway and bed IDs:

```bash
--gateway-id air021_02 --bed-id icu_bed_07
```

For single-device mode, override device ID:

```bash
--device-id philips_bed07_monitor
```

For multi-device deployments, keep the defaults unless your integration requires stable site-specific names. If you need per-device custom IDs in multi-device mode, use or extend the runtime's device-specific options rather than reusing one ID for both devices.

## Running Under systemd

Example service for JSONL plus HTTP output:

```ini
[Unit]
Description=Critical Insights Gateway - Bed 01
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=/opt/critical-insights
ExecStart=/opt/critical-insights/scripts/run-gateway.sh --multi --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1 --gateway-id air021_01 --bed-id bed_01 --jsonl /var/log/critical-insights/bed_01.jsonl --http-url https://ingest.example.internal/device-events --output-format canonical
Restart=always
RestartSec=5
User=critical-insights
Group=dialout

[Install]
WantedBy=multi-user.target
```

Operational notes:

- Put the service user in the group that can read/write serial ports, usually `dialout`.
- Create `/var/log/critical-insights` with permissions appropriate for the service user.
- Use stable `/dev/...` names for USB serial adapters through udev rules.
- Keep credentials out of committed files; use environment files or a secrets manager.

## Validation Without Simulator

Driver-only validation should use real hardware or captured protocol data. Simulator success is useful but is not hardware validation.

Useful checks:

```bash
# Confirm serial port exists
ls -l /dev/ttyS0 /dev/ttyS1

# Confirm no other process owns a TCP test endpoint
lsof -nP -iTCP:9100 -sTCP:LISTEN

# Confirm JSONL is receiving events
tail -f /var/log/critical-insights/bed_01.jsonl
```

Real AIR-021 validation script:

```bash
PHILIPS_SERIAL=/dev/ttyS0 DRAEGER_SERIAL=/dev/ttyS1 ./scripts/validate-air021-rs232.sh
```

## Safety And Scope

Critical Insights driver output should be treated as an integration data stream unless the full deployed system has gone through the required medical-device validation.

Do not use the canonical event stream as a standalone real-time life-safety alarm system.
