# Building A Dashboard Or Consumer

This guide explains how an external dashboard, storage service, or analytics process should consume Critical Insights driver output.

The important contract is:

```text
drivers publish canonical JSON events
consumers subscribe to canonical JSON events
consumers do not decode Philips or Draeger protocol bytes
```

The built-in dashboard follows the same rule. It receives decoded canonical events over Server-Sent Events and renders them in the browser.

## Available Output Channels

The gateway can publish the same canonical event stream to multiple sinks.

| Sink | Option or endpoint | Best for |
| --- | --- | --- |
| Terminal JSON | `--stdout true` | Local debugging with complete JSON objects. |
| Terminal summary | `--stdout compact` | Human-readable local monitoring. Do not build software against this. |
| JSONL file | `--jsonl <path>` | Local audit trail, offline replay, storage ingestion. |
| HTTP POST | `--http-url <url>` | Sending each event to another service. |
| Built-in SSE | `GET /events` | Browser dashboards and live UI updates. |
| Latest event | `GET /api/latest` | Health checks and quick manual inspection. |
| Replay ingest | `POST /api/events` | Canonical replay and UI testing. |

All machine consumers should use one of:

- `GET /events`
- `--jsonl <path>`
- `--http-url <url>`

Avoid `--stdout compact` for automation because it is intentionally optimized for people reading a terminal.

## Starting The Gateway For Integration

Local simulator example:

```bash
./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout true \
  --jsonl /tmp/critical-insights-events.jsonl \
  --output-format canonical
```

Production-style HTTP POST example:

```bash
./scripts/run-gateway.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --web-port 8081 \
  --jsonl /var/log/critical-insights/bed01.jsonl \
  --http-url https://example.internal/api/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

The gateway posts one JSON object per event to the configured HTTP URL. Your receiver should accept `application/json` request bodies and return a successful 2xx response.

## Server-Sent Events

The live dashboard stream is available at:

```text
GET http://localhost:8081/events
```

Each SSE message has this shape:

```text
data: {canonical JSON object}

```

Minimal browser example:

```html
<script>
const events = new EventSource("http://localhost:8081/events");

events.onmessage = (message) => {
  const event = JSON.parse(message.data);
  handleCanonicalEvent(event);
};

events.onerror = () => {
  console.warn("gateway event stream disconnected");
};

function handleCanonicalEvent(event) {
  const deviceId = event.unique_device_identifier;
  const topic = event.topic;
  console.log(deviceId, topic, event);
}
</script>
```

Minimal Node.js parsing example using `fetch` and a stream parser:

```js
const response = await fetch("http://localhost:8081/events");
const reader = response.body.getReader();
const decoder = new TextDecoder();
let buffer = "";

while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });

  let boundary;
  while ((boundary = buffer.indexOf("\n\n")) >= 0) {
    const chunk = buffer.slice(0, boundary);
    buffer = buffer.slice(boundary + 2);

    for (const line of chunk.split("\n")) {
      if (!line.startsWith("data: ")) continue;
      const event = JSON.parse(line.slice(6));
      handleCanonicalEvent(event);
    }
  }
}
```

## Event Identity And State Management

A dashboard should maintain state by device and topic.

Use:

- `unique_device_identifier` as the primary device key.
- `topic` as the event type.
- `metric_id` plus `instance_id` as the numeric or waveform key.
- `presentation_time` as the gateway publication time.
- `device_time` when the device provides its own timestamp.

Recommended in-memory shape:

```js
const devices = new Map();

function stateFor(event) {
  const id = event.unique_device_identifier;
  if (!devices.has(id)) {
    devices.set(id, {
      identity: null,
      connectivity: null,
      numerics: new Map(),
      waveforms: new Map(),
      patientAlerts: new Map(),
      technicalAlerts: new Map(),
      settings: new Map(),
      textMessages: []
    });
  }
  return devices.get(id);
}
```

Update rules:

| Topic | Suggested dashboard behavior |
| --- | --- |
| `DeviceConnectivity` | Update connection badge and transport details for that device. |
| `DeviceIdentity` | Update model/manufacturer/serial metadata. |
| `Patient` / `PatientDemographics` | Update patient panel if your workflow displays patient context. |
| `Numeric` | Store by `metric_id + instance_id`; update value, unit, and timestamp. |
| `SampleArray` | Store by `metric_id + instance_id`; append or redraw waveform samples. |
| `PatientAlert` | Store by `identifier`; show active clinical alerts by priority. |
| `TechnicalAlert` | Store by `identifier`; show active device/sensor/communication alerts. |
| `DeviceSetting` | Store by setting identifier or metric field. |
| `TextMessage` | Keep recent messages per device with timestamps. |
| `DeviceTime` | Display or use for drift diagnostics. |

Do not assume events arrive in a fixed order. A waveform or numeric can arrive before identity metadata. A device can be connected but not yet have clinical values.

## Numeric Events

A `Numeric` event carries one scalar observation.

Important fields:

| Field | Meaning |
| --- | --- |
| `metric_id` | Canonical or normalized metric identifier used by consumers. |
| `vendor_metric_id` | Original protocol/vendor metric identifier. |
| `instance_id` | Distinguishes repeated instances of the same metric. |
| `unit_id` | Unit identifier or symbol. |
| `value` | Numeric value, or `null` when the device reports no valid value. |

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
  "presentation_time": "2026-05-24T21:01:00.437734Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "draeger",
  "protocol": "medibus",
  "schema_valid": true
}
```

Dashboard advice:

- Do not hard-code only one vendor's metric names.
- Prefer a display mapping table that maps known `metric_id` values to labels.
- Keep unknown metrics visible in a generic table during development.
- Preserve `vendor_metric_id` in logs so protocol mapping gaps can be debugged.

## Waveform Events

A `SampleArray` event carries decoded waveform samples. The driver has already decoded the protocol frame into `values`.

Important fields:

| Field | Meaning |
| --- | --- |
| `metric_id` | Waveform identifier. |
| `vendor_metric_id` | Original protocol waveform identifier. |
| `frequency` | Samples per second when known. |
| `sample_period_us` | Microseconds between samples when known. |
| `values` | Decoded numeric sample array. |
| `raw_sample_bytes` | Optional raw sample bytes retained for diagnostics. |
| `array_size` | Number of samples represented by the event when available. |

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
  "presentation_time": "2026-05-24T21:11:46.552516Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "philips",
  "protocol": "philips_mib_rs232",
  "schema_valid": true
}
```

Dashboard advice:

- Render `values`; do not parse Philips or Draeger protocol bytes in the browser.
- Use `frequency` or `sample_period_us` to pace drawing when present.
- Keep a ring buffer per device and waveform metric.
- Treat missing `frequency` as unknown; do not invent a clinical sample rate.
- If both `values` and `raw_sample_bytes` exist, `values` is the consumer-facing data.

## Alerts

`PatientAlert` and `TechnicalAlert` events carry alarm state.

Important fields:

| Field | Meaning |
| --- | --- |
| `identifier` | Stable alert code or identifier. |
| `text` | Human-readable alarm text when mapped, otherwise the raw identifier. |
| `priority` | Priority or severity when available. |
| `state` | Active/inactive/latched state when available. |
| `source` | Optional subsystem/source metadata. |

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

Alarm safety:

- Do not use this dashboard stream as a real-time life-safety alarm system.
- Treat it as integration/visibility data unless the whole deployed system has gone through the required medical-device validation.
- Preserve raw identifiers for traceability.

## JSONL Consumption

When using `--jsonl`, every line is one canonical JSON object:

```bash
tail -f /tmp/critical-insights-events.jsonl
```

Example parser:

```js
import fs from "node:fs";
import readline from "node:readline";

const lines = readline.createInterface({
  input: fs.createReadStream("/tmp/critical-insights-events.jsonl")
});

for await (const line of lines) {
  if (!line.trim()) continue;
  const event = JSON.parse(line);
  handleCanonicalEvent(event);
}
```

JSONL is useful for:

- replaying a session
- auditing driver output
- debugging schema changes
- feeding batch pipelines

## HTTP POST Receiver

If you use `--http-url`, implement a receiver that accepts one event per request:

```js
import express from "express";

const app = express();
app.use(express.json({ limit: "1mb" }));

app.post("/api/device-events", (req, res) => {
  const event = req.body;
  if (!event || event.schema_version !== "1.0" || !event.topic) {
    res.status(400).json({ error: "invalid_canonical_event" });
    return;
  }

  handleCanonicalEvent(event);
  res.status(202).json({ accepted: true });
});

app.listen(3000);
```

Operational advice:

- Make the receiver idempotent where possible.
- Log rejected events with enough context to diagnose schema drift.
- Store original JSON for audit/debug while your product is still evolving.
- Use TLS and authentication for production HTTP endpoints.

## Schema Validation

JSON Schema files live under:

```text
gateway/schemas/
```

The schema documentation lives in:

```text
docs/canonical-schema.md
```

For strict consumers:

1. Validate `schema_version`.
2. Switch on `topic`.
3. Validate required fields for that topic.
4. Accept additive optional fields.
5. Preserve unknown fields if forwarding or storing events.

The schemas allow additional fields because protocol-specific metadata can be useful for diagnostics. Do not reject an event only because it includes an extra field.

## Handling Multiple Devices

A single gateway process can publish Philips and Draeger events together. Keep device state separate:

```text
devices[unique_device_identifier].numerics[metric_id + instance_id]
devices[unique_device_identifier].waveforms[metric_id + instance_id]
devices[unique_device_identifier].alerts[identifier]
```

Do not infer the active device from event order. Draeger can publish at a higher rate than Philips, so a global "latest value" view will mix devices unless you key by `unique_device_identifier`.

## Troubleshooting Consumer Integrations

No events in `/events`:

- Confirm the gateway was started with `--web-port`.
- Confirm at least one driver or replay source is running.
- Check `curl http://localhost:<port>/api/latest`.
- Check the gateway terminal for connection or parser errors.

Only one device appears:

- Confirm both simulator/device processes are running.
- Confirm the gateway command includes both device flags.
- For RS232, confirm simulator uses `*-device` and gateway uses `*-gateway`.
- Remember Draeger may publish more frequently than Philips; key by device, not global latest event.

Waveforms do not draw:

- Confirm you are receiving `SampleArray` events.
- Confirm the event has a non-empty `values` array.
- Confirm your dashboard is not filtering out unknown `metric_id` values.
- Use canonical replay to isolate UI drawing from protocol driver behavior.

Values look wrong:

- Compare the raw event from `/events` or JSONL to your display mapping.
- Check `unit_id`.
- Check whether the metric is from Philips monitor data or Draeger ventilator data.
- Preserve and inspect `vendor_metric_id` when mapping is unclear.
