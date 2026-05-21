#!/bin/bash
set -euo pipefail

# OpenICE Headless JSON Gateway — Production Deployment Script
# Targets: Advantech AIR-021 (x86_64) or Jetson Nano (aarch64)

INSTALL_DIR="/opt/openice-headless"
LOG_DIR="/var/log/openice"
SERVICE_USER="openice"
SERVICE_GROUP="openice"
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== OpenICE Headless Gateway Deployment ==="
echo "Source:  $SCRIPT_DIR"
echo "Install: $INSTALL_DIR"
echo ""

# --- Check prerequisites ---
echo "[1/8] Checking prerequisites..."

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java not found. Install JDK 17:"
    ARCH=$(uname -m)
    if [ "$ARCH" = "aarch64" ]; then
        echo "  sudo apt install openjdk-17-jdk"
        echo "  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64"
    else
        echo "  sudo apt install openjdk-17-jdk"
        echo "  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64"
    fi
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '"\K[^"]+' | cut -d. -f1)
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo "WARNING: Java $JAVA_VER detected. JDK 17+ is required."
    echo "  sudo apt install openjdk-17-jdk"
fi

# --- Create service user ---
echo "[2/8] Creating service user '$SERVICE_USER'..."
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    sudo useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
    echo "  Created user: $SERVICE_USER"
else
    echo "  User already exists"
fi
sudo usermod -aG dialout "$SERVICE_USER" 2>/dev/null || true

# --- Create log directory ---
echo "[3/8] Creating log directory..."
sudo mkdir -p "$LOG_DIR"
sudo chown "$SERVICE_USER:$SERVICE_GROUP" "$LOG_DIR"
sudo chmod 750 "$LOG_DIR"

# --- Build ---
echo "[4/8] Building project..."
cd "$SCRIPT_DIR"

if [ ! -f gradle/wrapper/gradle-7.6-bin.zip ]; then
    echo "  Downloading Gradle 7.6..."
    wget -q -O gradle/wrapper/gradle-7.6-bin.zip \
        https://services.gradle.org/distributions/gradle-7.6-bin.zip
fi

export GRADLE_OPTS="-Xmx512m -Xms256m"
./gradlew clean build -x test --quiet
./gradlew :devices:philips:installDist :devices:draeger:installDist :devices:multidevice:installDist --quiet
echo "  Build successful"

# --- Install ---
echo "[5/8] Installing to $INSTALL_DIR..."
sudo mkdir -p "$INSTALL_DIR"
sudo rsync -a --delete \
    --exclude='.git' \
    --exclude='.gradle' \
    --exclude='deploy' \
    "$SCRIPT_DIR/" "$INSTALL_DIR/"
sudo chown -R root:root "$INSTALL_DIR"
sudo chmod -R a+rX "$INSTALL_DIR"

# --- Install logrotate ---
echo "[6/8] Installing logrotate config..."
sudo cp "$SCRIPT_DIR/deploy/logrotate-openice" /etc/logrotate.d/openice
sudo chown root:root /etc/logrotate.d/openice
sudo chmod 644 /etc/logrotate.d/openice
echo "  Installed /etc/logrotate.d/openice"

# --- Install systemd service ---
echo "[7/8] Installing systemd service..."
sudo cp "$INSTALL_DIR/openice-multidevice.service" /etc/systemd/system/
sudo chmod 644 /etc/systemd/system/openice-multidevice.service
sudo systemctl daemon-reload
echo "  Installed openice-multidevice.service"

# --- Serial port permissions ---
echo "[8/8] Checking serial port access..."
for port in /dev/ttyS0 /dev/ttyS1 /dev/ttyUSB0 /dev/ttyUSB1; do
    if [ -e "$port" ]; then
        echo "  Found: $port ($(ls -la "$port" | awk '{print $4}'))"
    fi
done
echo ""

echo "=== Deployment complete ==="
echo ""
echo "Next steps:"
echo "  1. Edit the service file to match your COM port mapping:"
echo "     sudo nano /etc/systemd/system/openice-multidevice.service"
echo ""
echo "  2. Start the service:"
echo "     sudo systemctl enable openice-multidevice"
echo "     sudo systemctl start openice-multidevice"
echo ""
echo "  3. Monitor:"
echo "     sudo journalctl -u openice-multidevice -f"
echo "     tail -f $LOG_DIR/bed01.jsonl"
echo ""
echo "  4. Check health (once running):"
echo "     sudo systemctl status openice-multidevice"
echo "     ls -la $LOG_DIR/"
