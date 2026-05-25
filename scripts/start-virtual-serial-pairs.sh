#!/bin/bash
set -euo pipefail

if ! command -v socat >/dev/null 2>&1; then
    echo "ERROR: socat is required to create virtual serial ports."
    echo "Install on macOS: brew install socat"
    echo "Install on Debian/Ubuntu: sudo apt install socat"
    exit 1
fi

cleanup_links() {
    rm -f /tmp/philips-device /tmp/philips-gateway /tmp/draeger-device /tmp/draeger-gateway
}

cleanup_links

socat -d -d pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/philips-device pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/philips-gateway &
PHILIPS_PID=$!

socat -d -d pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/draeger-device pty,raw,echo=0,ixon=0,ixoff=0,link=/tmp/draeger-gateway &
DRAEGER_PID=$!

echo "Virtual serial pairs started:"
echo "  Philips simulator: /tmp/philips-device"
echo "  Philips gateway:   /tmp/philips-gateway"
echo "  Draeger simulator: /tmp/draeger-device"
echo "  Draeger gateway:   /tmp/draeger-gateway"
echo ""
sleep 1

configure_pty() {
    local link="$1"
    local path
    path="$(ls -l "$link" | sed 's/.* -> //')"
    if command -v stty >/dev/null 2>&1 && [ -n "$path" ]; then
        stty -f "$path" raw -echo -ixon -ixoff 2>/dev/null || true
    fi
}

configure_pty /tmp/philips-device
configure_pty /tmp/philips-gateway
configure_pty /tmp/draeger-device
configure_pty /tmp/draeger-gateway

echo "Resolved device paths:"
ls -l /tmp/philips-device /tmp/philips-gateway /tmp/draeger-device /tmp/draeger-gateway | sed 's/^/  /'
echo ""
echo "On macOS, if the gateway rejects the /tmp link, use the resolved /dev/ttys* path shown above."
echo ""
echo "Keep this terminal open. Stop with Ctrl-C."

trap 'kill "$PHILIPS_PID" "$DRAEGER_PID" >/dev/null 2>&1 || true; cleanup_links' INT TERM EXIT
wait
