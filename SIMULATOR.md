# Device Simulators

Spec-compliant simulators for testing the gateway without real hardware. All simulators are in the `test-tools/` directory.

## Quick reference

| Simulator | Command | Transport |
|-----------|---------|-----------|
| **CriticalInsightsMonitor** (GUI) | `java -cp test-tools CriticalInsightsMonitor` | Both Draeger TCP + Philips UDP |
| MedibusSimulatorV2 (CLI) | `java -cp test-tools MedibusSimulatorV2 --tcp 9100` | Draeger MEDIBUS over TCP |
| IntellivueSimulatorV2 (CLI) | `java -cp test-tools IntellivueSimulatorV2 --port 24105` | Philips IntelliVue over UDP |
| IntellivueSerialSimulator (CLI) | `java -cp test-tools IntellivueSerialSimulator --serial /tmp/philips-device` | Philips MIB/RS232 over serial |
| MedibusSimulatorV2 (serial) | `java -cp test-tools MedibusSimulatorV2 --serial /tmp/draeger-device` | Draeger MEDIBUS over serial |

---

## Compile

```bash
# All simulators at once
javac test-tools/CriticalInsightsMonitor.java \
      test-tools/MedibusSimulatorV2.java \
      test-tools/IntellivueSimulatorV2.java \
      test-tools/IntellivueSerialSimulator.java
```

Or use `./setup.sh` which compiles everything automatically.

---

## CriticalInsightsMonitor (primary GUI)

A dark patient-monitor GUI with real-time waveforms, adjustable sliders, alarm triggers, and clinical scenarios. Runs both Draeger and Philips simulators in one window.

### Launch

```bash
java -cp test-tools CriticalInsightsMonitor
```

### Features

- **Dark patient monitor UI** with waveform displays (ECG-style traces, pressure curves)
- **Draeger controls**: tidal volume, respiratory rate, peak pressure, PEEP, FiO2, compliance, resistance, airway temperature
- **Philips controls**: heart rate, SpO2, respiratory rate, ABP (sys/dia), NBP (sys/dia), etCO2, temperature
- **Alarm triggers**: PAW HIGH, APNEA, %O2 HIGH, MV LOW, HR HIGH, SpO2 LOW, ABP HIGH
- **Clinical scenarios**: preset combinations for common clinical situations
- **Noise injection**: toggle realistic signal noise
- **Protocol log**: view every command/response exchanged

### Connect the gateway

```bash
# Both devices from the GUI
./run.sh --multi --philips-host 127.0.0.1 --draeger-tcp 127.0.0.1:9100 --stdout
```

### With web dashboard

```bash
# Start the GUI simulator
java -cp test-tools CriticalInsightsMonitor

# Connect gateway with web dashboard
./run.sh --multi --philips-host 127.0.0.1 --draeger-tcp 127.0.0.1:9100 \
  --stdout compact --web-port 8080

# Open http://localhost:8080 in a browser
```

---

## CLI simulators

### MedibusSimulatorV2 (Draeger MEDIBUS)

Follows the Draeger RS 232 MEDIBUS Protocol Definition (Rev 6.00). Supports ICC handshake, ASCII HEX checksums, measured data, alarms, device settings, text messages, and multiple device models.

```bash
# Default (Evita on port 9100)
java -cp test-tools MedibusSimulatorV2 --tcp 9100

# Specific device model
java -cp test-tools MedibusSimulatorV2 --tcp 9100 --model v500
java -cp test-tools MedibusSimulatorV2 --tcp 9100 --model fabius
java -cp test-tools MedibusSimulatorV2 --tcp 9100 --model savina
```

Connect the gateway:

```bash
./run.sh --draeger-tcp 127.0.0.1:9100 --stdout
```

Available models:

| Model | ID | Name |
|-------|----|------|
| evita | 8210 | Evita |
| evita2 | 8200 | Evita 2 |
| evita4 | 8214 | Evita 4 |
| v500 | 8410 | Evita V500 |
| savina | 8310 | Savina |
| fabius | 8088 | Fabius GS |

Protocol features: ICC handshake (0x51), measured data CP1/CP2 with correct data codes, alarm messages with priority/code/phrase, device identification, DateTime (German months), device settings, text messages, NAK for corrupt checksums, DC1/DC3 flow control, realistic physiological variation over time.

### IntellivueSimulatorV2 (Philips IntelliVue)

Follows the Philips IntelliVue Data Export Interface Programming Guide (Rev G.0).

```bash
java -cp test-tools IntellivueSimulatorV2 --port 24105
```

Connect the gateway:

```bash
./run.sh --philips-host 127.0.0.1 --stdout
```

Protocol features: UDP transport, Association Request/Response handshake, MDS Create Event (Philips IntelliVue MX800), Extended Poll Data Result with FLOAT-Type numerics, big-endian encoding, 12 simulated vitals (HR, SpO2, Pulse Rate, RR, ABP sys/dia/mean, NBP sys/dia/mean, etCO2, Temperature).

### IntellivueSerialSimulator (Philips MIB/RS232)

Simulates a Philips MX800 connected over the MIB/RS232 serial port using the Fixed Baudrate Protocol.

Requires `socat` for virtual serial ports:

```bash
sudo apt install socat
```

Step by step:

```bash
# Terminal 1 -- create virtual serial port pair
socat -d -d \
  pty,raw,echo=0,link=/tmp/philips-device \
  pty,raw,echo=0,link=/tmp/philips-gateway &

# Terminal 2 -- start simulator
java -cp test-tools IntellivueSerialSimulator --serial /tmp/philips-device

# Terminal 3 -- connect gateway
./run.sh --philips-serial /tmp/philips-gateway --stdout
```

Uses the same Data Export protocol as UDP but wrapped in serial framing: `BOF(0xC0) | Header | User Data | FCS(CRC-CCITT) | EOF(0xC1)` with byte escaping (0x7D + XOR 0x20).

---

## Both devices together

### CLI simulators (TCP/UDP)

```bash
# Terminal 1 -- Draeger simulator
java -cp test-tools MedibusSimulatorV2 --tcp 9100

# Terminal 2 -- Philips simulator
java -cp test-tools IntellivueSimulatorV2 --port 24105

# Terminal 3 -- gateway receives from both
./run.sh --multi --philips-host 127.0.0.1 --draeger-tcp 127.0.0.1:9100 --stdout
```

### CLI simulators (serial -- closest to real hardware)

```bash
# Terminal 1 -- create two virtual serial pairs
socat -d -d pty,raw,echo=0,link=/tmp/draeger-device pty,raw,echo=0,link=/tmp/draeger-gateway &
socat -d -d pty,raw,echo=0,link=/tmp/philips-device pty,raw,echo=0,link=/tmp/philips-gateway &

# Terminal 2 -- Draeger serial simulator
java -cp test-tools MedibusSimulatorV2 --serial /tmp/draeger-device

# Terminal 3 -- Philips serial simulator
java -cp test-tools IntellivueSerialSimulator --serial /tmp/philips-device

# Terminal 4 -- gateway (both devices over serial)
devices/multidevice/build/install/multidevice/bin/multidevice \
  --gateway-id test --bed-id test \
  --philips-serial /tmp/philips-gateway --philips-device-id sim_philips \
  --draeger-serial /tmp/draeger-gateway --draeger-device-id sim_draeger \
  --stdout true
```

This is the closest simulation to a real AIR-021 setup -- both devices communicating over serial ports with full protocol framing.

---

## Web dashboard with simulators

Any simulator setup works with the web dashboard. Add `--web-port 8080` to the gateway command:

```bash
# Draeger only
./run.sh --draeger-tcp 127.0.0.1:9100 --stdout compact --web-port 8080

# Both devices
./run.sh --multi --philips-host 127.0.0.1 --draeger-tcp 127.0.0.1:9100 --web-port 8080

# Then open http://localhost:8080 in a browser
```

---

## Automated testing

### Quick test (Draeger TCP)

```bash
./test.sh              # 30 seconds on default port
./test.sh 9100 60      # 60 seconds on port 9100
```

Starts a Draeger simulator and gateway automatically, reports events captured.

### HIL test (virtual serial ports)

Tests the full serial path -- port open/close, MEDIBUS framing, reconnection -- without physical devices.

```bash
sudo apt install socat

./hil-test.sh              # 30 seconds, Draeger only
./hil-test.sh --duration 120
./hil-test.sh --multi      # Both devices
./hil-test.sh --loopback   # With real loopback cables
```

How it works:

```
┌──────────────────┐     socat PTY pair     ┌──────────────────┐
│ Simulator         │ ←── /dev/pts/X ──────→ │  Gateway         │
│ (MEDIBUS frames)  │    /dev/pts/Y          │  (--serial mode) │
└──────────────────┘     virtual RS-232      └──────────────────┘
```

Results are written to `/tmp/hil-test-output.jsonl` and reported at the end (total events, vitals, errors).

### Save output to file

```bash
./run.sh --draeger-tcp 127.0.0.1:9100 --jsonl /tmp/sim-output.jsonl --stdout
```

---

## Troubleshooting

**"Address already in use":**
Another process is using the port. Use a different port:
```bash
java -cp test-tools MedibusSimulatorV2 --tcp 9200
./run.sh --draeger-tcp 127.0.0.1:9200 --stdout
```

**GUI does not open:**
Over SSH, use X forwarding (`ssh -X user@host`), or use the CLI simulators instead.

**Gateway does not connect:**
- Check that port numbers match between simulator and gateway
- For Philips: gateway and simulator must be on the same machine or network
- For Draeger TCP: ensure no firewall blocks the port

**Values do not change in gateway output:**
Slider changes take effect on the next poll cycle (every 1 second). Check the Protocol Log tab in CriticalInsightsMonitor to confirm commands are being received.
