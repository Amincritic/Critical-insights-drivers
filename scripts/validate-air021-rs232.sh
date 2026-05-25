#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DURATION="${DURATION:-60}"
WEB_PORT="${WEB_PORT:-8095}"
PHILIPS_SERIAL="${PHILIPS_SERIAL:-/dev/ttyS0}"
DRAEGER_SERIAL="${DRAEGER_SERIAL:-/dev/ttyS1}"
JSONL="${JSONL:-/tmp/critical-insights-air021-rs232.jsonl}"

cleanup() {
    if [ -n "${GW_PID:-}" ]; then kill "$GW_PID" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

cd "$ROOT_DIR"
rm -f "$JSONL"

echo "AIR-021 RS232 validation"
echo "  Philips serial: $PHILIPS_SERIAL"
echo "  Draeger serial: $DRAEGER_SERIAL"
echo "  Duration:       ${DURATION}s"
echo "  JSONL:          $JSONL"
echo ""
echo "Before running, confirm real devices are connected and configured:"
echo "  Philips IntelliVue MIB/RS232: 115200 8N1, no flow control"
echo "  Draeger MEDIBUS: 19200 8E1, no flow control"
echo ""

./scripts/run-gateway.sh --multi \
  --philips-serial "$PHILIPS_SERIAL" \
  --draeger-serial "$DRAEGER_SERIAL" \
  --web-port "$WEB_PORT" \
  --jsonl "$JSONL" \
  --stdout false \
  --output-format canonical >/tmp/critical-insights-air021-gateway.log 2>&1 &
GW_PID=$!

deadline=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$deadline" ]; do
    if [ -f "$JSONL" ] &&
       grep -q '"vendor":"draeger"' "$JSONL" &&
       grep -q '"vendor":"philips"' "$JSONL"; then
        echo "PASS: both real RS232 devices produced canonical events"
        echo "--- Draeger sample ---"
        grep '"vendor":"draeger"' "$JSONL" | head -1
        echo "--- Philips sample ---"
        grep '"vendor":"philips"' "$JSONL" | head -1
        exit 0
    fi
    sleep 1
done

echo "FAIL: did not receive both Philips and Draeger canonical events within ${DURATION}s"
echo "--- gateway log ---"
tail -120 /tmp/critical-insights-air021-gateway.log || true
exit 1
