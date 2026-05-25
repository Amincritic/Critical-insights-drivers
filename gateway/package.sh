#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — Build runtime package
#
# Creates a minimal deployment bundle (~3.5MB) containing only the
# pre-built JARs, launch scripts, and tools needed to run on an edge device.
# No Gradle, no source code, no build tools.
#
# Usage:
#   ./package.sh                    # outputs to ../openice-gateway-runtime/
#   ./package.sh /path/to/output    # custom output directory

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

DEST="${1:-$(dirname "$SCRIPT_DIR")/openice-gateway-runtime}"

# --- Detect JAVA_HOME ---
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
else
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

echo "=== Building Runtime Package ==="
echo "Source:  $SCRIPT_DIR"
echo "Output:  $DEST"
echo ""

# --- Build if needed ---
if [ ! -d runtime/build/install ]; then
    echo "[1/4] Building project..."
    export GRADLE_OPTS="-Xmx512m -Xms256m"
    ./gradlew clean build -x test --quiet 2>&1 | grep -v "Fatal Error\|doctype" || true
    ./gradlew :devices:philips-intellivue:installDist :devices:draeger-medibus:installDist :runtime:installDist --quiet 2>&1 | grep -v "Fatal Error\|doctype" || true
else
    echo "[1/4] Build already exists, skipping (run ./gradlew clean to rebuild)"
fi

# --- Create output directory ---
echo "[2/4] Creating runtime package..."
rm -rf "$DEST"
mkdir -p "$DEST/bin" "$DEST/lib" "$DEST/test-tools" "$DEST/deploy"

# Copy JARs (gateway-runtime includes all device JARs)
cp runtime/build/install/gateway-runtime/lib/* "$DEST/lib/"

# Copy launchers
cp runtime/build/install/gateway-runtime/bin/gateway-runtime "$DEST/bin/"
cp devices/draeger-medibus/build/install/draeger/bin/draeger "$DEST/bin/"
cp devices/philips-intellivue/build/install/philips/bin/philips "$DEST/bin/"
cp devices/philips-intellivue/build/install/philips/bin/philips-serial "$DEST/bin/"
chmod +x "$DEST/bin/"*

# --- Copy scripts and config ---
echo "[3/4] Copying scripts and config..."

# Deployment files
cp openice-gateway-runtime.service "$DEST/"
cp deploy/deploy.sh deploy/logrotate-openice "$DEST/deploy/"

# Documentation
cp ARCHITECTURE.md README.md FIXES_APPLIED.md "$DEST/"

# Test tools (source + compile)
cp test-tools/FakeDraegerDevice.java test-tools/FakeDraegerSerial.java test-tools/PortDetector.java "$DEST/test-tools/"
"$JAVA_HOME/bin/javac" "$DEST/test-tools/FakeDraegerDevice.java" \
                       "$DEST/test-tools/FakeDraegerSerial.java" \
                       "$DEST/test-tools/PortDetector.java"

# Copy hil-test.sh (fix paths for runtime layout)
cp hil-test.sh "$DEST/"
chmod +x "$DEST/hil-test.sh"

# --- Create runtime run.sh ---
cat > "$DEST/run.sh" << 'RUNEOF'
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
else
    export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
fi

if ! command -v java >/dev/null 2>&1 && [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "ERROR: Java 17 not found. Install: sudo apt install openjdk-17-jdk"
    exit 1
fi

if [ ! -f bin/draeger ] && [ ! -f bin/gateway-runtime ]; then
    echo "ERROR: Launchers not found in bin/."
    exit 1
fi

show_help() {
    cat <<'HELP'
OpenICE Headless JSON Gateway — Runtime

Usage:
  ./run.sh [mode] [options]

Modes:
  --auto                               Auto-detect devices on COM ports
  --draeger-serial <port>              Draeger ventilator over RS-232
  --draeger-tcp <host:port>            Draeger ventilator over TCP
  --philips-serial <port>              Philips MX800 over MIB/RS232
  --philips-host <ip>                  Philips monitor over LAN/UDP
  --multi                              Both devices (requires device flags)

Options:
  --gateway-id <id>                    default: air021_01
  --bed-id <id>                        default: bed_01
  --device-id <id>                     default: per device type
  --jsonl <path>                       Write JSON lines to file
  --stdout                             Print JSON to terminal
  --http-url <url>                     POST JSON to HTTP endpoint
  --http-header 'Name: value'          Add HTTP header
  --help                               Show this help

Examples:
  ./run.sh --auto --stdout
  ./run.sh --draeger-serial /dev/ttyS1 --stdout
  ./run.sh --multi --philips-serial /dev/ttyS0 --draeger-serial /dev/ttyS1 \
           --jsonl /var/log/openice/bed01.jsonl --stdout
HELP
    exit 0
}

GATEWAY_ID="air021_01"; BED_ID="bed_01"; MODE=""; AUTO_DETECT=false
DRAEGER_SERIAL=""; DRAEGER_TCP_HOST=""; DRAEGER_TCP_PORT=""
PHILIPS_SERIAL=""; PHILIPS_HOST=""
JSONL=""; STDOUT_FLAG=""; HTTP_URL=""; HTTP_HEADERS=(); EXTRA_ARGS=()
DRAEGER_DEVICE_ID="draeger_vent_01"; PHILIPS_DEVICE_ID="philips_mx800_01"

if [ $# -eq 0 ]; then show_help; fi

while [ $# -gt 0 ]; do
    case "$1" in
        --help) show_help ;;
        --auto) AUTO_DETECT=true; shift ;;
        --multi) MODE="multi"; shift ;;
        --draeger-serial) DRAEGER_SERIAL="$2"; MODE="${MODE:-draeger}"; shift 2 ;;
        --draeger-tcp) IFS=':' read -r DRAEGER_TCP_HOST DRAEGER_TCP_PORT <<< "$2"; MODE="${MODE:-draeger-tcp}"; shift 2 ;;
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

OUTPUT_ARGS=()
if [ -n "$JSONL" ]; then OUTPUT_ARGS+=(--jsonl "$JSONL"); fi
if [ -n "$STDOUT_FLAG" ]; then OUTPUT_ARGS+=(--stdout true); fi
if [ -n "$HTTP_URL" ]; then OUTPUT_ARGS+=(--http-url "$HTTP_URL"); fi
for h in "${HTTP_HEADERS[@]+"${HTTP_HEADERS[@]}"}"; do OUTPUT_ARGS+=(--http-header "$h"); done
if [ -z "$JSONL" ] && [ -z "$STDOUT_FLAG" ] && [ -z "$HTTP_URL" ]; then OUTPUT_ARGS+=(--stdout true); fi

if [ "$AUTO_DETECT" = true ]; then
    echo "=== Auto-detecting devices on serial ports ==="
    echo ""
    if [ ! -f test-tools/PortDetector.class ] || [ test-tools/PortDetector.java -nt test-tools/PortDetector.class ]; then
        "$JAVA_HOME/bin/javac" test-tools/PortDetector.java
    fi
    DETECT_OUTPUT=$("$JAVA_HOME/bin/java" -cp test-tools PortDetector 2>/dev/tty || true)
    if [ -z "$DETECT_OUTPUT" ]; then echo "ERROR: No devices detected."; exit 1; fi
    DETECTED_DRAEGER=$(echo "$DETECT_OUTPUT" | grep "^DRAEGER=" | cut -d= -f2 || true)
    DETECTED_PHILIPS=$(echo "$DETECT_OUTPUT" | grep "^PHILIPS=" | cut -d= -f2 || true)
    if [ -n "$DETECTED_DRAEGER" ] && [ -n "$DETECTED_PHILIPS" ]; then
        MODE="multi"; DRAEGER_SERIAL="$DETECTED_DRAEGER"; PHILIPS_SERIAL="$DETECTED_PHILIPS"
        echo "Auto-detected: Draeger=$DRAEGER_SERIAL Philips=$PHILIPS_SERIAL"
    elif [ -n "$DETECTED_DRAEGER" ]; then
        MODE="draeger"; DRAEGER_SERIAL="$DETECTED_DRAEGER"; echo "Auto-detected: Draeger=$DRAEGER_SERIAL"
    elif [ -n "$DETECTED_PHILIPS" ]; then
        MODE="philips-serial"; PHILIPS_SERIAL="$DETECTED_PHILIPS"; echo "Auto-detected: Philips=$PHILIPS_SERIAL"
    else echo "ERROR: Could not identify devices."; exit 1; fi
    echo ""
fi

echo "=== OpenICE Gateway ==="
echo "Mode: $MODE | Gateway: $GATEWAY_ID | Bed: $BED_ID"
echo ""

case "$MODE" in
    draeger)
        exec bin/draeger --serial "$DRAEGER_SERIAL" --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" --device-id "$DRAEGER_DEVICE_ID" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" ;;
    draeger-tcp)
        exec bin/draeger --tcp-host "$DRAEGER_TCP_HOST" --tcp-port "$DRAEGER_TCP_PORT" --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" --device-id "$DRAEGER_DEVICE_ID" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" ;;
    philips-serial)
        exec bin/philips-serial --serial "$PHILIPS_SERIAL" --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" --device-id "$PHILIPS_DEVICE_ID" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" ;;
    philips-udp)
        exec bin/philips --host "$PHILIPS_HOST" --gateway-id "$GATEWAY_ID" --bed-id "$BED_ID" --device-id "$PHILIPS_DEVICE_ID" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" ;;
    multi)
        MULTI_ARGS=(--gateway-id "$GATEWAY_ID" --bed-id "$BED_ID")
        [ -n "$PHILIPS_SERIAL" ] && MULTI_ARGS+=(--philips-serial "$PHILIPS_SERIAL" --philips-device-id "$PHILIPS_DEVICE_ID")
        [ -n "$PHILIPS_HOST" ] && MULTI_ARGS+=(--philips-host "$PHILIPS_HOST" --philips-device-id "$PHILIPS_DEVICE_ID")
        [ -n "$DRAEGER_SERIAL" ] && MULTI_ARGS+=(--draeger-serial "$DRAEGER_SERIAL" --draeger-device-id "$DRAEGER_DEVICE_ID")
        exec bin/gateway-runtime "${MULTI_ARGS[@]}" "${OUTPUT_ARGS[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" ;;
    *) echo "ERROR: No device specified. Run ./run.sh --help"; exit 1 ;;
esac
RUNEOF
chmod +x "$DEST/run.sh"

# --- Report ---
echo "[4/4] Done"
echo ""
SIZE=$(du -sh "$DEST" | cut -f1)
echo "=== Runtime package ready ==="
echo "Location: $DEST"
echo "Size:     $SIZE"
echo ""
echo "Contents:"
echo "  bin/           Launcher scripts (draeger, philips, philips-serial, gateway-runtime)"
echo "  lib/           JAR files (all dependencies)"
echo "  test-tools/    Port detector, fake device simulators"
echo "  deploy/        deploy.sh, logrotate config"
echo "  run.sh         Main entry point"
echo ""
echo "Deploy to edge device:"
echo "  scp -r $DEST user@air021:/opt/openice/"
echo "  ssh user@air021 'cd /opt/openice && ./run.sh --auto --stdout'"
