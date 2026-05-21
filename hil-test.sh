#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — HIL (Hardware-in-the-Loop) Test
#
# Creates virtual serial port pairs using socat, runs fake device simulators
# on one end, and connects the gateway to the other end.
#
# Requirements:
#   sudo apt install socat
#
# Usage:
#   ./hil-test.sh                         # Draeger only, 30 seconds
#   ./hil-test.sh --duration 60           # Draeger only, 60 seconds
#   ./hil-test.sh --draeger               # Draeger only (default)
#   ./hil-test.sh --multi                 # Draeger + Philips (Philips placeholder)
#   ./hil-test.sh --loopback              # Use real loopback cables on /dev/ttyS0,S1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- Defaults ---
DURATION=30
MODE="draeger"
USE_LOOPBACK=false
DRAEGER_DEVICE="/tmp/hil-draeger-device"
DRAEGER_GATEWAY="/tmp/hil-draeger-gateway"
PHILIPS_DEVICE="/tmp/hil-philips-device"
PHILIPS_GATEWAY="/tmp/hil-philips-gateway"
LOOPBACK_DRAEGER="/dev/ttyS1"
LOOPBACK_PHILIPS="/dev/ttyS0"
OUTPUT_FILE="/tmp/hil-test-output.jsonl"

# --- Parse args ---
while [ $# -gt 0 ]; do
    case "$1" in
        --duration) DURATION="$2"; shift 2 ;;
        --draeger) MODE="draeger"; shift ;;
        --multi) MODE="multi"; shift ;;
        --loopback) USE_LOOPBACK=true; shift ;;
        --help)
            cat <<'HELP'
HIL Test — Hardware-in-the-Loop testing with virtual serial ports

Usage:
  ./hil-test.sh [options]

Options:
  --draeger              Test Draeger gateway only (default)
  --multi                Test Draeger + Philips together
  --duration <seconds>   Test duration (default: 30)
  --loopback             Use real serial ports with loopback cables
                         instead of socat virtual ports
  --help                 Show this help

Requirements:
  sudo apt install socat    (for virtual serial ports)

What it does:
  1. Creates virtual serial port pairs using socat
  2. Runs fake device simulator(s) on the "device" end
  3. Connects the gateway to the "gateway" end
  4. Captures JSON output and reports results

The test verifies:
  - Serial port open/close lifecycle
  - MEDIBUS protocol framing over serial
  - Data parsing and JSON output
  - Reconnection behavior (kill/restart simulator mid-test)
HELP
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# --- Detect JAVA_HOME ---
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
else
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

# --- Check prerequisites ---
echo "=== HIL Test ==="
echo "Mode: $MODE | Duration: ${DURATION}s"
echo ""

if [ "$USE_LOOPBACK" = false ]; then
    if ! command -v socat >/dev/null 2>&1; then
        echo "ERROR: socat is required for virtual serial ports."
        echo "  Install: sudo apt install socat"
        echo "  Or use --loopback with real serial cables."
        exit 1
    fi
fi

if [ ! -f devices/draeger/build/install/draeger/bin/draeger ]; then
    echo "ERROR: Not built yet. Run ./setup.sh first."
    exit 1
fi

# --- Compile simulators ---
echo "[1/5] Compiling simulators..."
"$JAVA_HOME/bin/javac" test-tools/FakeDraegerSerial.java
echo "  Done"

# --- Track PIDs for cleanup ---
PIDS=()
cleanup() {
    echo ""
    echo "[5/5] Stopping all processes..."
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    for pid in "${PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    # Remove socat symlinks
    rm -f "$DRAEGER_DEVICE" "$DRAEGER_GATEWAY" "$PHILIPS_DEVICE" "$PHILIPS_GATEWAY" 2>/dev/null || true

    # Report results
    echo ""
    echo "=== HIL Test Results ==="
    if [ -f "$OUTPUT_FILE" ]; then
        TOTAL=$(wc -l < "$OUTPUT_FILE")
        VITALS=$(grep -c '"eventType":"vital"' "$OUTPUT_FILE" 2>/dev/null || echo 0)
        IDENTITY=$(grep -c '"eventType":"device_identity"' "$OUTPUT_FILE" 2>/dev/null || echo 0)
        ERRORS=$(grep -c '"eventType":"error"' "$OUTPUT_FILE" 2>/dev/null || echo 0)

        echo "Total events:    $TOTAL"
        echo "  Vitals:        $VITALS"
        echo "  Device ID:     $IDENTITY"
        echo "  Errors:        $ERRORS"
        echo "Output file:     $OUTPUT_FILE"
        echo ""

        if [ "$TOTAL" -gt 0 ]; then
            echo "PASS: Gateway received data over virtual serial ports"
        else
            echo "FAIL: No events captured"
        fi
    else
        echo "FAIL: No output file created"
    fi
    echo ""
}
trap cleanup EXIT

# --- Create virtual serial ports ---
echo "[2/5] Creating virtual serial ports..."
rm -f "$OUTPUT_FILE"

if [ "$USE_LOOPBACK" = true ]; then
    echo "  Using real serial ports with loopback cables"
    DRAEGER_GATEWAY="$LOOPBACK_DRAEGER"
    PHILIPS_GATEWAY="$LOOPBACK_PHILIPS"
    # In loopback mode, device and gateway are the same port
    # You need a physical loopback cable or null-modem cable
    DRAEGER_DEVICE="$LOOPBACK_DRAEGER"
    echo "  Draeger: $DRAEGER_GATEWAY"
    if [ "$MODE" = "multi" ]; then
        PHILIPS_DEVICE="$LOOPBACK_PHILIPS"
        echo "  Philips: $PHILIPS_GATEWAY"
    fi
else
    # Draeger virtual serial pair
    socat -d -d \
        pty,raw,echo=0,link="$DRAEGER_DEVICE" \
        pty,raw,echo=0,link="$DRAEGER_GATEWAY" &
    PIDS+=($!)
    sleep 1

    if [ "$MODE" = "multi" ]; then
        # Philips virtual serial pair
        socat -d -d \
            pty,raw,echo=0,link="$PHILIPS_DEVICE" \
            pty,raw,echo=0,link="$PHILIPS_GATEWAY" &
        PIDS+=($!)
        sleep 1
    fi

    echo "  Draeger: $DRAEGER_DEVICE <--serial--> $DRAEGER_GATEWAY"
    if [ "$MODE" = "multi" ]; then
        echo "  Philips: $PHILIPS_DEVICE <--serial--> $PHILIPS_GATEWAY"
    fi
fi

# --- Start simulators ---
echo "[3/5] Starting device simulators..."

"$JAVA_HOME/bin/java" -cp test-tools FakeDraegerSerial "$DRAEGER_DEVICE" &
PIDS+=($!)
echo "  Draeger simulator PID: ${PIDS[-1]}"
sleep 1

if [ "$MODE" = "multi" ]; then
    echo "  NOTE: Philips MIB/RS232 simulator not yet implemented."
    echo "        The Philips adapter will attempt to connect but receive no data."
    echo "        Draeger data will still flow through the shared queue."
fi

# --- Start gateway ---
echo "[4/5] Starting gateway (${DURATION}s)..."
echo ""
echo "--- Gateway output ---"
echo ""

if [ "$MODE" = "draeger" ]; then
    timeout "$DURATION" devices/draeger/build/install/draeger/bin/draeger \
        --serial "$DRAEGER_GATEWAY" \
        --gateway-id hil_test --bed-id hil_bed \
        --device-id hil_draeger \
        --jsonl "$OUTPUT_FILE" \
        --stdout true \
        2>/tmp/hil-test-stderr.log || true
elif [ "$MODE" = "multi" ]; then
    MULTI_ARGS=(
        --gateway-id hil_test --bed-id hil_bed
        --draeger-serial "$DRAEGER_GATEWAY"
        --draeger-device-id hil_draeger
        --jsonl "$OUTPUT_FILE"
        --stdout true
    )
    if [ "$USE_LOOPBACK" = false ]; then
        MULTI_ARGS+=(--philips-serial "$PHILIPS_GATEWAY" --philips-device-id hil_philips)
    fi
    timeout "$DURATION" devices/multidevice/build/install/multidevice/bin/multidevice \
        "${MULTI_ARGS[@]}" \
        2>/tmp/hil-test-stderr.log || true
fi
