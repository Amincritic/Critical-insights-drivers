#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — Run with real devices
#
# Usage:
#   ./run.sh --help
#   ./run.sh --auto                        # auto-detect devices on COM ports
#   ./run.sh --draeger-serial /dev/ttyS1
#   ./run.sh --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1
#   ./run.sh --draeger-tcp 192.168.10.60:4001
#
# This is a convenience wrapper. For full control, use the launchers directly:
#   devices/draeger/build/install/draeger/bin/draeger --help
#   devices/philips/build/install/philips/bin/philips-serial --help
#   devices/multidevice/build/install/multidevice/bin/multidevice --help

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- Detect JAVA_HOME ---
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
else
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

# --- Check build ---
if [ ! -d devices/draeger/build/install ] && [ ! -d devices/philips/build/install ] && [ ! -d devices/multidevice/build/install ]; then
    echo "ERROR: Not built yet. Run ./setup.sh first."
    exit 1
fi

# --- Parse simplified args ---
show_help() {
    cat <<'HELP'
OpenICE Headless JSON Gateway — Run Script

Usage:
  ./run.sh [mode] [options]

Modes (pick one):
  --auto                               Auto-detect devices on COM ports
  --draeger-serial <port>              Draeger ventilator over RS-232
  --draeger-tcp <host:port>            Draeger ventilator over TCP
  --philips-serial <port>              Philips MX800 over MIB/RS232
  --philips-host <ip>                  Philips monitor over LAN/UDP
  --multi                              Both devices (requires device flags below)

Device options (for --multi mode):
  --philips-serial <port>              Philips COM port (e.g. /dev/ttyS0)
  --philips-host <ip>                  Philips IP address
  --draeger-serial <port>              Draeger COM port (e.g. /dev/ttyS1)

Common options:
  --gateway-id <id>                    default: air021_01
  --bed-id <id>                        default: bed_01
  --device-id <id>                     default: auto-generated from device type
  --jsonl <path>                       Write JSON lines to file
  --stdout                             Print JSON to terminal
  --http-url <url>                     POST JSON to HTTP endpoint
  --help                               Show this help

Examples:
  # Auto-detect devices on COM ports and run
  ./run.sh --auto --stdout

  # Auto-detect with JSONL output
  ./run.sh --auto --jsonl /var/log/openice/bed01.jsonl --stdout

  # Draeger only, output to terminal
  ./run.sh --draeger-serial /dev/ttyS1 --stdout

  # Philips MX800 only, save to file
  ./run.sh --philips-serial /dev/ttyS0 --jsonl /var/log/openice/philips.jsonl

  # Both devices on AIR-021 COM ports
  ./run.sh --multi --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1 \
           --jsonl /var/log/openice/bed01.jsonl --stdout

  # Draeger over TCP (serial-to-Ethernet adapter)
  ./run.sh --draeger-tcp 192.168.10.60:4001 --stdout

  # Full production with HTTP POST
  ./run.sh --multi --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1 \
           --jsonl /var/log/openice/bed01.jsonl \
           --http-url https://hospital.example/api/events \
           --http-header 'Authorization: Bearer YOUR_TOKEN'

For all options, run the launchers directly:
  devices/draeger/build/install/draeger/bin/draeger --help
  devices/philips/build/install/philips/bin/philips-serial --help
  devices/multidevice/build/install/multidevice/bin/multidevice --help
HELP
    exit 0
}

# Defaults
GATEWAY_ID="air021_01"
BED_ID="bed_01"
MODE=""
AUTO_DETECT=false
DRAEGER_SERIAL=""
DRAEGER_TCP_HOST=""
DRAEGER_TCP_PORT=""
PHILIPS_SERIAL=""
PHILIPS_HOST=""
JSONL=""
STDOUT_FLAG=""
HTTP_URL=""
HTTP_HEADERS=()
EXTRA_ARGS=()
DRAEGER_DEVICE_ID="draeger_vent_01"
PHILIPS_DEVICE_ID="philips_mx800_01"

if [ $# -eq 0 ]; then show_help; fi

while [ $# -gt 0 ]; do
    case "$1" in
        --help) show_help ;;
        --auto) AUTO_DETECT=true; shift ;;
        --multi) MODE="multi"; shift ;;
        --draeger-serial) DRAEGER_SERIAL="$2"; MODE="${MODE:-draeger}"; shift 2 ;;
        --draeger-tcp)
            IFS=':' read -r DRAEGER_TCP_HOST DRAEGER_TCP_PORT <<< "$2"
            MODE="${MODE:-draeger-tcp}"; shift 2 ;;
        --philips-serial) PHILIPS_SERIAL="$2"; MODE="${MODE:-philips-serial}"; shift 2 ;;
        --philips-host) PHILIPS_HOST="$2"; MODE="${MODE:-philips-udp}"; shift 2 ;;
        --gateway-id) GATEWAY_ID="$2"; shift 2 ;;
        --bed-id) BED_ID="$2"; shift 2 ;;
        --device-id) DRAEGER_DEVICE_ID="$2"; PHILIPS_DEVICE_ID="$2"; shift 2 ;;
        --jsonl) JSONL="$2"; shift 2 ;;
        --stdout) STDOUT_FLAG="true"; shift ;;
        --http-url) HTTP_URL="$2"; shift 2 ;;
        --http-header) HTTP_HEADERS+=("$2"); shift 2 ;;
        *) EXTRA_ARGS+=("$1"); shift ;;
    esac
done

# Build common output args
OUTPUT_ARGS=()
if [ -n "$JSONL" ]; then OUTPUT_ARGS+=(--jsonl "$JSONL"); fi
if [ -n "$STDOUT_FLAG" ]; then OUTPUT_ARGS+=(--stdout true); fi
if [ -n "$HTTP_URL" ]; then OUTPUT_ARGS+=(--http-url "$HTTP_URL"); fi
for h in "${HTTP_HEADERS[@]+"${HTTP_HEADERS[@]}"}"; do
    OUTPUT_ARGS+=(--http-header "$h")
done

# Default: if no output specified, enable stdout
if [ -z "$JSONL" ] && [ -z "$STDOUT_FLAG" ] && [ -z "$HTTP_URL" ]; then
    OUTPUT_ARGS+=(--stdout true)
fi

# --- Auto-detect ---
if [ "$AUTO_DETECT" = true ]; then
    echo "=== Auto-detecting devices on serial ports ==="
    echo ""

    # Compile detector if needed
    if [ ! -f test-tools/PortDetector.class ] || \
       [ test-tools/PortDetector.java -nt test-tools/PortDetector.class ]; then
        "$JAVA_HOME/bin/javac" test-tools/PortDetector.java
    fi

    # Run detection
    DETECT_OUTPUT=$("$JAVA_HOME/bin/java" -cp test-tools PortDetector 2>/dev/tty || true)

    if [ -z "$DETECT_OUTPUT" ]; then
        echo "ERROR: No devices detected on any serial port."
        echo "Check cables and device power, then try again."
        echo "Or specify ports manually: ./run.sh --draeger-serial /dev/ttyS1"
        exit 1
    fi

    # Parse results
    DETECTED_DRAEGER=$(echo "$DETECT_OUTPUT" | grep "^DRAEGER=" | cut -d= -f2 || true)
    DETECTED_PHILIPS=$(echo "$DETECT_OUTPUT" | grep "^PHILIPS=" | cut -d= -f2 || true)

    if [ -n "$DETECTED_DRAEGER" ] && [ -n "$DETECTED_PHILIPS" ]; then
        MODE="multi"
        DRAEGER_SERIAL="$DETECTED_DRAEGER"
        PHILIPS_SERIAL="$DETECTED_PHILIPS"
        echo "Auto-detected: Draeger on $DRAEGER_SERIAL, Philips on $PHILIPS_SERIAL"
    elif [ -n "$DETECTED_DRAEGER" ]; then
        MODE="draeger"
        DRAEGER_SERIAL="$DETECTED_DRAEGER"
        echo "Auto-detected: Draeger on $DRAEGER_SERIAL"
    elif [ -n "$DETECTED_PHILIPS" ]; then
        MODE="philips-serial"
        PHILIPS_SERIAL="$DETECTED_PHILIPS"
        echo "Auto-detected: Philips on $PHILIPS_SERIAL"
    else
        echo "ERROR: Could not identify any devices."
        echo "Specify ports manually: ./run.sh --draeger-serial /dev/ttyS1"
        exit 1
    fi
    echo ""
fi

echo "=== OpenICE Gateway ==="
echo "Mode: $MODE | Gateway: $GATEWAY_ID | Bed: $BED_ID"
echo ""

case "$MODE" in
    draeger)
        echo "Draeger serial: $DRAEGER_SERIAL"
        exec devices/draeger/build/install/draeger/bin/draeger \
            --serial "$DRAEGER_SERIAL" \
            --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" \
            --device-id "$DRAEGER_DEVICE_ID" \
            "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
        ;;
    draeger-tcp)
        echo "Draeger TCP: $DRAEGER_TCP_HOST:$DRAEGER_TCP_PORT"
        exec devices/draeger/build/install/draeger/bin/draeger \
            --tcp-host "$DRAEGER_TCP_HOST" --tcp-port "$DRAEGER_TCP_PORT" \
            --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" \
            --device-id "$DRAEGER_DEVICE_ID" \
            "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
        ;;
    philips-serial)
        echo "Philips MIB/RS232: $PHILIPS_SERIAL"
        exec devices/philips/build/install/philips/bin/philips-serial \
            --serial "$PHILIPS_SERIAL" \
            --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" \
            --device-id "$PHILIPS_DEVICE_ID" \
            "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
        ;;
    philips-udp)
        echo "Philips LAN/UDP: $PHILIPS_HOST"
        exec devices/philips/build/install/philips/bin/philips \
            --host "$PHILIPS_HOST" \
            --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" \
            --device-id "$PHILIPS_DEVICE_ID" \
            "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
        ;;
    multi)
        MULTI_ARGS=(--gateway-id "$GATEWAY_ID" --bed-id "$BED_ID")
        if [ -n "$PHILIPS_SERIAL" ]; then
            MULTI_ARGS+=(--philips-serial "$PHILIPS_SERIAL" --philips-device-id "$PHILIPS_DEVICE_ID")
            echo "Philips MIB/RS232: $PHILIPS_SERIAL"
        fi
        if [ -n "$PHILIPS_HOST" ]; then
            MULTI_ARGS+=(--philips-host "$PHILIPS_HOST" --philips-device-id "$PHILIPS_DEVICE_ID")
            echo "Philips LAN/UDP: $PHILIPS_HOST"
        fi
        if [ -n "$DRAEGER_SERIAL" ]; then
            MULTI_ARGS+=(--draeger-serial "$DRAEGER_SERIAL" --draeger-device-id "$DRAEGER_DEVICE_ID")
            echo "Draeger serial: $DRAEGER_SERIAL"
        fi
        exec devices/multidevice/build/install/multidevice/bin/multidevice \
            "${MULTI_ARGS[@]}" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
        ;;
    *)
        echo "ERROR: No device specified. Run ./run.sh --help"
        exit 1
        ;;
esac
