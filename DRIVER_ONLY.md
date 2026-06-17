# Driver-Only Edge Deployment

This guide is for running only the Critical Insights protocol drivers on an edge device. It does not use the simulator GUI, protocol simulators, or browser dashboard.

Use this path when an edge box should:

- connect to Philips or Draeger bedside devices
- decode the native device protocol
- publish canonical JSON events to JSONL, stdout, or HTTP

## 1. Requirements

- Linux edge device or development machine
- Java 17 installed
- Bash-compatible shell
- Network access or serial ports for the target devices
- Write access to the output directory, for example `/var/log/critical-insights`

For RS232 deployments, the runtime user must have access to the serial device:

```bash
sudo usermod -a -G dialout "$USER"
```

Log out and back in after changing group membership.

## 2. Build The Driver Runtime

From the directory that contains the repository checkout:

```bash
cd critical-insights
cd gateway
./gradlew :runtime:installDist
cd ..
```

The normal entry point is the top-level wrapper:

```bash
./scripts/run-gateway.sh --help
```

## 3. Create An Output Directory

For JSONL output:

```bash
sudo mkdir -p /var/log/critical-insights
sudo chown "$USER":"$USER" /var/log/critical-insights
```

Each output line is one canonical JSON event.

## 4. Run One Philips Driver

Philips MIB/RS232:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --philips-baud 115200 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id philips_mx800_01 \
  --jsonl /var/log/critical-insights/philips_mx800_01.jsonl \
  --output-format canonical
```

Philips LAN/UDP:

```bash
./scripts/run-gateway.sh --philips-host 192.168.10.50 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id philips_mx800_01 \
  --jsonl /var/log/critical-insights/philips_mx800_01.jsonl \
  --output-format canonical
```

Use `--philips-baud 19200` only when the monitor MIB/RS232 port is configured for 19200 fixed-baudrate mode. The default Philips fixed-baudrate setting is usually `115200`, 8 data bits, no parity, 1 stop bit, no flow control.

## 5. Run One Draeger Driver

Draeger RS232:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

Draeger TCP:

```bash
./scripts/run-gateway.sh --draeger-tcp 192.168.10.60:4001 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --device-id draeger_vent_01 \
  --jsonl /var/log/critical-insights/draeger_vent_01.jsonl \
  --output-format canonical
```

Draeger profiles:

| Profile | Use case |
| --- | --- |
| `v500` | Default V500-style profile. |
| `intensive-care` | Intensive-care MEDIBUS family. |
| `evita` | Conservative Evita profile. |
| `savina` | Savina profile. |
| `fabius` | Fabius GS/Tiro profile. |

Default Draeger serial settings are `19200` baud, 8 data bits, even parity, 1 stop bit, no flow control.

## 6. Run Philips And Draeger Together

Use `--multi` when one edge device connects to more than one bedside device:

```bash
./scripts/run-gateway.sh --multi \
  --philips-serial /dev/ttyS0 \
  --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --jsonl /var/log/critical-insights/bed_01.jsonl \
  --output-format canonical
```

Each event includes `unique_device_identifier`, `gateway_id`, and `bed_id`.

## 7. Send Events To HTTP

Use `--http-url` to POST each canonical event to another service:

```bash
./scripts/run-gateway.sh --draeger-serial /dev/ttyS1 \
  --draeger-profile v500 \
  --gateway-id air021_01 \
  --bed-id bed_01 \
  --http-url https://ingest.example.internal/device-events \
  --http-header 'Authorization: Bearer YOUR_TOKEN' \
  --output-format canonical
```

You can combine JSONL and HTTP output:

```bash
./scripts/run-gateway.sh --philips-serial /dev/ttyS0 \
  --jsonl /var/log/critical-insights/philips_mx800_01.jsonl \
  --http-url https://ingest.example.internal/device-events \
  --output-format canonical
```

## 8. Verify Data Is Flowing

For JSONL:

```bash
tail -f /var/log/critical-insights/bed_01.jsonl
```

For a quick terminal check:

```bash
./scripts/run-gateway.sh --draeger-tcp 192.168.10.60:4001 \
  --draeger-profile v500 \
  --stdout compact \
  --output-format canonical
```

Expected canonical topics include:

| Topic | Meaning |
| --- | --- |
| `DeviceConnectivity` | Driver and transport state. |
| `DeviceIdentity` | Device identity metadata. |
| `Numeric` | Scalar observations. |
| `SampleArray` | Waveforms. |
| `PatientAlert` | Patient alarms. |
| `TechnicalAlert` | Technical/device alarms. |
| `DeviceSetting` | Ventilator settings, mainly Draeger. |
| `TextMessage` | Device status text, mainly Draeger. |

## 9. Run As A systemd Service

Create a runtime user:

```bash
sudo useradd --system --home /opt/critical-insights --shell /usr/sbin/nologin critical-insights
sudo usermod -a -G dialout critical-insights
sudo mkdir -p /var/log/critical-insights
sudo chown critical-insights:critical-insights /var/log/critical-insights
```

Example service for a single Draeger RS232 driver:

```ini
[Unit]
Description=Critical Insights Draeger Driver
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/critical-insights
ExecStart=/opt/critical-insights/scripts/run-gateway.sh --draeger-serial /dev/ttyS1 --draeger-profile v500 --gateway-id air021_01 --bed-id bed_01 --device-id draeger_vent_01 --jsonl /var/log/critical-insights/draeger_vent_01.jsonl --output-format canonical
Restart=always
RestartSec=5
User=critical-insights
Group=critical-insights

[Install]
WantedBy=multi-user.target
```

Install it:

```bash
sudo cp critical-insights-draeger.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now critical-insights-draeger.service
sudo systemctl status critical-insights-draeger.service
```

View logs:

```bash
journalctl -u critical-insights-draeger.service -f
```

## 10. Troubleshooting

| Symptom | Check |
| --- | --- |
| `Permission denied` on `/dev/ttyS*` | Add the runtime user to `dialout`, then log out and back in. |
| No JSONL file appears | Confirm the output directory exists and is writable. |
| JSONL exists but has only connectivity events | Confirm the device is connected, configured for data export, and using the expected serial/network settings. |
| Philips serial has no data | Confirm MIB/RS232 Data Out is enabled and baudrate matches `--philips-baud`. |
| Draeger serial has no data | Confirm null-modem/crossover cable, parity, baudrate, and `--draeger-profile`. |
| TCP connection fails | Confirm IP, port, firewall, and serial-to-Ethernet adapter settings. |
| HTTP output fails | Confirm the receiver returns 2xx and accepts `application/json`. |

## More Detail

- Philips-specific guide: `gateway/devices/philips-intellivue/EDGE.md`
- Draeger-specific guide: `gateway/devices/draeger-medibus/EDGE.md`
- Edge deployment reference: `docs/edge-driver-deployment.md`
- Event schema: `docs/canonical-schema.md`
- Hardware notes: `gateway/HARDWARE.md`
