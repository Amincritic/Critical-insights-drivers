# Gateway Architecture

The gateway is a Java 17 runtime that connects medical-device protocol drivers to canonical event publishers.

The high-level flow is:

```text
Philips LAN/UDP or MIB/RS232  -> Philips IntelliVue driver -> canonical events
Draeger TCP or RS232          -> Draeger MEDIBUS driver     -> canonical events
canonical replay              -> gateway web/API sinks      -> dashboard
canonical events              -> stdout / JSONL / HTTP / web dashboard
```

## Module Layout

```text
gateway/
  devices/common/
    shared IO, serial, publisher, schema, and canonical helper code

  devices/philips-intellivue/
    Philips IntelliVue LAN/UDP and MIB/RS232 driver

  devices/draeger-medibus/
    Draeger MEDIBUS TCP and RS232 driver

  runtime/
    combined multi-device gateway runtime

  schemas/
    JSON Schema files for canonical topics

  interop-lab/purejavacomm/
    serial provider support

  test-tools/
    gateway-only fake devices and port detector
```

The active simulator is outside this module:

```text
../simulator/
```

Do not add new simulator code under `gateway/test-tools`. That directory is only for small gateway helper programs that are still used by gateway scripts.

## Device Adapters

### Philips IntelliVue

Supported transports:

- LAN/UDP Data Export.
- MIB/RS232 fixed baudrate Data Export.

The MIB/RS232 path uses `RS232Adapter` to unwrap serial frames, validate FCS/CRC, handle byte escaping, and feed the existing IntelliVue parser.

Realtime Philips numerics, sample arrays, and alarm lists are requested with Extended Poll so the driver receives poll sequence numbers and relative timestamps. The driver publishes those fields with each decoded event and marks sequence/timing gaps when detected. Lower-frequency patient demographics use Single Poll.

The headless Philips path publishes:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`

Philips-specific implementation notes live in:

```text
devices/philips-intellivue/README.md
```

### Draeger MEDIBUS

Supported transports:

- TCP for simulator/development.
- RS232 serial for real ventilators.

The driver performs the ICC handshake, validates incoming MEDIBUS checksums, keeps the connection alive, polls profile-specific measured data/alarm/setting/text/waveform commands, decodes MEDIBUS responses, and publishes canonical events. Select the device family with `--draeger-profile v500|intensive-care|evita|savina|fabius`.

The headless Draeger path publishes:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`
- `DeviceSetting`
- `TextMessage`

Draeger-specific implementation notes live in:

```text
devices/draeger-medibus/README.md
```

## Publishing Pipeline

Drivers publish decoded events through the shared publisher stack:

```text
driver
  -> CanonicalEvents helpers
  -> canonical output filter
  -> QueuedJsonPublisher
  -> MultiJsonPublisher
  -> sinks
```

Supported sinks:

- stdout JSON
- compact stdout
- JSONL file
- HTTP POST
- built-in web dashboard

Runtime output is canonical JSON only. `--output-format canonical` is accepted as a compatibility flag for existing scripts, but `legacy` and `both` are not supported.

```bash
--output-format canonical
```

## Web Dashboard And API

The web dashboard is served by `WebDashboardPublisher` on the configured web port.

Endpoints:

```text
GET /             dashboard HTML/JS
GET /events       server-sent event stream
GET /api/latest   most recent canonical event
POST /api/events  accept one canonical event for replay/UI tests
```

The dashboard receives decoded canonical events. It does not decode Philips or Draeger protocol frames in browser code.

External dashboards should consume the same canonical stream. See:

```text
../docs/dashboard-integration.md
```

## Runtime Entrypoints

Use the top-level wrapper for normal work:

```bash
cd ..
./scripts/run-gateway.sh --help
```

The wrapper delegates to `gateway/run.sh`, which then launches one of:

```text
devices/draeger-medibus/build/install/draeger/bin/draeger
devices/philips-intellivue/build/install/philips/bin/philips
devices/philips-intellivue/build/install/philips/bin/philips-serial
runtime/build/install/gateway-runtime/bin/gateway-runtime
```

The combined runtime is used for `--multi` and `--web-only`.

## Adding A Driver To The Runtime

When adding a new driver:

1. Create a module under `devices/<vendor-protocol>/`.
2. Add it to `settings.gradle`.
3. Implement driver parsing and canonical conversion.
4. Add a standalone launcher if useful.
5. Add the driver to `runtime/` if it should run in the combined multi-device process.
6. Add top-level script support in `run.sh` and `../scripts/run-gateway.sh` only after the runtime path is stable.
7. Add simulator support under `../simulator/`.
8. Add docs and tests.

See:

```text
../docs/adding-devices.md
```

## Canonical Schema

The canonical event contract is documented in:

```text
../docs/canonical-schema.md
```

Consumer integration guidance is documented in:

```text
../docs/dashboard-integration.md
```

JSON Schema files live in:

```text
schemas/
```

Do not create driver-local product schemas unless they are internal parser structures. Product-facing event changes should be made in the canonical schema.

## Error Handling Expectations

Production drivers should:

- Publish connectivity state changes.
- Log transport errors with enough context to identify the device and transport.
- Reconnect or fail predictably after disconnects.
- Keep serial settings explicit.
- Avoid silently dropping malformed frames unless the protocol requires it.
- Preserve raw identifiers when a human-readable metric or alarm map is incomplete.

## Verification

Build:

```bash
./gradlew :runtime:installDist
```

From the top-level directory:

```bash
./scripts/test-canonical-replay.sh
./scripts/test-rs232-simulators.sh
```

Real hardware validation:

```bash
PHILIPS_SERIAL=/dev/ttyS0 DRAEGER_SERIAL=/dev/ttyS1 ./scripts/validate-air021-rs232.sh
```
