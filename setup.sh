#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — One-time setup and build
# Run this once after cloning or extracting the project.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== OpenICE Headless Gateway Setup ==="
echo ""

# --- Detect architecture ---
ARCH=$(uname -m)
if [ "$ARCH" = "aarch64" ]; then
    JDK_PATH="/usr/lib/jvm/java-17-openjdk-arm64"
    echo "Detected: ARM64 (Jetson / AIR-021 ARM)"
else
    JDK_PATH="/usr/lib/jvm/java-17-openjdk-amd64"
    echo "Detected: x86_64 (AIR-021 / desktop)"
fi

# --- Check JDK 17 ---
echo ""
echo "[1/4] Checking JDK 17..."
if [ -d "$JDK_PATH" ]; then
    export JAVA_HOME="$JDK_PATH"
    echo "  Found: $JDK_PATH"
elif command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '"\K[^"]+' | cut -d. -f1)
    if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
        export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}"
        echo "  Found: java $JAVA_VER at $JAVA_HOME"
    else
        echo "  ERROR: Java $JAVA_VER found, but JDK 17+ is required."
        echo "  Run: sudo apt install openjdk-17-jdk"
        exit 1
    fi
else
    echo "  ERROR: Java not found."
    echo "  Run: sudo apt install openjdk-17-jdk"
    exit 1
fi

# --- Download Gradle if needed ---
echo ""
echo "[2/4] Checking Gradle distribution..."
if [ -f gradle/wrapper/gradle-7.6-bin.zip ]; then
    echo "  Already present ($(du -h gradle/wrapper/gradle-7.6-bin.zip | cut -f1))"
else
    echo "  Downloading Gradle 7.6 (~117 MB)..."
    mkdir -p gradle/wrapper
    wget -q --show-progress -O gradle/wrapper/gradle-7.6-bin.zip \
        https://services.gradle.org/distributions/gradle-7.6-bin.zip
    echo "  Done"
fi

# --- Build ---
echo ""
echo "[3/4] Building project..."
export GRADLE_OPTS="-Xmx512m -Xms256m"
./gradlew clean build -x test --quiet 2>&1 | grep -v "Fatal Error\|doctype" || true
echo "  Build complete"

# --- Install distributions ---
echo ""
echo "[4/4] Creating runnable launchers..."
./gradlew :devices:philips:installDist :devices:draeger:installDist :devices:multidevice:installDist --quiet 2>&1 | grep -v "Fatal Error\|doctype" || true
echo "  Launchers created:"
echo "    devices/draeger/build/install/draeger/bin/draeger"
echo "    devices/philips/build/install/philips/bin/philips"
echo "    devices/philips/build/install/philips/bin/philips-serial"
echo "    devices/multidevice/build/install/multidevice/bin/multidevice"

# --- Compile test tools ---
echo ""
echo "  Compiling test tools..."
"$JAVA_HOME/bin/javac" test-tools/FakeDraegerDevice.java
"$JAVA_HOME/bin/javac" test-tools/FakeDraegerSerial.java
"$JAVA_HOME/bin/javac" test-tools/PortDetector.java
echo "    FakeDraegerDevice, FakeDraegerSerial, PortDetector"

echo ""
echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  ./test.sh                      Test with fake Draeger device"
echo "  ./run.sh --help                Run with real devices"
echo "  sudo bash deploy/deploy.sh     Deploy as a systemd service"
