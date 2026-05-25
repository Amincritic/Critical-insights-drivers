# Adding Devices And Protocols

This guide describes the long-term pattern for adding device support to Critical Insights.

The core rule is simple: device-specific work belongs in a device driver and a matching simulator. Product-facing output should remain canonical JSON.

## Where New Code Should Go

Use this structure for a new production driver:

```text
gateway/devices/<vendor-protocol>/
  build.gradle
  README.md
  src/main/java/...
  src/main/resources/...
  src/test/java/...
```

Examples already in the repository:

```text
gateway/devices/philips-intellivue/
gateway/devices/draeger-medibus/
```

Use this structure for simulator support:

```text
simulator/src/main/java/org/mdpnp/simulator/<vendor-or-protocol>/
simulator/config/<device-or-scenario>.properties
simulator/fixtures/canonical/<scenario>.jsonl
```

If the simulator is a small wrapper around an existing Java main class, it can be registered through a `SimulatorDeviceFactory`. If it becomes substantial, give it its own package instead of putting more logic into the runtime manager.

## Naming

Use protocol names for driver modules when the protocol can apply to multiple models:

- `philips-intellivue`
- `draeger-medibus`

Use device model names inside configuration, metadata, fixtures, and user-facing labels:

- `model=MX800`
- `model=Evita V500`
- `unique_device_identifier=philips_mx800_01`
- `unique_device_identifier=draeger_v500_01`

This avoids tying the code structure to one physical model when the same protocol supports a family of devices.

## Driver Responsibilities

A production driver should own:

- Transport connection: TCP, UDP, serial, or another link.
- Device protocol handshake.
- Framing, escaping, checksums, and retry behavior.
- Polling or subscription lifecycle.
- Device-specific decode maps.
- Conversion from decoded protocol objects to canonical events.
- Connectivity state events.
- Tests using real or captured protocol frames.

A production driver should not own:

- Web dashboard rendering.
- Storage-specific JSON shape.
- UI-specific metric labels.
- Simulator runtime lifecycle.
- Product-wide schema definitions beyond emitting canonical fields.

## Canonical Output Responsibilities

Every driver should emit canonical topics where possible:

- `DeviceIdentity`
- `DeviceConnectivity`
- `Patient`
- `PatientDemographics`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`

Do not add protocol-specific top-level event formats for new devices. Put protocol-specific metadata in clearly named optional fields when needed, and keep the common topic fields stable.

For example:

```json
{
  "schema_version": "1.0",
  "topic": "Numeric",
  "unique_device_identifier": "example_device_01",
  "presentation_time": "2026-05-24T01:00:00Z",
  "gateway_id": "air021_01",
  "bed_id": "bed_01",
  "vendor": "example",
  "protocol": "example_protocol",
  "metric_id": "MDC_PULS_OXIM_SAT_O2",
  "vendor_metric_id": "SPO2",
  "value": 98,
  "unit_id": "%",
  "schema_valid": true
}
```

## Simulator Responsibilities

For each new device, build two levels of simulation when practical:

1. Protocol simulator.
   This emits real protocol frames and is used to verify the production driver.

2. Canonical fixtures.
   These emit deterministic canonical events and are used to verify the web UI, API, storage, and alert rendering.

The protocol simulator is the more important end-to-end test. Canonical replay is faster and more deterministic, but it cannot prove that the driver decodes the vendor protocol correctly.

## Adding A New Driver

1. Create `gateway/devices/<vendor-protocol>/`.
2. Add the module to `gateway/settings.gradle`.
3. Add a `build.gradle` matching the existing driver style.
4. Implement the protocol transport and parser.
5. Add a headless launcher or adapter that accepts:
   - `--gateway-id`
   - `--bed-id`
   - `--device-id`
   - transport-specific options
   - publisher options such as stdout, JSONL, HTTP, and web port if it is a standalone app
6. Emit canonical events through the shared publisher/event helpers in `devices/common`.
7. Add schema validation for emitted events where applicable.
8. Add unit tests for parsing, checksum/framing, and canonical conversion.
9. Add captured-frame tests if hardware or vendor sample traffic is available.
10. Update `scripts/run-gateway.sh` only when the new device should be exposed through the top-level launcher.
11. Update docs and smoke tests.

## Adding A New Simulator

1. Implement `SimulatorDeviceFactory`.
2. Implement `SimulatedDevice` for lifecycle methods.
3. Register the factory in `SimulatorRuntimeApp`.
4. Add a config block using the `device.<id>.*` format.
5. Add GUI support if the device should be controllable from `run-simulator-gui.sh`.
6. Add canonical fixtures for UI/schema scenarios.
7. Add a smoke test that proves data reaches the gateway or dashboard.

Example config shape:

```properties
device.example_1.type=example-protocol
device.example_1.vendor=ExampleVendor
device.example_1.model=ExampleModel
device.example_1.transport.type=tcp
device.example_1.transport.port=9200
```

Transport keys are device-specific, but `type`, `vendor`, `model`, and `transport.type` should stay consistent across devices.

## Updating The Web Dashboard

The web dashboard should stay canonical-topic driven:

- Add UI rendering only when a canonical topic or field is missing from the dashboard.
- Avoid adding vendor-specific rendering unless the data truly has no canonical equivalent.
- Keep device-specific labels as display metadata, not as schema requirements.

If a new driver emits `Numeric`, `SampleArray`, and alert topics using the existing schema, the dashboard should work without protocol-specific browser code.

## Testing Requirements

Minimum test coverage for a new driver:

- Parser unit tests.
- Framing/checksum tests.
- Canonical event conversion tests.
- Simulator-to-gateway smoke test.
- Canonical replay fixture test.
- Real hardware validation before production use.

Recommended commands:

```bash
cd gateway
./gradlew test :runtime:installDist

cd ..
./scripts/test-canonical-replay.sh
./scripts/test-rs232-simulators.sh
```

For hardware:

```bash
PHILIPS_SERIAL=/dev/ttyS0 DRAEGER_SERIAL=/dev/ttyS1 ./scripts/validate-air021-rs232.sh
```

Add a new hardware validation script if the new device cannot use the existing one.

## Documentation Requirements

Each new device should include:

- A driver README under `gateway/devices/<vendor-protocol>/README.md`.
- An edge deployment guide under `gateway/devices/<vendor-protocol>/EDGE.md`.
- Supported models and transports.
- Required cable and serial settings.
- Expected canonical topics.
- Simulator instructions.
- Driver-only commands that do not require simulator or `--web-port`.
- Known limitations.
- Hardware validation steps.

Update these product docs:

- `../README.md`
- `README.md`
- `canonical-schema.md` if the event contract changes
- `dashboard-integration.md` if external consumers need new handling
- `edge-driver-deployment.md` if edge deployment conventions change
- `simulator-scenarios.md`
- `gateway/ARCHITECTURE.md`
- `gateway/HARDWARE.md` if wiring or safety details are relevant

## Production Readiness Checklist

A device is production-ready only after:

- The driver can reconnect cleanly after disconnect/reconnect.
- Serial/TCP timeouts are explicit and logged.
- Device state publishes `DeviceConnectivity` events.
- Decoding is based on the vendor protocol documentation or verified captured traffic.
- No invented metrics are emitted as if they came from the device.
- Canonical events validate against schemas.
- Web dashboard renders the device without protocol-specific hacks.
- Real hardware validation has been run on the target edge device.
- Operator docs explain cabling, settings, and troubleshooting.
