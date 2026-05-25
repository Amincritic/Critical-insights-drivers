# Philips IntelliVue Driver

This module contains the Philips IntelliVue driver used by the Critical Insights gateway.

For driver-only edge deployment without simulator or web dashboard, see:

```text
EDGE.md
../../../docs/edge-driver-deployment.md
```

## Supported Device Family

The driver targets Philips IntelliVue Data Export, including MX-series monitors such as the MX800 when configured for LAN Data Export or MIB/RS232 Data Out.

Supported transports:

- LAN/UDP Data Export.
- MIB/RS232 fixed baudrate Data Export.

## Published Canonical Topics

The driver publishes canonical events for:

- `DeviceConnectivity`
- `DeviceIdentity`
- `Numeric`
- `SampleArray`
- `PatientAlert`
- `TechnicalAlert`

The web dashboard consumes these canonical events. It does not decode IntelliVue protocol frames.

External dashboards should consume the same canonical events through `/events`, `--jsonl`, or `--http-url`. See:

```text
../../../docs/dashboard-integration.md
../../../docs/canonical-schema.md
```

## LAN/UDP

Development/simulator example:

```bash
cd ../../..
./scripts/run-gateway.sh --philips-host 127.0.0.1 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

For a real monitor, replace `127.0.0.1` with the monitor IP address or the host/interface address expected by the Data Export configuration.

## MIB/RS232

Development with a virtual serial pair:

```bash
cd ../../..
./scripts/start-virtual-serial-pairs.sh
./scripts/run-simulator.sh --config simulator/config/philips-mib-rs232.properties
./scripts/run-gateway.sh --philips-serial /tmp/philips-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Real hardware example:

```bash
cd ../../..
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --philips-baud 115200 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

MIB/RS232 serial settings:

```text
115200 or 19200 baud, 8 data bits, no parity, 1 stop bit, no flow control
```

The serial settings are configured by the driver. The default is 115200. Use `--philips-baud 19200` for a monitor configured for the 19200 fixed-baudrate mode.

## Driver Path

The MIB/RS232 path unwraps fixed-baudrate serial frames and feeds the same IntelliVue parser used by the LAN/UDP path:

```text
serial bytes
  -> RS232Adapter
  -> frame unescape and FCS validation
  -> IntelliVue protocol parser
  -> decoded observations
  -> canonical events
```

Waveforms are decoded into `SampleArray` events. Numeric observations are decoded into `Numeric` events. Alarm state is decoded into `PatientAlert` or `TechnicalAlert` events when available.

For realtime numerics, waveforms, and alarms, the headless driver requests Philips Extended Poll results. It preserves poll sequence numbers and relative timestamps in the published event fields so downstream systems can detect sequence or timing gaps. Patient demographics are still requested with Single Poll because they are lower-frequency data.

## Simulator

Simulator classes live in the top-level simulator module:

```text
../../../simulator/src/main/java/
```

Relevant classes:

- `IntellivueSimulatorV2`: LAN/UDP simulator.
- `IntellivueSerialSimulator`: MIB/RS232 simulator.
- `PhilipsWaveformFrame`: simulator waveform frame helper.

## Hardware Notes

Wiring and monitor configuration are documented in:

```text
../../HARDWARE.md
```

Important points:

- Philips MIB/RS232 uses an RJ-45 connector, not DB-9.
- The gateway side usually uses DB-9 on AIR-021.
- The Philips cable is straight-through by signal mapping, not a Draeger-style null modem.
- Use the monitor's fixed baudrate protocol.
- Do not invent decode behavior; use the Philips programming guide or captured traffic.

## Tests

From the top-level directory:

```bash
./scripts/test-rs232-simulators.sh
./scripts/test-canonical-replay.sh
```

The RS232 smoke test requires Philips numeric, waveform, and alert events to reach the gateway output.

## Development Notes

When adding Philips decode support:

- Keep protocol parsing in this module.
- Keep canonical conversion close to the headless publisher path.
- Preserve raw vendor identifiers when no canonical name is known.
- Update `../../../docs/canonical-schema.md` only when product-facing event fields change.
- Add simulator parity for LAN/UDP and MIB/RS232 when practical.
