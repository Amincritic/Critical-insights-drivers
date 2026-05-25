#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — Test with fake Draeger device
# No real hardware required. Runs the simulator and gateway together.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${1:-9100}"
DURATION="${2:-30}"

# --- Detect JAVA_HOME ---
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
else
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

# --- Check build ---
if [ ! -f devices/draeger-medibus/build/install/draeger/bin/draeger ]; then
    echo "ERROR: Not built yet. Run ./setup.sh first."
    exit 1
fi

# --- Compile simulator if needed ---
if [ ! -f test-tools/FakeDraegerDevice.class ] || \
   [ test-tools/FakeDraegerDevice.java -nt test-tools/FakeDraegerDevice.class ]; then
    echo "Compiling simulator..."
    "$JAVA_HOME/bin/javac" test-tools/FakeDraegerDevice.java
fi

echo "=== OpenICE Gateway Test (fake Draeger device) ==="
echo "Simulator port: $PORT"
echo "Duration: ${DURATION}s (Ctrl+C to stop early)"
echo ""

# --- Start fake device ---
"$JAVA_HOME/bin/java" -cp test-tools FakeDraegerDevice "$PORT" &
SIM_PID=$!

cleanup() {
    echo ""
    echo "Stopping..."
    kill "$SIM_PID" 2>/dev/null || true
    kill "$GW_PID" 2>/dev/null || true
    wait "$SIM_PID" 2>/dev/null || true
    wait "$GW_PID" 2>/dev/null || true

    if [ -f /tmp/openice-test.jsonl ]; then
        EVENTS=$(wc -l < /tmp/openice-test.jsonl)
        echo ""
        echo "=== Test results ==="
        echo "Events captured: $EVENTS"
        echo "Output file: /tmp/openice-test.jsonl"
        echo ""
        echo "Sample events:"
        head -5 /tmp/openice-test.jsonl | python3 -m json.tool 2>/dev/null || head -5 /tmp/openice-test.jsonl
    fi
}
trap cleanup EXIT

sleep 1

# --- Start gateway ---
echo ""
echo "--- Gateway output (stdout) ---"
echo ""

devices/draeger-medibus/build/install/draeger/bin/draeger \
    --tcp-host 127.0.0.1 --tcp-port "$PORT" \
    --gateway-id test_gw --bed-id test_bed \
    --device-id test_draeger \
    --jsonl /tmp/openice-test.jsonl \
    --stdout true &
GW_PID=$!

# --- Run for specified duration ---
sleep "$DURATION"
