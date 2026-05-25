#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WEB_PORT="${WEB_PORT:-8096}"
SIM_PORT="${SIM_PORT:-9096}"
JSONL="/tmp/critical-insights-rs232-events.jsonl"

if ! command -v socat >/dev/null 2>&1; then
    echo "ERROR: socat is required for RS232 simulator tests."
    echo "Install on macOS: brew install socat"
    echo "Install on Debian/Ubuntu: sudo apt install socat"
    exit 2
fi

cleanup() {
    if [ -n "${SIM_PID:-}" ]; then kill "$SIM_PID" >/dev/null 2>&1 || true; fi
    if [ -n "${GW_PID:-}" ]; then kill "$GW_PID" >/dev/null 2>&1 || true; fi
    if [ -n "${PH_SOCAT_PID:-}" ]; then kill "$PH_SOCAT_PID" >/dev/null 2>&1 || true; fi
    if [ -n "${DR_SOCAT_PID:-}" ]; then kill "$DR_SOCAT_PID" >/dev/null 2>&1 || true; fi
    rm -f /tmp/philips-device /tmp/philips-gateway /tmp/draeger-device /tmp/draeger-gateway "$JSONL"
    rm -f /tmp/critical-insights-philips-mib-rs232.properties /tmp/critical-insights-draeger-rs232.properties
}
trap cleanup EXIT

cd "$ROOT_DIR"
rm -f /tmp/philips-device /tmp/philips-gateway /tmp/draeger-device /tmp/draeger-gateway "$JSONL"

socat -d -d pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/philips-device pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/philips-gateway >/tmp/critical-insights-philips-socat.log 2>&1 &
PH_SOCAT_PID=$!
socat -d -d pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/draeger-device pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/draeger-gateway >/tmp/critical-insights-draeger-socat.log 2>&1 &
DR_SOCAT_PID=$!

for _ in $(seq 1 30); do
    if [ -e /tmp/philips-device ] && [ -e /tmp/philips-gateway ] && [ -e /tmp/draeger-device ] && [ -e /tmp/draeger-gateway ]; then
        break
    fi
    sleep 0.2
done

resolve_link() {
    ls -l "$1" | sed 's/.* -> //'
}

PHILIPS_DEVICE="$(resolve_link /tmp/philips-device)"
PHILIPS_GATEWAY="$(resolve_link /tmp/philips-gateway)"
DRAEGER_DEVICE="$(resolve_link /tmp/draeger-device)"
DRAEGER_GATEWAY="$(resolve_link /tmp/draeger-gateway)"

configure_pty() {
    if command -v stty >/dev/null 2>&1 && [ -n "$1" ]; then
        stty -f "$1" raw -echo -ixon -ixoff 2>/dev/null || true
    fi
}

configure_pty "$PHILIPS_DEVICE"
configure_pty "$PHILIPS_GATEWAY"
configure_pty "$DRAEGER_DEVICE"
configure_pty "$DRAEGER_GATEWAY"

TEST_CONFIG="/tmp/critical-insights-rs232-test.properties"
cat > "$TEST_CONFIG" <<EOF
device.draeger_rs232_1.type=draeger-medibus
device.draeger_rs232_1.vendor=Draeger
device.draeger_rs232_1.model=Evita V500
device.draeger_rs232_1.transport.type=serial
device.draeger_rs232_1.transport.port=$DRAEGER_DEVICE
device.draeger_rs232_1.transport.model=v500
device.draeger_rs232_1.control-file=/tmp/critical-insights-draeger-rs232.properties

device.philips_mib_1.type=philips-intellivue
device.philips_mib_1.vendor=Philips
device.philips_mib_1.model=MX800
device.philips_mib_1.transport.type=mib-rs232
device.philips_mib_1.transport.port=$PHILIPS_DEVICE
device.philips_mib_1.control-file=/tmp/critical-insights-philips-mib-rs232.properties
EOF

cat > /tmp/critical-insights-philips-mib-rs232.properties <<EOF
heartRate=132
spo2=86
pulseRate=133
respRate=20
abpSys=166
abpDia=82
nbpSys=124
nbpDia=78
etCo2=39
temp=37.2
EOF

./scripts/run-simulator.sh --config "$TEST_CONFIG" --api-port "$SIM_PORT" >/tmp/critical-insights-rs232-simulator.log 2>&1 &
SIM_PID=$!

./scripts/run-gateway.sh --multi \
  --philips-serial "$PHILIPS_GATEWAY" \
  --draeger-serial "$DRAEGER_GATEWAY" \
  --web-port "$WEB_PORT" \
  --jsonl "$JSONL" \
  --stdout false \
  --output-format canonical >/tmp/critical-insights-rs232-gateway.log 2>&1 &
GW_PID=$!

for _ in $(seq 1 80); do
    curl -fsS "http://localhost:${WEB_PORT}/api/latest" >/dev/null || true
    if [ -f "$JSONL" ] &&
       grep -q '"vendor":"draeger"' "$JSONL" &&
       grep -q '"topic":"Numeric".*"vendor":"draeger"' "$JSONL" &&
       grep -q '"vendor":"philips"' "$JSONL" &&
       grep -q '"topic":"Numeric".*"vendor":"philips"' "$JSONL" &&
       grep -q '"topic":"SampleArray".*"vendor":"philips"' "$JSONL" &&
       grep -Eq '"topic":"(PatientAlert|TechnicalAlert|Alert)".*"vendor":"philips"' "$JSONL"; then
        echo "PASS: Philips and Draeger RS232 clinical events reached gateway outputs"
        echo "--- Draeger sample ---"
        grep -m 1 '"topic":"Numeric".*"vendor":"draeger"' "$JSONL"
        echo "--- Philips sample ---"
        grep -m 1 '"topic":"Numeric".*"vendor":"philips"' "$JSONL"
        echo "--- Philips waveform sample ---"
        grep -m 1 '"topic":"SampleArray".*"vendor":"philips"' "$JSONL"
        echo "--- Philips alarm sample ---"
        grep -m 1 -E '"topic":"(PatientAlert|TechnicalAlert|Alert)".*"vendor":"philips"' "$JSONL"
        exit 0
    fi
    sleep 0.5
done

echo "FAIL: did not receive RS232 numeric/waveform/alarm clinical events from Philips and numeric events from Draeger"
echo "--- gateway log ---"
tail -80 /tmp/critical-insights-rs232-gateway.log || true
echo "--- simulator log ---"
tail -80 /tmp/critical-insights-rs232-simulator.log || true
exit 1
