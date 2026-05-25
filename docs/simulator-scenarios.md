# Simulator Scenarios

The simulator exists so driver, UI, API, and schema work can be developed without relying on a live bedside device for every change.

There are two simulator modes:

- Protocol simulation emits Philips or Draeger protocol traffic and exercises the production gateway drivers.
- Canonical replay emits already-normalized JSON events and exercises the web dashboard/API without protocol decoding.

Use protocol simulation when validating a driver. Use canonical replay when validating UI behavior, schema compatibility, demos, or deterministic scenarios.

## Simulator Applications

| Launcher | Purpose |
| --- | --- |
| `./scripts/run-simulator-gui.sh` | Swing GUI for starting/stopping simulator devices during local development. |
| `./scripts/run-simulator.sh --config <file>` | Headless simulator runtime driven by a properties file. |
| `./scripts/start-virtual-serial-pairs.sh` | Creates local virtual serial pairs for RS232 testing without hardware. |

The simulator sends either protocol frames or canonical events. It does not replace the gateway. For protocol simulation, the gateway must still run so the real drivers decode the traffic and publish canonical output.

## GUI Simulator Workflow

Terminal 1:

```bash
./scripts/run-simulator-gui.sh
```

In the GUI:

1. Start the Philips IntelliVue LAN/UDP simulator when testing Philips LAN/UDP.
2. Start the Draeger MEDIBUS TCP simulator when testing Draeger TCP.
3. For serial testing, use the RS232 controls after virtual serial pairs or real serial ports are available.

Terminal 2:

```bash
./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Browser:

```text
http://localhost:8081
```

The gateway owns the web dashboard. The simulator GUI does not directly feed the dashboard except when it is running canonical replay into `/api/events`.

## Headless Local Protocol Simulation

Start the default local simulators:

```bash
./scripts/run-simulator.sh --config simulator/config/local.properties
```

This starts:

| Simulated device | Transport | Endpoint |
| --- | --- | --- |
| Philips IntelliVue MX800 | LAN/UDP | UDP `24105` |
| Draeger Evita/V500-style MEDIBUS | TCP | TCP `9100` |

Start the gateway:

```bash
./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Protocol flow:

```text
simulator protocol frames
  -> gateway transport connection
  -> production driver decode
  -> canonical JSON events
  -> stdout / JSONL / HTTP / SSE / dashboard
```

## Canonical Replay

Canonical replay posts JSON events directly to the web publisher.

Terminal 1:

```bash
./scripts/run-gateway.sh --web-only \
  --web-port 8099 \
  --stdout false \
  --output-format canonical
```

Terminal 2:

```bash
./scripts/run-simulator.sh --config simulator/config/replay.properties
```

Browser:

```text
http://localhost:8099
```

Replay flow:

```text
canonical JSONL fixture
  -> simulator replay worker
  -> POST /api/events
  -> dashboard/API publisher
```

Replay is useful for:

- frontend work
- schema tests
- demos
- alert rendering
- waveform rendering
- repeatable UI scenarios

Replay cannot prove that a Philips or Draeger protocol driver decodes vendor traffic correctly.

## RS232 Simulation Without Hardware

Install `socat`, then create virtual serial pairs:

```bash
./scripts/start-virtual-serial-pairs.sh
```

Keep that terminal open. It creates:

| Path | Owner |
| --- | --- |
| `/tmp/philips-device` | Philips simulator side |
| `/tmp/philips-gateway` | Philips gateway side |
| `/tmp/draeger-device` | Draeger simulator side |
| `/tmp/draeger-gateway` | Draeger gateway side |

Start RS232 simulators:

```bash
./scripts/run-simulator.sh --config simulator/config/serial-rs232.properties
```

Start the gateway:

```bash
./scripts/run-gateway.sh --multi \
  --philips-serial /tmp/philips-gateway \
  --draeger-serial /tmp/draeger-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

If the gateway rejects a `/tmp` symlink on macOS, use the resolved `/dev/ttys*` path printed by `start-virtual-serial-pairs.sh`.

## One-Device RS232 Simulation

Philips MIB/RS232 only:

```bash
./scripts/start-virtual-serial-pairs.sh
./scripts/run-simulator.sh --config simulator/config/philips-mib-rs232.properties
./scripts/run-gateway.sh --philips-serial /tmp/philips-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

Draeger RS232 only:

```bash
./scripts/start-virtual-serial-pairs.sh
./scripts/run-simulator.sh --config simulator/config/draeger-rs232.properties
./scripts/run-gateway.sh --draeger-serial /tmp/draeger-gateway \
  --web-port 8081 \
  --stdout compact \
  --output-format canonical
```

## Config Files

Simulator configs live under:

```text
simulator/config/
```

Current configs:

| Config | Purpose |
| --- | --- |
| `local.properties` | Default Philips LAN/UDP and Draeger TCP protocol simulators. |
| `serial-rs232.properties` | Philips MIB/RS232 and Draeger RS232 simulators. |
| `philips-mib-rs232.properties` | Philips MIB/RS232 simulator only. |
| `draeger-rs232.properties` | Draeger RS232 simulator only. |
| `replay.properties` | Canonical JSONL replay into the web dashboard. |
| `scenario-normal-monitor.properties` | Replay fixture for normal monitor state. |
| `scenario-tachycardia.properties` | Replay fixture for tachycardia state. |
| `scenario-apnea.properties` | Replay fixture for apnea state. |
| `scenario-alarm-burst.properties` | Replay fixture for alarm burst behavior. |
| `scenario-disconnect-reconnect.properties` | Replay fixture for connectivity changes. |
| `scenario-bad-signal.properties` | Replay fixture for poor signal/technical state. |

## Config Format

Each device is configured as a `device.<id>.*` block:

```properties
device.philips_1.type=philips-intellivue
device.philips_1.vendor=Philips
device.philips_1.model=MX800
device.philips_1.patient=John Doe
device.philips_1.patient-id=P12345
device.philips_1.waves=all
device.philips_1.transport.type=udp
device.philips_1.transport.port=24105
```

Required:

- `device.<id>.type`

Recommended:

- `device.<id>.vendor`
- `device.<id>.model`
- `device.<id>.transport.type`

Common transport keys:

| Transport | Example keys |
| --- | --- |
| UDP | `transport.type=udp`, `transport.port=24105` |
| TCP | `transport.type=tcp`, `transport.port=9100` |
| Serial | `transport.type=serial`, `transport.port=/tmp/draeger-device` |
| Philips MIB/RS232 | `transport.type=mib-rs232`, `transport.port=/tmp/philips-device` |
| Replay HTTP | `transport.type=http`, `transport.url=http://localhost:8099/api/events` |

Replay-specific keys:

```properties
device.replay_1.type=canonical-replay
device.replay_1.file=simulator/fixtures/canonical/sample-monitor-events.jsonl
device.replay_1.target-url=http://localhost:8099/api/events
device.replay_1.interval-ms=250
device.replay_1.loop=true
```

## Canonical Fixtures

Canonical fixtures live under:

```text
simulator/fixtures/canonical/
```

Current fixtures:

| Fixture | Purpose |
| --- | --- |
| `sample-monitor-events.jsonl` | Philips-style monitor identity, patient, numerics, waveform, and alert. |
| `normal-monitor.jsonl` | Stable normal patient-monitor state. |
| `tachycardia.jsonl` | Elevated heart-rate scenario. |
| `apnea.jsonl` | Apnea-like respiratory scenario. |
| `alarm-burst.jsonl` | Multiple alerts in short succession. |
| `disconnect-reconnect.jsonl` | Connectivity loss and recovery. |
| `bad-signal.jsonl` | Poor signal and technical alert state. |

Fixtures should contain synthetic data only. Each line must be a valid canonical JSON object.

## Protocol Simulator Classes

Current protocol simulator classes:

| Class | Purpose |
| --- | --- |
| `CriticalInsightsMonitor` | Swing GUI launcher. |
| `IntellivueSimulatorV2` | Philips IntelliVue LAN/UDP simulator. |
| `IntellivueSerialSimulator` | Philips MIB/RS232 simulator. |
| `MedibusSimulatorV2` | Draeger MEDIBUS TCP/RS232 simulator. |
| `org.mdpnp.simulator.replay.*` | Canonical JSONL replay. |
| `org.mdpnp.simulator.runtime.*` | Headless simulator runtime and device lifecycle. |

Protocol simulators should be used when changing:

- framing and escaping
- checksums or CRC
- association/handshake
- polling behavior
- numeric decode
- waveform decode
- alarm decode
- serial transport behavior

## Smoke Tests

Canonical replay smoke test:

```bash
./scripts/test-canonical-replay.sh
```

RS232 protocol smoke test:

```bash
./scripts/test-rs232-simulators.sh
```

The RS232 test requires:

- Philips `Numeric`
- Philips `SampleArray`
- Philips `PatientAlert`
- Draeger `Numeric`

Real AIR-021 hardware validation:

```bash
PHILIPS_SERIAL=/dev/ttyS0 DRAEGER_SERIAL=/dev/ttyS1 ./scripts/validate-air021-rs232.sh
```

Hardware validation is intentionally separate from simulator tests.

## Troubleshooting

No data in the web dashboard:

- Confirm the gateway is running with `--web-port`.
- Confirm the simulator or real device is running.
- Check `curl http://localhost:<port>/api/latest`.
- Confirm no old process is using the same web port.

RS232 simulator connects but no clinical data appears:

- Confirm `start-virtual-serial-pairs.sh` is still running.
- Confirm simulator config uses the `*-device` side.
- Confirm gateway command uses the `*-gateway` side.
- Try the resolved `/dev/ttys*` path instead of `/tmp/...`.

Dashboard shows the wrong device:

- Use the selector in the dashboard header.
- Open a direct device URL such as `/?device=philips_mx800_01`.
- Make sure your own dashboard keys state by `unique_device_identifier`.

Waveforms missing:

- Check whether `SampleArray` events are present in `/events` or JSONL.
- Use canonical replay to test UI waveform rendering separately from protocol decode.
- Use protocol simulation or real hardware to test the driver waveform path.

## Adding Scenarios

For a new canonical scenario:

1. Add a fixture under `simulator/fixtures/canonical/`.
2. Add a matching config under `simulator/config/`.
3. Verify the fixture uses canonical fields from `canonical-schema.md`.
4. Run `./scripts/test-canonical-replay.sh`.
5. Document the scenario in this file.

For a new protocol simulator:

1. Add or update a `SimulatorDeviceFactory`.
2. Add device config blocks.
3. Add simulator-specific smoke tests.
4. Add a canonical replay fixture for UI behavior.
5. Document hardware/protocol limitations.
