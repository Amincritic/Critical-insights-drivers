# Hardware Requirements and Pin Mapping

## Supported Edge Gateways

### Advantech AIR-021

| Spec | Value |
|------|-------|
| CPU | x86_64 |
| OS | Ubuntu 18.04/20.04 |
| COM Ports | 2x RS-232 (DB-9), typically `/dev/ttyS0`, `/dev/ttyS1` |
| JDK | `openjdk-17-jdk` (amd64) |

### NVIDIA Jetson Nano

| Spec | Value |
|------|-------|
| CPU | ARM Cortex-A57 (aarch64) |
| OS | Ubuntu 18.04 (JetPack) |
| UART | 3x UART on 260-pin SO-DIMM (exposed via carrier board) |
| RAM | 4GB LPDDR4 |
| JDK | `openjdk-17-jdk` (arm64) |

Note: Jetson Nano UARTs are 3.3V TTL level. You need an **RS-232 level shifter** (e.g. MAX3232 breakout board) to connect to medical devices that use RS-232 voltage levels (+/- 3-15V).

---

## Philips MX800 — MIB/RS232 Connection

### Physical Interface

The Philips MX Series MIB/RS232 port uses an **RJ-45 connector** (not DB-9). The interface follows IEEE 1073.3.2.

### MIB/RS232 Pin Assignment (RJ-45)

| RJ-45 Pin | Signal | Direction | Description |
|-----------|--------|-----------|-------------|
| 1 | dDPWR | Monitor out | +5V power output (100-150mA) |
| 2 | — | — | Not used for Data Export |
| 3 | — | — | Not used for Data Export |
| 4 | GND | — | Signal ground |
| 5 | TxD | Monitor out | Transmit data (from monitor) |
| 6 | — | — | Not used for Data Export |
| 7 | RxD | Monitor in | Receive data (to monitor) |
| 8 | — | — | Not used for Data Export |

Pins counted from pin 1 (leftmost) to pin 8 (rightmost) looking at the RJ-45 jack on the MIB/RS232 board.

### Cable: RJ-45 to DB-9 (for AIR-021)

You need a custom cable or adapter from the Philips MIB/RS232 RJ-45 to the AIR-021 DB-9 COM port:

```
Philips MIB/RS232 (RJ-45)          AIR-021 COM Port (DB-9 Male)
─────────────────────────          ────────────────────────────
Pin 4 (GND)       ─────────────── Pin 5 (GND)
Pin 5 (TxD out)   ─────────────── Pin 2 (RxD)
Pin 7 (RxD in)    ─────────────── Pin 3 (TxD)
Pin 1 (dDPWR)     ─── not connected (or optional +5V)
```

**Cable requirements:**
- 8-conductor #24 AWG UTP (Unshielded Twisted Pair)
- CAT-5 or better
- Straight-through pinning
- Maximum length: 65 ft (20 m)
- Do NOT use shielded cable — required for galvanic isolation

### Cable: RJ-45 to TTL UART (for Jetson Nano)

If using Jetson Nano with an RS-232 level shifter (e.g. MAX3232):

```
Philips MIB/RS232 (RJ-45)     MAX3232 Board          Jetson Nano UART
─────────────────────────      ─────────────          ────────────────
Pin 4 (GND) ──────────────── GND ──────────────────── GND
Pin 5 (TxD) ──────────────── RS232 RX ── TTL TX ────── UART RX
Pin 7 (RxD) ──────────────── RS232 TX ── TTL RX ────── UART TX
```

### Serial Settings

| Parameter | Value |
|-----------|-------|
| Baud rate | 115,200 bps (fixed baudrate protocol) |
| Data bits | 8 |
| Parity | None |
| Stop bits | 1 |
| Flow control | None |

These settings are configured automatically by the driver.

### Monitor Configuration

On the Philips MX800:

1. Enter **Configuration Mode** (requires password)
2. Go to **Main Setup** > **Hardware** > **Interfaces**
3. Set the MIB/RS232 port to **DtOut1** (Data Out function)
4. Verify the yellow "arrow out" LED is lit on the MIB board (DCC mode)
5. Go to **Main Setup** > **Global Settings** > **LAN Data Export** and set to desired level
6. Set **Central Monitoring** to **Optional** (not Mandatory) when connecting to a Computer Client

### Protocol Notes

- The Philips MIB/RS232 uses the **Fixed Baudrate Protocol** (not AutoSpeed/IrDA)
- Frame format: `BOF(0xC0) | Header | User Data | FCS(CRC-CCITT) | EOF(0xC1)`
- Protocol ID: `0x11` (Data Export), Message Type: `0x01`
- The monitor processes max 4-5 frames per 128ms cycle
- Max 1 message per second per poll type (enforced by the driver)
- The driver sends keep-alive polls automatically if no data is exchanged for 10 seconds

---

## Draeger Ventilator — MEDIBUS Connection

### Physical Interface

Draeger ventilators provide an RS-232 port (DB-9 or DB-25 depending on model) for the MEDIBUS protocol.

### DB-9 Pin Assignment (standard RS-232)

| DB-9 Pin | Signal | Direction |
|----------|--------|-----------|
| 2 | RxD | Ventilator in (from gateway) |
| 3 | TxD | Ventilator out (to gateway) |
| 5 | GND | Signal ground |

### Cable: DB-9 to DB-9 (for AIR-021)

Standard **null-modem** (crossover) RS-232 cable:

```
Draeger Ventilator (DB-9)          AIR-021 COM Port (DB-9)
─────────────────────────          ───────────────────────
Pin 2 (RxD)       ─────────────── Pin 3 (TxD)
Pin 3 (TxD)       ─────────────── Pin 2 (RxD)
Pin 5 (GND)       ─────────────── Pin 5 (GND)
```

Some Draeger models may use DB-25. Check your ventilator's MEDIBUS documentation for the exact connector.

### Cable: DB-9 to TTL UART (for Jetson Nano)

```
Draeger Ventilator (DB-9)     MAX3232 Board          Jetson Nano UART
─────────────────────────     ─────────────          ────────────────
Pin 5 (GND) ─────────────── GND ──────────────────── GND
Pin 3 (TxD) ─────────────── RS232 RX ── TTL TX ────── UART RX
Pin 2 (RxD) ─────────────── RS232 TX ── TTL RX ────── UART TX
```

### Serial Settings

| Parameter | Value |
|-----------|-------|
| Baud rate | 19,200 bps (default, configurable with `--draeger-baud`) |
| Data bits | 8 |
| Parity | None |
| Stop bits | 1 |
| Flow control | None (XON/XOFF used at protocol level) |

### Ventilator Configuration

On the Draeger ventilator:

1. Enable the **MEDIBUS** protocol (consult Draeger service manual for your model)
2. Confirm baud rate is set to **19200** (or match with `--draeger-baud`)
3. Verify the RS-232 port is active and not in use by another device

---

## Wiring Diagram — AIR-021 with Both Devices

```
┌─────────────────────┐
│   Philips MX800     │
│   MIB/RS232 (RJ-45) │──── RJ-45 to DB-9 cable ────┐
│                     │     (straight-through)        │
└─────────────────────┘                               │
                                                      ▼
                                              ┌──────────────┐
                                              │  AIR-021     │
                                              │              │
                                              │  COM1 (DB-9) │ ← /dev/ttyS0
                                              │  COM2 (DB-9) │ ← /dev/ttyS1
                                              │              │
                                              └──────────────┘
                                                      ▲
┌─────────────────────┐                               │
│   Draeger Vent      │                               │
│   MEDIBUS (DB-9)    │──── Null-modem DB-9 cable ────┘
│                     │     (crossover)
└─────────────────────┘
```

### Important: Cable Type Differences

| Device | Cable Type | Reason |
|--------|-----------|--------|
| **Philips MX800** | **Straight-through** (RJ-45 to DB-9) | Monitor pin 5 (TxD) maps to DB-9 pin 2 (RxD) by convention |
| **Draeger** | **Null-modem / crossover** (DB-9 to DB-9) | Both sides are DTE, so TxD/RxD must cross |

Getting this wrong is the most common connection issue.

---

## Safety Warnings

Per the Philips IntelliVue Programming Guide:

- The Computer Client (gateway) must be located **outside the patient vicinity** (>6ft / 1.85m from the bed) unless isolated from mains power by an isolation transformer
- Use only **UTP (Unshielded Twisted Pair)** cables to maintain galvanic isolation
- All external devices in the patient vicinity must comply with **IEC 60601-1**
- Alarm data accessed via the protocol **must not be used as a real-time alarming system** due to message transfer delays and possible data loss
- The gateway must comply with applicable **data privacy regulations**

---

## Power Considerations

### AIR-021
- Powered via its own AC adapter — no special considerations

### Jetson Nano
- Module power: 5-10W
- Ensure stable 5V supply via the barrel jack or GPIO header
- The MAX3232 level shifter can typically be powered from the Jetson's 3.3V or 5V rail

### Philips MIB/RS232 dDPWR (Pin 1)
- Provides +5V at 100-150mA from the monitor
- Can optionally power a low-power adapter/converter, but **do not use for powering the gateway**
- Leave disconnected if not needed
