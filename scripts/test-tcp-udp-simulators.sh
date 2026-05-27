#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WEB_PORT="${WEB_PORT:-8097}"
SIM_PORT="${SIM_PORT:-9097}"
DRAEGER_PORT="${DRAEGER_PORT:-9197}"
PHILIPS_PORT="${PHILIPS_PORT:-24105}"
JSONL="/tmp/critical-insights-tcp-udp-events.jsonl"
TEST_CONFIG="/tmp/critical-insights-tcp-udp-test.properties"

cleanup() {
    if [ -n "${SIM_PID:-}" ]; then kill "$SIM_PID" >/dev/null 2>&1 || true; fi
    if [ -n "${GW_PID:-}" ]; then kill "$GW_PID" >/dev/null 2>&1 || true; fi
    rm -f "$JSONL" "$TEST_CONFIG"
}
trap cleanup EXIT

cd "$ROOT_DIR"
rm -f "$JSONL" "$TEST_CONFIG"

cat > "$TEST_CONFIG" <<EOF
device.draeger_tcp_1.type=draeger-medibus
device.draeger_tcp_1.vendor=Draeger
device.draeger_tcp_1.model=Evita
device.draeger_tcp_1.transport.type=tcp
device.draeger_tcp_1.transport.port=$DRAEGER_PORT

device.philips_udp_1.type=philips-intellivue
device.philips_udp_1.vendor=Philips
device.philips_udp_1.model=MX800
device.philips_udp_1.patient=John Doe
device.philips_udp_1.patient-id=P12345
device.philips_udp_1.waves=all
device.philips_udp_1.transport.type=udp
device.philips_udp_1.transport.port=$PHILIPS_PORT
EOF

./scripts/run-simulator.sh --config "$TEST_CONFIG" --api-port "$SIM_PORT" >/tmp/critical-insights-tcp-udp-simulator.log 2>&1 &
SIM_PID=$!

./scripts/run-gateway.sh --multi \
  --philips-host 127.0.0.1 \
  --philips-port "$PHILIPS_PORT" \
  --draeger-tcp "127.0.0.1:${DRAEGER_PORT}" \
  --web-port "$WEB_PORT" \
  --jsonl "$JSONL" \
  --stdout false \
  --output-format canonical >/tmp/critical-insights-tcp-udp-gateway.log 2>&1 &
GW_PID=$!

for _ in $(seq 1 80); do
    curl -fsS "http://localhost:${WEB_PORT}/api/latest" >/dev/null 2>&1 || true
    if [ -f "$JSONL" ] &&
       grep -q '"topic":"Numeric".*"vendor":"draeger"' "$JSONL" &&
       grep -q '"topic":"Numeric".*"vendor":"philips"' "$JSONL" &&
       grep -q '"topic":"SampleArray".*"vendor":"philips"' "$JSONL"; then
        echo "PASS: Philips LAN/UDP and Draeger TCP clinical events reached gateway outputs"
        echo "--- Draeger sample ---"
        grep -m 1 '"topic":"Numeric".*"vendor":"draeger"' "$JSONL"
        echo "--- Philips sample ---"
        grep -m 1 '"topic":"Numeric".*"vendor":"philips"' "$JSONL"
        echo "--- Philips waveform sample ---"
        grep -m 1 '"topic":"SampleArray".*"vendor":"philips"' "$JSONL"
        exit 0
    fi
    sleep 0.5
done

echo "FAIL: did not receive TCP/UDP numeric and waveform clinical events"
echo "--- gateway log ---"
tail -100 /tmp/critical-insights-tcp-udp-gateway.log || true
echo "--- simulator log ---"
tail -100 /tmp/critical-insights-tcp-udp-simulator.log || true
exit 1
