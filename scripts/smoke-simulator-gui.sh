#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "$ROOT_DIR/simulator"
../gateway/gradlew installDist

cd "$ROOT_DIR"
exec java -Djava.awt.headless=true -cp "simulator/build/install/simulator/lib/*" org.mdpnp.simulator.launcher.CriticalInsightsMonitorLauncher --smoke
