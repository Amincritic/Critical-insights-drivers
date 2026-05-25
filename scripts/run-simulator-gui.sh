#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: java not found. Install JDK 17+."
    exit 1
fi

if [ ! -f "$ROOT_DIR/simulator/build/install/simulator/lib/simulator.jar" ]; then
    echo "ERROR: simulator runtime is not built."
    echo "Run: cd simulator && ../gateway/gradlew installDist"
    exit 1
fi

cd "$ROOT_DIR"
exec java -cp "simulator/build/install/simulator/lib/*" CriticalInsightsMonitor "$@"
