# Critical Insights Gateway

This directory contains the OpenICE-derived Java gateway runtime and the production protocol drivers.

Use the top-level scripts from `../` for normal development. They keep paths stable and match the product docs:

```bash
cd ..
./scripts/run-gateway.sh --help
./scripts/run-simulator-gui.sh
./scripts/test-rs232-simulators.sh
```

## Responsibilities

The gateway is responsible for:

- Connecting to device transports.
- Decoding Philips IntelliVue LAN/UDP data.
- Decoding Philips IntelliVue MIB/RS232 fixed baudrate data.
- Decoding Draeger MEDIBUS TCP and RS232 data.
- Converting decoded observations into canonical JSON events.
- Publishing events to stdout, JSONL, HTTP, and the built-in web dashboard.

The gateway is not responsible for:

- Running the product simulator.
- Storing long-term clinical history.
- Decoding protocol data in the browser.
- Defining device-specific UI behavior.

## Build

From this directory:

```bash
./gradlew :runtime:installDist
```

From the top-level directory:

```bash
cd gateway
./gradlew :runtime:installDist
```

## Run

Prefer the top-level wrapper:

```bash
../scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --draeger-tcp 127.0.0.1:9100 \
  --stdout compact \
  --web-port 8081 \
  --output-format canonical
```

Real serial example:

```bash
../scripts/run-gateway.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --stdout compact \
  --web-port 8081 \
  --output-format canonical
```

Web-only replay target:

```bash
../scripts/run-gateway.sh --web-only \
  --web-port 8099 \
  --stdout false \
  --output-format canonical
```

## Current Drivers

| Module | Device/protocol | Transports |
| --- | --- | --- |
| `devices/philips-intellivue/` | Philips IntelliVue Data Export | LAN/UDP, MIB/RS232 |
| `devices/draeger-medibus/` | Draeger MEDIBUS | TCP, RS232 |

Both drivers publish canonical topics for connectivity, identity, numerics, waveforms, and alerts where the protocol provides the data.

For edge deployments that do not use the simulator or web dashboard, start with:

```text
../docs/edge-driver-deployment.md
devices/philips-intellivue/EDGE.md
devices/draeger-medibus/EDGE.md
```

## Documentation

- `../README.md`: product quick start.
- `../docs/README.md`: product documentation index.
- `../docs/canonical-schema.md`: canonical event contract.
- `../docs/dashboard-integration.md`: consuming gateway output in an external dashboard or backend.
- `../docs/edge-driver-deployment.md`: running only drivers on an edge device.
- `../docs/simulator-scenarios.md`: simulator configs and smoke tests.
- `../docs/adding-devices.md`: long-term device extension guide.
- `ARCHITECTURE.md`: gateway internals.
- `HARDWARE.md`: RS232 wiring and device-side setup.
