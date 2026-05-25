# Gateway Simulator Pointer

This file is only a pointer for developers already browsing inside `gateway/`. The full simulator runbook is `../docs/simulator-scenarios.md`, and the normal first-run workflow is in `../README.md`.

The active simulator is a top-level sibling application under:

```text
../simulator/
```

Do not add new simulator code under `gateway/test-tools`.

Current simulator classes live in:

```text
../simulator/src/main/java/
```

Important classes:

- `CriticalInsightsMonitor`: Swing GUI simulator launcher.
- `IntellivueSimulatorV2`: Philips IntelliVue LAN/UDP simulator.
- `IntellivueSerialSimulator`: Philips MIB/RS232 simulator.
- `MedibusSimulatorV2`: Draeger MEDIBUS TCP/RS232 simulator.
- `org.mdpnp.simulator.runtime.*`: headless simulator runtime and API.
- `org.mdpnp.simulator.replay.*`: canonical JSONL replay.

Use these docs for current simulator workflows:

- `../README.md`: GUI, gateway, web dashboard, and RS232 quick start.
- `../docs/simulator-scenarios.md`: supported configs, fixtures, and smoke tests.
- `../docs/adding-devices.md`: adding simulator support for new devices.

Before changing simulator or driver wiring, run:

```bash
cd ..
./scripts/test-rs232-simulators.sh
./scripts/test-canonical-replay.sh
```
