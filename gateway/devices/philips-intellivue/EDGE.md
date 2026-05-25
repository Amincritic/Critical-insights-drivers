# Philips IntelliVue Edge Driver Deployment

This guide is for running only the Philips IntelliVue driver on an edge device. It does not require the simulator or the built-in web dashboard.

Use this guide when the edge device connects to a real Philips monitor and publishes decoded canonical JSON to a file, stdout, or an HTTP endpoint.

## Supported Philips Modes

| Mode | Transport | Typical use |
| --- | --- | --- |
| LAN/UDP Data Export | Network UDP | Development LANs or Philips LAN Data Export deployments. |
| MIB/RS232 fixed baudrate | Serial RS232 | AIR-021 or another edge gateway connected to the Philips MIB/RS232 port. |

Supported output topics:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`

The driver decodes Philips protocol traffic before publishing. Consumers receive canonical JSON; they do not need to know IntelliVue packet framing.

## Build

From the top-level workspace:

```bash
cd critical-insights/gateway
./gradlew :devices:philips-intellivue:installDist :runtime:installDist
```

For normal deployment, use the top-level wrapper:

```bash
cd critical-insights
./scripts/run-gateway.sh --help
```

## Philips MIB/RS232 Driver Only

JSONL output:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --philips-baud 115200 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id philips_mx800_01 \
  --jsonl /var/log/critical-insights/philips_mx800_01.jsonl \
  --output-format canonical
```

HTTP output:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --philips-baud 115200 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id philips_mx800_01 \
  --http-url https://ingest.example.internal/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

Debug full JSON to terminal:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --stdout true \
  --output-format canonical
```

Do not pass `--web-port` if you do not want the web dashboard/API.

## Philips LAN/UDP Driver Only

JSONL output:

```bash
./scripts/run-gateway.sh --philips-host 192.168.10.50 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id philips_mx800_01 \
  --jsonl /var/log/critical-insights/philips_mx800_01.jsonl \
  --output-format canonical
```

Replace `192.168.10.50` with the address used by your Philips LAN Data Export configuration.

## Serial Settings

Philips MIB/RS232 fixed baudrate settings:

```text
Baud:        115200 default, 19200 optional
Data bits:   8
Parity:      none
Stop bits:   1
Flow control: none
```

Use `--philips-baud 19200` only when the monitor is configured for 19200 fixed-baudrate operation.

## Hardware Notes

Philips MIB/RS232 uses an RJ-45 connector, not DB-9. AIR-021 deployments normally require a custom RJ-45-to-DB-9 cable.

See:

```text
../../HARDWARE.md
```

Key points:

- Philips MIB/RS232 is straight-through by signal mapping.
- Do not use the Draeger null-modem cable for Philips.
- Use the monitor fixed baudrate Data Export mode.
- Confirm the monitor MIB/RS232 port is set to Data Out.

## Canonical Output Examples

Numeric:

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
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

Waveform:

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

Alert:

```json
{
  "schema_version": "1.0",
  "topic": "PatientAlert",
  "unique_device_identifier": "philips_mx800_01",
  "identifier": "1026",
  "text": "1026",
  "priority": "high",
  "state": "active",
  "presentation_time": "2026-05-24T21:11:46.555149Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

See `../../../docs/canonical-schema.md` for the full event contract.

## What Consumers Should Expect

Consumers should:

- key device state by `unique_device_identifier`
- key numeric values by `metric_id` and `instance_id`
- key waveform streams by `metric_id` and `instance_id`
- render waveform `values`; do not parse Philips bytes
- preserve `vendor_metric_id` for traceability
- accept optional extra fields

## Operational Checks

Confirm JSONL output:

```bash
tail -f /var/log/critical-insights/philips_mx800_01.jsonl
```

Confirm a serial port exists:

```bash
ls -l /dev/ttyS0
```

If no data appears:

- Confirm the monitor is configured for Data Export.
- Confirm the serial cable pinout.
- Confirm baud rate matches the monitor setting.
- Confirm no other process owns the serial port.
- Run with `--stdout true` temporarily to inspect complete canonical events.

## Related Docs

- `README.md`: module-level driver overview.
- `../../../docs/edge-driver-deployment.md`: general edge driver deployment.
- `../../../docs/canonical-schema.md`: output schema.
- `../../../docs/dashboard-integration.md`: consuming output in an external system.
- `../../HARDWARE.md`: wiring and monitor setup.
