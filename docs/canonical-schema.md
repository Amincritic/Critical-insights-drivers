# Canonical Event Schema

Canonical events are the product-facing contract between device drivers, simulator fixtures, the web dashboard, API consumers, files, and downstream services.

The gateway decodes vendor protocols into canonical events. Downstream systems should consume these canonical fields instead of Philips IntelliVue or Draeger MEDIBUS protocol structures.

Each event is one complete JSON object. JSONL files contain one canonical JSON object per line.

## Versioning

Current schema version:

```text
1.0
```

Compatibility rules:

- Add optional fields when possible.
- Do not rename existing fields without a migration plan.
- Do not change the meaning of an existing `topic`.
- Keep committed fixtures synthetic and valid.
- If a breaking change is required, bump `schema_version` and update schemas, docs, fixtures, tests, and consumers together.

Runtime output is canonical only. The gateway accepts `--output-format canonical` for compatibility with scripts, but `legacy` and `both` are not supported runtime formats.

## Common Fields

Most events include these fields:

| Field | Type | Required in schemas | Meaning |
| --- | --- | --- | --- |
| `schema_version` | string | yes | Canonical schema version. Current value: `1.0`. |
| `topic` | string | yes | Event topic such as `Numeric`, `SampleArray`, or `DeviceConnectivity`. |
| `unique_device_identifier` | string | yes | Stable device key inside the gateway stream. |
| `presentation_time` | string | yes | Gateway publication time as ISO-8601 UTC. |
| `gateway_id` | string | topic-dependent | Edge gateway identifier, usually `air021_01` by default. |
| `bed_id` | string | topic-dependent | Bed or care-location identifier, usually `bed_01` by default. |
| `vendor` | string | topic-dependent | Normalized vendor, for example `philips` or `draeger`. |
| `protocol` | string | topic-dependent | Normalized protocol, for example `intellivue_udp`, `philips_mib_rs232`, or `medibus`. |
| `schema_valid` | boolean | no | Marker added when the publisher path validates or marks the event. |

Consumer rules:

- Use `unique_device_identifier` as the primary device key.
- Use `topic` to select parsing/rendering behavior.
- Use `presentation_time` for gateway event ordering.
- Use `device_time` only when the device/protocol actually provides it.
- Accept additional fields. The JSON Schemas intentionally allow `additionalProperties`.

## Supported Topics

| Topic | Purpose |
| --- | --- |
| `DeviceConnectivity` | Transport and driver state changes. |
| `DeviceIdentity` | Device manufacturer/model/serial/build metadata. |
| `Patient` | Patient context when available. |
| `PatientDemographics` | Patient demographic attributes when available. |
| `Numeric` | One scalar observation. |
| `SampleArray` | One decoded waveform/sample-array observation. |
| `PatientAlert` | Clinical or patient alarm state. |
| `TechnicalAlert` | Device, sensor, lead, communication, or equipment alarm state. |
| `DeviceSetting` | One decoded device setting value. |
| `TextMessage` | One decoded device text/status message. |
| `TrendStatus` | Available trend parameters and sample counts. |
| `RealtimeConfig` | Available realtime waveform streams. |
| `DeviceTime` | Device-reported clock value. |

## DeviceConnectivity

Use `DeviceConnectivity` when the gateway, transport, or driver state changes.

Required by schema:

- `schema_version`
- `topic`
- `unique_device_identifier`
- `state`
- `type`
- `presentation_time`

Common fields:

| Field | Meaning |
| --- | --- |
| `state` | `Connecting`, `Connected`, `Disconnected`, `Error`, or another driver-specific state. |
| `type` | Transport category such as `Serial`, `Network`, or `Replay`. |
| `info` | Human-readable detail. |
| `valid_targets` | Optional list of expected connection targets. |
| `comPort` | Optional serial port name for serial devices. |

Example:

```json
{
  "schema_version": "1.0",
  "topic": "DeviceConnectivity",
  "unique_device_identifier": "philips_mx800_01",
  "presentation_time": "2026-05-24T21:00:00Z",
  "state": "Connected",
  "type": "Serial",
  "info": "Philips IntelliVue adapter initialized",
  "valid_targets": [],
  "comPort": "/dev/ttyS0",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

Connectivity events are important because a device may be connected before the first clinical value arrives.

## DeviceIdentity

Use `DeviceIdentity` to publish metadata that is stable over a connection.

Common fields:

- `manufacturer`
- `model`
- `serial_number`
- `build`
- `operating_system`
- `software_revision`
- `hardware_revision`
- `firmware_revision`

Missing metadata should be omitted or set to `null`. Do not invent a serial number, firmware version, or model.

Example:

```json
{
  "schema_version": "1.0",
  "topic": "DeviceIdentity",
  "unique_device_identifier": "draeger_vent_01",
  "presentation_time": "2026-05-24T21:01:01.442516Z",
  "manufacturer": "Draeger",
  "model": "Evita",
  "serial_number": "8210",
  "build": "01.00:03.00",
  "operating_system": null,
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "schema_valid": true
}
```

## Numeric

`Numeric` events carry one decoded scalar value.

Required by schema:

- `schema_version`
- `topic`
- `unique_device_identifier`
- `metric_id`
- `vendor_metric_id`
- `instance_id`
- `unit_id`
- `value`
- `presentation_time`

Fields:

| Field | Meaning |
| --- | --- |
| `metric_id` | Canonical or normalized metric identifier used by consumers. |
| `vendor_metric_id` | Original vendor/protocol metric identifier. |
| `instance_id` | Metric instance when the same metric can appear multiple times. |
| `unit_id` | Unit identifier or symbol. |
| `value` | Numeric value, or `null` when no valid value is available. |
| `device_time` | Device-side timestamp when available. |

Example:

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

Consumer guidance:

- Key values by `unique_device_identifier`, `metric_id`, and `instance_id`.
- Display `metric_id` through a mapping table.
- Keep `vendor_metric_id` for debugging and traceability.
- Do not assume all devices use the same unit string for similar clinical concepts.

## SampleArray

`SampleArray` events carry decoded waveform samples. The driver has already decoded the vendor protocol into `values`.

Required by schema:

- `schema_version`
- `topic`
- `unique_device_identifier`
- `metric_id`
- `vendor_metric_id`
- `instance_id`
- `unit_id`
- `frequency`
- `values`
- `presentation_time`

Fields:

| Field | Meaning |
| --- | --- |
| `metric_id` | Canonical or normalized waveform identifier. |
| `vendor_metric_id` | Original vendor/protocol waveform identifier. |
| `instance_id` | Waveform instance. |
| `unit_id` | Unit identifier or symbol. |
| `frequency` | Sample frequency in Hz when available. |
| `values` | Decoded sample values for consumers. |
| `device_time` | Device-side timestamp when available. |

Optional waveform metadata:

- `sample_period_us`
- `sample_size_bits`
- `significant_bits`
- `array_size`
- `physiological_range`
- `scale_range`
- `calibration`
- `fixed_values`
- `raw_sample_bytes`

Example:

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
  "values": [129, 130, 126, 128, 126],
  "sample_period_us": 2000,
  "sample_size_bits": 8,
  "significant_bits": 8,
  "array_size": 5,
  "device_time": null,
  "presentation_time": "2026-05-24T21:11:46.552516Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

Consumer guidance:

- Render `values`; do not decode protocol bytes in the dashboard.
- Use `frequency` or `sample_period_us` when available.
- Keep a ring buffer per device and waveform key.
- Treat missing timing metadata as unknown.
- If `raw_sample_bytes` exists, it is diagnostic; `values` remains the display data.

## PatientAlert And TechnicalAlert

`PatientAlert` and `TechnicalAlert` events carry decoded alarm state.

Required by schema:

- `schema_version`
- `topic`
- `unique_device_identifier`
- `identifier`
- `text`
- `presentation_time`

Fields:

| Field | Meaning |
| --- | --- |
| `identifier` | Stable alert identifier or raw vendor code. |
| `text` | Human-readable alert text when mapped, otherwise the raw identifier. |
| `priority` | Priority or severity when available. |
| `state` | Active/inactive/latched state when available. |
| `source` | Device subsystem or protocol source when available. |

Example:

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

Use `PatientAlert` for clinical/patient alarms. Use `TechnicalAlert` for device, sensor, lead-off, communication, or equipment alarms.

Alarm safety:

- Do not use this event stream as a standalone real-time life-safety alarm system.
- Preserve raw identifiers for traceability.
- A mapped human-readable text should come from protocol documentation, a vendor map, or verified captured traffic.

## Patient And Demographics

Use patient topics only when the protocol provides patient context or when replay fixtures intentionally model UI behavior.

Common fields:

- `patient_id`
- `patient_name`
- `given_name`
- `family_name`
- `sex`
- `date_of_birth`
- `height`
- `weight`

Do not include protected health information in committed fixtures unless the values are synthetic.

## DeviceSetting

`DeviceSetting` represents a decoded device setting, commonly from a ventilator.

Common fields:

- `setting_id`
- `vendor_setting_id`
- `value`
- `unit_id`
- `instance_id`
- `device_time`

Consumers should treat settings separately from patient observations. A setting is configured device state; a numeric is a measured or calculated observation.

## TextMessage

`TextMessage` represents decoded status text or device messages.

Common fields:

- `identifier`
- `text`
- `priority`
- `state`
- `source`

Keep recent messages per device and timestamp. Do not assume text messages are alarms unless the topic is `PatientAlert` or `TechnicalAlert`.

## DeviceTime

`DeviceTime` carries the device-reported clock when available.

Common fields:

- `device_time`
- `presentation_time`

Use it for diagnostics or timestamp drift checks. Do not replace `presentation_time` with `device_time` unless your downstream system explicitly wants device-clock ordering.

## JSON Schema Files

JSON Schema files live under:

```text
gateway/schemas/
```

Current schema files:

```text
device_connectivity.schema.json
device_identity.schema.json
numeric.schema.json
patient.schema.json
patient_alert.schema.json
patient_demographics.schema.json
sample_array.schema.json
technical_alert.schema.json
```

When changing this document, update the matching schema file. When changing a schema file, update this document and affected fixtures.

## Adding A Topic

Add a new topic only when existing topics cannot represent the data safely.

Required steps:

1. Define the topic in this document.
2. Add a JSON Schema file under `gateway/schemas/`.
3. Add publisher validation support if needed.
4. Add dashboard rendering if the UI should show it.
5. Add canonical replay fixtures.
6. Add smoke test coverage.
7. Update `adding-devices.md` if the topic affects driver authoring.

## Consumer Checklist

External dashboards and services should:

- parse complete JSON objects
- key all state by `unique_device_identifier`
- switch behavior by `topic`
- keep unknown fields
- treat missing optional fields as unknown
- preserve `vendor_metric_id` and raw identifiers for debugging
- avoid relying on compact stdout
- avoid decoding vendor protocol bytes in frontend code

See `dashboard-integration.md` for a practical consumer implementation guide.
