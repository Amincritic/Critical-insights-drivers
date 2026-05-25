# Critical Insights Documentation

This directory contains product-level documentation for running, consuming, and extending Critical Insights.

Start with the top-level `../README.md` if you only need to run the simulator, gateway, GUI, and web dashboard.

## Pick The Right Document

| If you want to... | Read this |
| --- | --- |
| Run the whole local simulator + gateway + dashboard workflow | `../README.md` |
| Run only production drivers on an edge device | `edge-driver-deployment.md` |
| Run only the Philips driver on edge hardware | `../gateway/devices/philips-intellivue/EDGE.md` |
| Run only the Draeger driver on edge hardware | `../gateway/devices/draeger-medibus/EDGE.md` |
| Build your own dashboard or backend consumer | `dashboard-integration.md` |
| Understand the driver JSON output format | `canonical-schema.md` |
| Use replay fixtures and protocol simulators | `simulator-scenarios.md` |
| Connect real Philips/Draeger RS232 hardware | `../gateway/HARDWARE.md` |
| Add a future device or protocol | `adding-devices.md` |
| Understand internal gateway wiring | `../gateway/ARCHITECTURE.md` |

## Documentation Map

| Document | Use it for |
| --- | --- |
| `../README.md` | Main runbook: build, GUI, driver commands, web dashboard, RS232 simulation, output channels, and quick examples. |
| `canonical-schema.md` | Canonical JSON event contract and topic field definitions. |
| `dashboard-integration.md` | How to consume driver output in your own dashboard, backend, or storage service. |
| `edge-driver-deployment.md` | How to run only the edge drivers without simulator or web dashboard. |
| `simulator-scenarios.md` | Simulator modes, config files, replay fixtures, RS232 simulation, and smoke tests. |
| `adding-devices.md` | Long-term structure for adding new devices, protocols, simulators, schemas, and tests. |
| `../gateway/ARCHITECTURE.md` | Gateway internals, driver modules, publisher pipeline, and runtime entrypoints. |
| `../gateway/HARDWARE.md` | Real RS232 wiring, serial settings, AIR-021 setup, and safety notes. |
| `../gateway/devices/philips-intellivue/README.md` | Philips driver-specific notes. |
| `../gateway/devices/philips-intellivue/EDGE.md` | Philips edge-driver-only deployment. |
| `../gateway/devices/draeger-medibus/README.md` | Draeger driver-specific notes. |
| `../gateway/devices/draeger-medibus/EDGE.md` | Draeger edge-driver-only deployment. |

## Product Architecture

Critical Insights is structured around a stable canonical event boundary:

```text
device protocol -> device driver -> canonical event -> publisher/API/UI/consumer
```

The driver owns device-specific decoding. The publisher stack, web dashboard, replay fixtures, and downstream consumers should consume canonical events only.

That separation matters because:

- Adding a new device should not require a new dashboard data model if the device can emit existing canonical topics.
- Adding a new transport should not require a schema fork.
- Simulator replay can test UI and API behavior without a real protocol driver.
- Protocol simulation can still test the real driver end to end.
- External dashboards can integrate once against canonical JSON instead of one protocol at a time.

## Current Structure

```text
critical-insights/
  gateway/
    devices/common/              shared serial, IO, publisher, schema, and canonical helpers
    devices/draeger-medibus/     Draeger MEDIBUS driver
    devices/philips-intellivue/  Philips IntelliVue driver
    runtime/                     combined multi-device gateway runtime
    schemas/                     JSON Schema files for canonical topics
    test-tools/                  gateway-only helper programs

  simulator/
    src/main/java/               simulator runtime, GUI launcher, protocol simulators
    config/                      simulator device/scenario configs
    fixtures/canonical/          deterministic canonical JSONL replay fixtures

  scripts/
    run-gateway.sh
    run-simulator.sh
    run-simulator-gui.sh
    start-virtual-serial-pairs.sh
    test-canonical-replay.sh
    test-rs232-simulators.sh
    validate-air021-rs232.sh

  docs/
    README.md
    canonical-schema.md
    dashboard-integration.md
    edge-driver-deployment.md
    simulator-scenarios.md
    adding-devices.md
```

## Runtime Responsibilities

The gateway is responsible for:

- connecting to Philips and Draeger devices over supported transports
- performing protocol handshakes and polling
- decoding numerics, waveforms, alarms, identity, settings, text messages, and connectivity
- normalizing decoded data into canonical JSON
- publishing canonical JSON to stdout, JSONL, HTTP, and the web dashboard

The simulator is responsible for:

- running protocol-level virtual devices for driver testing
- running canonical JSONL replay for UI/schema testing
- providing a GUI for local development
- providing repeatable scenarios for smoke tests and demos

The web dashboard is responsible for:

- displaying the latest canonical event state
- rendering numerics, waveforms, alerts, and device state from gateway events
- receiving events from the gateway over Server-Sent Events

External dashboards should follow the same contract as the built-in dashboard. See `dashboard-integration.md`.

## Development Checklist

Before changing canonical output, update:

- `canonical-schema.md`
- matching files in `../gateway/schemas/`
- canonical replay fixtures under `../simulator/fixtures/canonical/`
- smoke test assertions if event shape changed

Before changing simulator or driver wiring, run:

```bash
./scripts/test-canonical-replay.sh
./scripts/test-rs232-simulators.sh
```

Before calling a serial change production-ready, validate against real hardware:

```bash
PHILIPS_SERIAL=/dev/ttyS0 DRAEGER_SERIAL=/dev/ttyS1 ./scripts/validate-air021-rs232.sh
```
