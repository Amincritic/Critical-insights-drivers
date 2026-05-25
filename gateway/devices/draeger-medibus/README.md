# Draeger MEDIBUS Driver

This module contains the Draeger MEDIBUS driver used by the Critical Insights gateway.

For driver-only edge deployment without simulator or web dashboard, see:

```text
EDGE.md
../../../docs/edge-driver-deployment.md
```

## Supported Device Family

The driver targets Draeger ventilators that expose MEDIBUS over TCP or RS232. The hardware guide lists common models and serial settings, including Evita and V500-style operation.

Supported transports:

- TCP for simulator/development or serial-to-Ethernet adapters.
- RS232 serial for real ventilators.

## Published Canonical Topics

The driver publishes canonical events for:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`
- `DeviceSetting`
- `TextMessage`

The web dashboard consumes these canonical events. It does not decode MEDIBUS protocol frames.

External dashboards should consume the same canonical events through `/events`, `--jsonl`, or `--http-url`. See:

```text
../../../docs/dashboard-integration.md
../../../docs/canonical-schema.md
```

## Device Profiles

The launcher supports MEDIBUS device profiles because Fabius/Tiro and intensive-care ventilators do not expose exactly the same command set.

```bash
./scripts/run-gateway.sh --draeger-tcp 127.0.0.1:9100 \
  --draeger-profile v500 \
  --web-port 8081 \
  --output-format canonical
```

Available profiles:

- `v500`: default; requests measured data CP1/CP2, alarm CP1/CP2, limits, settings, text messages, and realtime waveforms.
- `intensive-care`: for the intensive-care MEDIBUS manual family; avoids measured-data CP2 but keeps alarm CP2.
- `evita`: conservative Evita profile; measured data CP1 and alarm CP1.
- `savina`: measured data CP1 and alarm CP1/CP2.
- `fabius`: Fabius GS/Tiro profile; measured data CP1 and alarm CP1/CP2.

## TCP

Development/simulator example:

```bash
cd ../../..
./scripts/run-gateway.sh --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

## RS232

Development with a virtual serial pair:

```bash
cd ../../..
./scripts/start-virtual-serial-pairs.sh
./scripts/run-simulator.sh --config simulator/config/draeger-rs232.properties
./scripts/run-gateway.sh --draeger-serial /tmp/draeger-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Real hardware example:

```bash
cd ../../..
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Default serial settings:

```text
19200 baud, 8 data bits, even parity, 1 stop bit, no hardware flow control
```

Some Draeger models use different settings. Override when needed:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-baud 38400 \
  --serial-parity none \
  --web-port 8081 \
  --output-format canonical
```

## Driver Path

The runtime flow is:

```text
TCP or serial bytes
  -> MEDIBUS framing/checksum
  -> ICC handshake and polling
  -> measured data / alarm / waveform decode
  -> canonical events
```

Incoming MEDIBUS response checksums are validated before parsing. Numeric values become `Numeric` events. Realtime/waveform data becomes `SampleArray` events. Alarm responses become `PatientAlert` or `TechnicalAlert` events depending on the decoded alarm type. Device settings and text-message responses become `DeviceSetting` and `TextMessage` events.

## Simulator

Simulator classes live in the top-level simulator module:

```text
../../../simulator/src/main/java/
```

Relevant class:

- `MedibusSimulatorV2`: TCP and RS232 MEDIBUS simulator.

## Hardware Notes

Wiring and ventilator configuration are documented in:

```text
../../HARDWARE.md
```

Important points:

- Draeger RS232 usually requires a null-modem/crossover cable.
- Evita-style Channel A defaults to 19200 baud with even parity.
- V500 deployments should be validated against the actual device-side MEDIBUS settings.
- Keep the ICC handshake and polling active; long idle gaps can terminate the link.

## Tests

From the top-level directory:

```bash
./scripts/test-rs232-simulators.sh
./scripts/test-canonical-replay.sh
```

The RS232 smoke test requires a Draeger numeric event to reach the gateway output, alongside Philips RS232 events.

## Development Notes

When adding Draeger decode support:

- Use MEDIBUS maps under `src/main/resources/.../types/` when possible.
- Preserve raw command/data identifiers when no readable mapping exists.
- Keep polling intervals compatible with MEDIBUS timeout requirements.
- Add simulator support and smoke tests for any new response type.
- Update `../../../docs/canonical-schema.md` only when product-facing event fields change.
