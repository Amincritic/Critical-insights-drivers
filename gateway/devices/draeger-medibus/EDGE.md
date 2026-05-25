# Draeger MEDIBUS Edge Driver Deployment

This guide is for running only the Draeger MEDIBUS driver on an edge device. It does not require the simulator or the built-in web dashboard.

Use this guide when the edge device connects to a real Draeger ventilator, serial-to-Ethernet adapter, or other MEDIBUS endpoint and publishes decoded canonical JSON to a file, stdout, or an HTTP endpoint.

## Supported Draeger Modes

| Mode | Transport | Typical use |
| --- | --- | --- |
| MEDIBUS RS232 | Serial RS232 | AIR-021 or another edge gateway connected directly to a ventilator. |
| MEDIBUS TCP | TCP | Serial-to-Ethernet adapter, lab bridge, or development endpoint. |

Supported output topics:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`
- `DeviceSetting`
- `TextMessage`
- `DeviceTime`

The driver decodes MEDIBUS traffic before publishing. Consumers receive canonical JSON; they do not need to know MEDIBUS framing or checksums.

## Build

From the top-level workspace:

```bash
cd critical-insights/gateway
./gradlew :devices:draeger-medibus:installDist :runtime:installDist
```

For normal deployment, use the top-level wrapper:

```bash
cd critical-insights
./scripts/run-gateway.sh --help
```

## Draeger RS232 Driver Only

JSONL output:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

HTTP output:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id draeger_vent_01 \
  --http-url https://ingest.example.internal/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

Debug full JSON to terminal:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --stdout true \
  --output-format canonical
```

Do not pass `--web-port` if you do not want the web dashboard/API.

## Draeger TCP Driver Only

JSONL output:

```bash
./scripts/run-gateway.sh --draeger-tcp 192.168.10.60:4001 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

Use TCP when the ventilator is exposed through a serial-to-Ethernet adapter or a lab bridge. The MEDIBUS protocol decode path is the same after bytes enter the driver.

## Device Profiles

Use `--draeger-profile` to select the command profile:

| Profile | Use case |
| --- | --- |
| `v500` | Default profile for V500-style testing; requests measured data CP1/CP2, alarms, settings, text messages, and realtime waveforms. |
| `intensive-care` | Intensive-care MEDIBUS manual family; avoids measured-data CP2 but keeps alarm CP2. |
| `evita` | Conservative Evita profile; measured data CP1 and alarm CP1. |
| `savina` | Savina profile; measured data CP1 and alarm CP1/CP2. |
| `fabius` | Fabius GS/Tiro profile; measured data CP1 and alarm CP1/CP2. |

If you are targeting V500, start with:

```bash
--draeger-profile v500
```

## Serial Settings

Default Draeger serial settings:

```text
Baud:        19200
Data bits:   8
Parity:      even
Stop bits:   1
Flow control: none at the serial layer
```

Override when your ventilator configuration requires it:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-baud 38400 \
  --serial-parity none \
  --draeger-profile fabius \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

## Hardware Notes

Draeger RS232 usually requires a null-modem/crossover cable.

See:

```text
../../HARDWARE.md
```

Key points:

- Evita-style Channel A defaults to 19200 baud with even parity.
- Fabius/Tiro settings may differ and can use no parity.
- MEDIBUS requires ICC handshake and regular polling/keepalive.
- Long idle gaps can terminate the link.

## Canonical Output Examples

Numeric:

```json
{
  "schema_version": "1.0",
  "topic": "Numeric",
  "unique_device_identifier": "draeger_vent_01",
  "metric_id": "TidalVolume",
  "vendor_metric_id": "TidalVolumeFrac",
  "instance_id": 0,
  "unit_id": "mL",
  "value": 450,
  "device_time": null,
  "presentation_time": "2026-05-24T21:01:00.437734Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "schema_valid": true
}
```

Waveform:

```json
{
  "schema_version": "1.0",
  "topic": "SampleArray",
  "unique_device_identifier": "draeger_vent_01",
  "metric_id": "AirwayPressure",
  "vendor_metric_id": "AirwayPressure",
  "instance_id": 0,
  "unit_id": "cmH2O",
  "frequency": 50,
  "values": [5, 6, 8, 12, 18, 22],
  "presentation_time": "2026-05-24T21:01:00.500000Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "schema_valid": true
}
```

Device setting:

```json
{
  "schema_version": "1.0",
  "topic": "DeviceSetting",
  "unique_device_identifier": "draeger_vent_01",
  "setting_id": "PEEP",
  "vendor_setting_id": "PEEPBreathingPressure",
  "value": 5,
  "unit_id": "cmH2O",
  "presentation_time": "2026-05-24T21:01:01Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "schema_valid": true
}
```

See `../../../docs/canonical-schema.md` for the full event contract.

## What Consumers Should Expect

Consumers should:

- key device state by `unique_device_identifier`
- key numeric values by `metric_id` and `instance_id`
- key waveform streams by `metric_id` and `instance_id`
- treat `DeviceSetting` separately from measured `Numeric` values
- render waveform `values`; do not parse MEDIBUS bytes
- preserve `vendor_metric_id` and raw text/setting identifiers for traceability
- accept optional extra fields

## Operational Checks

Confirm JSONL output:

```bash
tail -f /var/log/critical-insights/draeger_vent_01.jsonl
```

Confirm a serial port exists:

```bash
ls -l /dev/ttyS1
```

If no data appears:

- Confirm the ventilator MEDIBUS port is enabled.
- Confirm the serial cable is null-modem/crossover where required.
- Confirm baud and parity match the ventilator configuration.
- Confirm the selected `--draeger-profile` matches the device family.
- Confirm no other process owns the serial port.
- Run with `--stdout true` temporarily to inspect complete canonical events.

## Related Docs

- `README.md`: module-level driver overview.
- `../../../docs/edge-driver-deployment.md`: general edge driver deployment.
- `../../../docs/canonical-schema.md`: output schema.
- `../../../docs/dashboard-integration.md`: consuming output in an external system.
- `../../HARDWARE.md`: wiring and ventilator setup.
