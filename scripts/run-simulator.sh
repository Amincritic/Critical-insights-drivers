#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -x "$ROOT_DIR/simulator/build/install/simulator/bin/simulator" ]; then
    echo "ERROR: simulator runtime is not built."
    echo "Run: cd simulator && ../gateway/gradlew installDist"
    exit 1
fi

cd "$ROOT_DIR"
exec simulator/build/install/simulator/bin/simulator --config simulator/config/local.properties "$@"
