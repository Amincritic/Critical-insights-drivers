# Device Simulators

This project includes three ways to simulate Draeger ventilators and Philips patient monitors without real hardware.

## Quick Reference

| Simulator | Command | Protocol |
|-----------|---------|----------|
| GUI (both devices) | `java -cp test-tools SimulatorGui` | Interactive sliders + alarm triggers |
| Draeger CLI | `java -cp test-tools MedibusSimulator --tcp 9100` | MEDIBUS Rev 6.00 compliant |
| Philips CLI | `java -cp test-tools IntellivueSimulator --port 24105` | IntelliVue Rev G.0 compliant |
| Basic test | `./test.sh` | Automated 30-second Draeger test |

## Compile (once)

```bash
javac test-tools/SimulatorGui.java
javac test-tools/MedibusSimulator.java
javac test-tools/IntellivueSimulator.java
```

Or compile all at once:

```bash
javac test-tools/SimulatorGui.java test-tools/MedibusSimulator.java test-tools/IntellivueSimulator.java
```

---

## GUI Simulator

The GUI provides real-time control over simulated vital signs with sliders, alarm triggers, and a protocol log.

### Launch

```bash
java -cp test-tools SimulatorGui
```

### Layout

```
┌─────────────────────────────────────────────────────────┐
│  Simulator Controls                                     │
│  Draeger TCP Port: [9100]  Model: [evita▼]  [Start] [Stop] │
│  Philips UDP Port: [24105]                  [Start] [Stop] │
├─────────────────────────────────────────────────────────┤
│  [Draeger MEDIBUS] [Philips IntelliVue] [Protocol Log]  │
│                                                         │
│  Live Values          │  Adjust Values                  │
│  ─────────────────    │  ──────────────────              │
│  Tidal Volume: 450 mL │  Tidal Vol (mL) ──●──────── 450 │
│  Resp Rate:    16 /min │  Resp Rate      ────●────── 16  │
│  Peak Pressure: 22 mbar│  Peak Pres      ──────●──── 22  │
│  PEEP:         5 mbar  │  PEEP           ──●──────── 5   │
│  FiO2:         40 %    │  FiO2           ────────●── 40  │
│  ...                   │  ...                            │
│                                                         │
│  Trigger Alarms                                         │
│  [✓] PAW HIGH  [ ] % O2 HIGH  [ ] MIN VOL LOW  [ ] APNEA │
├─────────────────────────────────────────────────────────┤
│  Draeger: Connected: /127.0.0.1:54321  │  Philips: No connection │
└─────────────────────────────────────────────────────────┘
```

### Step by step

**1. Start the Draeger simulator:**
- Set the TCP port (default: 9100)
- Select a device model from the dropdown (evita, evita4, v500, savina, fabius)
- Click **Start**
- Status shows "Running" in green

**2. Connect the gateway:**
```bash
./run.sh --draeger-tcp 127.0.0.1:9100 --stdout
```

**3. Adjust vitals:**
- Drag the **Tidal Volume** slider from 450 to 600 — the gateway output updates on the next poll
- Drag **PEEP** from 5 to 12 — reflected immediately
- All changes take effect on the next 1-second poll cycle

**4. Trigger alarms:**
- Check **PAW HIGH** — the alarm appears in the gateway's alarm response
- Check **APNEA** — appears as alarm code 0x98 with phrase "APNEA EVITA"
- Uncheck to clear the alarm

**5. Start the Philips simulator:**
- Click **Start** on the Philips row
- Connect: `./run.sh --philips-host 127.0.0.1 --stdout`
- Adjust HR, SpO2, blood pressure with sliders

**6. View protocol log:**
- Click the **Protocol Log** tab
- See every command/response exchanged
- Click **Clear Log** to reset

### Draeger tab — available controls

| Slider | Range | Default | Unit |
|--------|-------|---------|------|
| Tidal Volume | 200–800 | 450 | mL |
| Resp Rate | 5–40 | 16 | /min |
| Peak Pressure | 5–50 | 22 | mbar |
| PEEP | 0–20 | 5 | mbar |
| FiO2 | 21–100 | 40 | % |
| Compliance | 10–100 | 45 | L/bar |
| Resistance | 3–40 | 12 | mbar/L/s |
| Airway Temp | 28–40 | 34 | °C |

| Alarm | Code | Priority | Phrase |
|-------|------|----------|--------|
| PAW HIGH | 0x10 | 27 | PAW HIGH |
| % O2 HIGH | 0x37 | 23 | % O2 HIGH |
| MIN VOL LOW | 0x19 | 26 | MIN VOL LOW |
| APNEA | 0x98 | 27 | APNEA EVITA |

### Philips tab — available controls

| Slider | Range | Default | Unit |
|--------|-------|---------|------|
| Heart Rate | 30–200 | 72 | bpm |
| SpO2 | 70–100 | 97 | % |
| Resp Rate | 5–40 | 16 | /min |
| ABP Systolic | 60–220 | 120 | mmHg |
| ABP Diastolic | 30–130 | 80 | mmHg |
| NBP Systolic | 60–220 | 118 | mmHg |
| NBP Diastolic | 30–130 | 76 | mmHg |
| etCO2 | 15–65 | 38 | mmHg |
| Temperature | 34.0–41.0 | 37.0 | °C |

ABP Mean and NBP Mean are calculated automatically: `(sys + 2*dia) / 3`

---

## CLI Simulators

### Draeger MEDIBUS Simulator

Spec-compliant simulator following the Dräger RS 232 MEDIBUS Protocol Definition (Rev 6.00).

```bash
# Default (Evita on port 9100)
java -cp test-tools MedibusSimulator --tcp 9100

# Evita V500
java -cp test-tools MedibusSimulator --tcp 9100 --model v500

# Fabius GS
java -cp test-tools MedibusSimulator --tcp 9100 --model fabius

# Over serial/PTY (for HIL testing)
java -cp test-tools MedibusSimulator --serial /tmp/draeger-device
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

Protocol features:
- ICC handshake (0x51) before communication
- Measured data CP1/CP2 with correct data codes
- Alarm messages with priority, code, and 12-char phrase
- Device identification with ID number, quoted name, revision
- DateTime in HH:MM:SS DD-MMM-YY format (German months)
- Device settings response
- Text messages response
- NAK for corrupt checksums
- DC1/DC3 flow control handling
- Vitals vary over time with realistic physiological patterns

### Philips IntelliVue Simulator

Spec-compliant simulator following the Philips IntelliVue Data Export Interface Programming Guide (Rev G.0).

```bash
# Default (port 24105)
java -cp test-tools IntellivueSimulator --port 24105

# Custom port
java -cp test-tools IntellivueSimulator --port 24200
```

Connect the gateway:
```bash
./run.sh --philips-host 127.0.0.1 --stdout
```

Protocol features:
- UDP transport on port 24105
- Association Request/Response handshake
- MDS Create Event with system model info (Philips IntelliVue MX800)
- Extended Poll Data Result with numeric observed values
- FLOAT-Type encoding (8-bit exponent + 24-bit mantissa)
- Big-endian byte order
- 12 simulated vitals: HR, SpO2, Pulse Rate, Resp Rate, ABP (sys/dia/mean), NBP (sys/dia/mean), etCO2, Temperature
- Vitals vary over time with realistic patterns

---

## Both Simulators Together

### With CLI simulators

```bash
# Terminal 1
java -cp test-tools MedibusSimulator --tcp 9100

# Terminal 2
java -cp test-tools IntellivueSimulator --port 24105

# Terminal 3
devices/multidevice/build/install/multidevice/bin/multidevice \
  --gateway-id test --bed-id test \
  --philips-host 127.0.0.1 --philips-device-id sim_philips \
  --draeger-serial NONE \
  --stdout true
```

### With GUI

```bash
# Terminal 1 — launch GUI, click Start on both Draeger and Philips
java -cp test-tools SimulatorGui

# Terminal 2 — connect gateway
devices/multidevice/build/install/multidevice/bin/multidevice \
  --gateway-id test --bed-id test \
  --philips-host 127.0.0.1 --philips-device-id sim_philips \
  --draeger-serial NONE \
  --stdout true
```

---

## Automated Testing

### Quick test (Draeger only)

```bash
./test.sh              # 30 seconds
./test.sh 9100 60      # 60 seconds on port 9100
```

### HIL test (virtual serial ports)

```bash
sudo apt install socat
./hil-test.sh              # 30 seconds
./hil-test.sh --duration 120
```

### Save output to file

```bash
./run.sh --draeger-tcp 127.0.0.1:9100 --jsonl /tmp/sim-output.jsonl --stdout
```

Then inspect:
```bash
tail -f /tmp/sim-output.jsonl
wc -l /tmp/sim-output.jsonl
```

---

## Troubleshooting

### "Address already in use"

Another process is using the port. Either stop it or use a different port:
```bash
java -cp test-tools MedibusSimulator --tcp 9200
./run.sh --draeger-tcp 127.0.0.1:9200 --stdout
```

### GUI doesn't open

Make sure you have a display available. Over SSH, use X forwarding:
```bash
ssh -X user@host
java -cp test-tools SimulatorGui
```

Or use the CLI simulators instead — they work without a display.

### Gateway doesn't connect

- Check the port numbers match between simulator and gateway
- For Philips: the gateway and simulator must be on the same machine or network
- For Draeger TCP: ensure no firewall blocks the port

### Values don't change in gateway output

- Slider changes take effect on the next poll cycle (every 1 second)
- Check the Protocol Log tab to confirm commands are being received
