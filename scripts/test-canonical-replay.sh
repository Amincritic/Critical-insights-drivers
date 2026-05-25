#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WEB_PORT="${WEB_PORT:-8099}"
SIM_PORT="${SIM_PORT:-9098}"

cleanup() {
    if [ -n "${SIM_PID:-}" ]; then kill "$SIM_PID" >/dev/null 2>&1 || true; fi
    if [ -n "${GW_PID:-}" ]; then kill "$GW_PID" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

cd "$ROOT_DIR"

./scripts/run-gateway.sh --web-only --web-port "$WEB_PORT" --stdout false --output-format canonical > /tmp/critical-insights-replay-gateway.log 2>&1 &
GW_PID=$!

for _ in $(seq 1 30); do
    if curl -fsS "http://localhost:${WEB_PORT}/api/latest" >/dev/null; then
        break
    fi
    sleep 0.5
done

./scripts/run-simulator.sh --config simulator/config/replay.properties --api-port "$SIM_PORT" > /tmp/critical-insights-replay-simulator.log 2>&1 &
SIM_PID=$!

for _ in $(seq 1 40); do
    latest="$(curl -fsS "http://localhost:${WEB_PORT}/api/latest" || true)"
    if printf '%s' "$latest" | grep -q '"topic":"SampleArray"'; then
        echo "PASS: canonical replay reached web dashboard"
        printf '%s\n' "$latest"
        exit 0
    fi
    sleep 0.5
done

echo "FAIL: canonical replay did not reach web dashboard"
echo "--- gateway log ---"
tail -40 /tmp/critical-insights-replay-gateway.log || true
echo "--- simulator log ---"
tail -40 /tmp/critical-insights-replay-simulator.log || true
exit 1
