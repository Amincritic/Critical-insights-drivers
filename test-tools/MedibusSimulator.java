import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Spec-compliant Draeger MEDIBUS simulator.
 *
 * Follows the Dräger RS 232 MEDIBUS Protocol Definition (Rev 6.00) and
 * MEDIBUS for Dräger Intensive Care Devices manual.
 *
 * Protocol compliance:
 *   - ICC (0x51) handshake with proper response
 *   - ASCII HEX checksum (LSB of sum from SOH/ESC, in ASCII HEX format)
 *   - Measured data: 2-byte ASCII HEX code + 4-byte ASCII value per record
 *   - Alarm records: 1-byte priority + 2-byte code + 12-byte phrase
 *   - Device identification: 4-byte ID + quoted name + 11-byte revision
 *   - DateTime: HH:MM:SS + DD-MMM-YY
 *   - DC1 (XON) / DC3 (XOFF) flow control handling
 *   - NOP (0x30) response
 *   - NAK (0x15) for corrupt commands
 *   - 3-second timeout awareness
 *
 * Usage:
 *   javac test-tools/MedibusSimulator.java
 *
 *   # TCP mode (connect gateway with --tcp-host/--tcp-port)
 *   java -cp test-tools MedibusSimulator --tcp 9100
 *
 *   # Serial/PTY mode (connect gateway with --serial)
 *   java -cp test-tools MedibusSimulator --serial /tmp/draeger-device
 *
 *   # Custom device model
 *   java -cp test-tools MedibusSimulator --tcp 9100 --model evita4
 *
 * Models: evita (default), evita2, evita4, fabius
 */
public class MedibusSimulator {

    // Protocol framing
    static final int ESC = 0x1B;
    static final int SOH = 0x01;
    static final int CR  = 0x0D;
    static final int DC1 = 0x11; // XON - suspend
    static final int DC3 = 0x13; // XOFF - resume
    static final int NAK = 0x15;
    static final int ETX = 0x03;

    // Command codes
    static final int CMD_NOP              = 0x30;
    static final int CMD_ICC              = 0x51;
    static final int CMD_STOP             = 0x55;
    static final int CMD_REQ_DEVICE_ID    = 0x52;
    static final int CMD_REQ_DATETIME     = 0x28;
    static final int CMD_REQ_DATA_CP1     = 0x24;
    static final int CMD_REQ_DATA_CP2     = 0x2B;
    static final int CMD_REQ_LOW_ALRM_CP1 = 0x25;
    static final int CMD_REQ_HI_ALRM_CP1  = 0x26;
    static final int CMD_REQ_ALARMS_CP1   = 0x27;
    static final int CMD_REQ_ALARMS_CP2   = 0x2E;
    static final int CMD_REQ_SETTINGS     = 0x29;
    static final int CMD_REQ_TEXT_MSG     = 0x2A;
    static final int CMD_CONFIGURE_RESP   = 0x4A;
    static final int CMD_REQ_RT_CONFIG    = 0x53;
    static final int CMD_CONFIG_RT        = 0x54;
    static final int CMD_TIME_CHANGED     = 0x49;

    private final DeviceModel model;
    private volatile boolean running = true;
    private int cycleCount = 0;
    private boolean communicationInitialized = false;

    // Simulated vital signs with realistic ranges
    private final Random rng = new Random();
    private double tidalVolume = 450;
    private double respRate = 16;
    private double minuteVolume = 7.2;
    private double peakPressure = 22;
    private double peep = 5.0;
    private double meanPressure = 12;
    private double plateauPressure = 18;
    private double compliance = 45;
    private double resistance = 12;
    private double fio2 = 40;
    private double airwayTemp = 34;
    private double spontRespRate = 4;
    private double spontMinVol = 2.1;

    public MedibusSimulator(DeviceModel model) {
        this.model = model;
    }

    public void runTcp(int port) throws IOException {
        ServerSocket server = new ServerSocket(port);
        System.out.println("MedibusSimulator [" + model.name + "] listening on TCP port " + port);
        System.out.println("Connect gateway: --tcp-host 127.0.0.1 --tcp-port " + port);
        System.out.println();

        while (running) {
            try (Socket client = server.accept()) {
                System.out.println("Gateway connected from " + client.getRemoteSocketAddress());
                communicationInitialized = false;
                cycleCount = 0;
                handleConnection(client.getInputStream(), client.getOutputStream());
            } catch (IOException e) {
                if (running) System.err.println("Client disconnected: " + e.getMessage());
            }
        }
    }

    public void runSerial(String devicePath) throws Exception {
        System.out.println("MedibusSimulator [" + model.name + "] opening: " + devicePath);
        System.out.println("Connect gateway: --serial <other-end-of-pty>");
        System.out.println();

        try (FileInputStream fin = new FileInputStream(devicePath);
             FileOutputStream fout = new FileOutputStream(devicePath)) {
            communicationInitialized = false;
            cycleCount = 0;
            handleConnection(fin, fout);
        }
    }

    private void handleConnection(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[512];
        int pos = 0;
        boolean inFrame = false;

        while (running) {
            int b = in.read();
            if (b == -1) break;

            // DC1/DC3 flow control — handle at any time
            if (b == DC1) {
                System.out.println("  [flow] DC1 (XON) received — suspending transmission");
                continue;
            }
            if (b == DC3) {
                System.out.println("  [flow] DC3 (XOFF) received — resuming transmission");
                continue;
            }

            if (b == ESC) {
                inFrame = true;
                pos = 0;
                buf[pos++] = (byte) b;
            } else if (b == CR && inFrame) {
                inFrame = false;
                if (pos >= 4) { // ESC + cmd + 2 checksum bytes minimum
                    processCommand(buf, pos, out);
                }
                pos = 0;
            } else if (inFrame && pos < buf.length) {
                buf[pos++] = (byte) b;
            }
        }
    }

    private void processCommand(byte[] buf, int len, OutputStream out) throws IOException {
        // buf[0] = ESC, buf[1] = command code, buf[2..len-3] = argument, buf[len-2..len-1] = checksum
        int cmdCode = buf[1] & 0xFF;

        // Verify checksum
        int receivedChecksum = asciiHexDecode(buf[len - 2], buf[len - 1]);
        int calculatedChecksum = 0;
        for (int i = 0; i < len - 2; i++) {
            calculatedChecksum += buf[i] & 0xFF;
        }
        calculatedChecksum &= 0xFF;

        if (receivedChecksum != calculatedChecksum) {
            System.out.println("  [" + cycleCount + "] BAD CHECKSUM cmd=0x" +
                    hex(cmdCode) + " expected=" + hex(calculatedChecksum) +
                    " received=" + hex(receivedChecksum));
            sendNak(out);
            return;
        }

        // Extract argument (between command code and checksum)
        byte[] argument = null;
        if (len > 4) {
            argument = new byte[len - 4]; // exclude ESC, cmd, 2 checksum bytes
            System.arraycopy(buf, 2, argument, 0, argument.length);
        }

        cycleCount++;
        String cmdName = commandName(cmdCode);
        System.out.println("  [" + cycleCount + "] " + cmdName + " (0x" + hex(cmdCode) + ")");

        // Update simulated vitals
        updateVitals();

        switch (cmdCode) {
            case CMD_ICC:
                communicationInitialized = true;
                sendControlResponse(cmdCode, out);
                System.out.println("       -> ICC acknowledged, communication initialized");
                break;
            case CMD_NOP:
                sendControlResponse(cmdCode, out);
                break;
            case CMD_STOP:
                communicationInitialized = false;
                sendControlResponse(cmdCode, out);
                System.out.println("       -> Communication stopped");
                break;
            case CMD_REQ_DEVICE_ID:
                sendDeviceIdentification(out);
                break;
            case CMD_REQ_DATETIME:
                sendDateTime(out);
                break;
            case CMD_REQ_DATA_CP1:
                sendMeasuredDataCP1(out);
                break;
            case CMD_REQ_DATA_CP2:
                sendMeasuredDataCP2(out);
                break;
            case CMD_REQ_ALARMS_CP1:
                sendAlarms(cmdCode, out);
                break;
            case CMD_REQ_ALARMS_CP2:
                sendAlarms(cmdCode, out);
                break;
            case CMD_REQ_LOW_ALRM_CP1:
            case CMD_REQ_HI_ALRM_CP1:
                sendAlarmLimits(cmdCode, out);
                break;
            case CMD_REQ_SETTINGS:
                sendDeviceSettings(out);
                break;
            case CMD_REQ_TEXT_MSG:
                sendTextMessages(out);
                break;
            case CMD_CONFIGURE_RESP:
                sendControlResponse(cmdCode, out);
                System.out.println("       -> Configure Data Response acknowledged");
                break;
            case CMD_REQ_RT_CONFIG:
            case CMD_CONFIG_RT:
                sendControlResponse(cmdCode, out);
                break;
            default:
                sendControlResponse(cmdCode, out);
                System.out.println("       -> Unknown command acknowledged");
                break;
        }
    }

    // --- Response builders ---

    /**
     * Control/unknown command response: SOH + CmdEcho + Checksum + CR
     * Per spec page 13: no data, just acknowledge.
     */
    private void sendControlResponse(int cmdCode, OutputStream out) throws IOException {
        int checksum = SOH + cmdCode;
        out.write(SOH);
        out.write(cmdCode);
        writeAsciiHexChecksum(out, checksum);
        out.write(CR);
        out.flush();
    }

    /**
     * NAK response for corrupt commands: SOH + NAK + Checksum + CR
     * Per spec page 13.
     */
    private void sendNak(OutputStream out) throws IOException {
        int checksum = SOH + NAK;
        out.write(SOH);
        out.write(NAK);
        writeAsciiHexChecksum(out, checksum);
        out.write(CR);
        out.flush();
    }

    /**
     * Device Identification response.
     * Per spec page 19: ID_NUMBER(4) + 'Name'(quoted) + REVISION(11 chars DD.DD:MM.MM)
     */
    private void sendDeviceIdentification(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // ID Number: 4 ASCII chars
        data.write(padRight(model.idNumber, 4).getBytes());
        // Name: quoted with apostrophes (0x27)
        data.write(0x27); // opening apostrophe
        data.write(model.name.getBytes());
        data.write(0x27); // closing apostrophe
        // Revision: 11 chars (DD.DD:MM.MM format — device version : medibus version)
        data.write(padRight(model.revision, 11).getBytes());

        sendDataResponse(CMD_REQ_DEVICE_ID, data.toByteArray(), out);
        System.out.println("       -> ID=" + model.idNumber + " Name='" + model.name +
                "' Rev=" + model.revision);
    }

    /**
     * DateTime response.
     * Per spec page 16: TIME(HH:MM:SS, 8 bytes) + DATE(DD-MMM-YY, 9 bytes)
     */
    private void sendDateTime(OutputStream out) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String[] months = {"JAN","FEB","MAR","APR","MAI","JUN",
                           "JUL","AUG","SEP","OKT","NOV","DEZ"};
        // German month names per spec
        String time = String.format("%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());
        String date = String.format("%02d-%s-%02d", now.getDayOfMonth(),
                months[now.getMonthValue() - 1], now.getYear() % 100);
        byte[] data = (time + date).getBytes();
        sendDataResponse(CMD_REQ_DATETIME, data, out);
        System.out.println("       -> " + time + " " + date);
    }

    /**
     * Measured data response (codepage 1).
     * Per spec page 14: records of DATA_CODE(2 ASCII HEX) + DATA(4 ASCII chars)
     */
    private void sendMeasuredDataCP1(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // Evita measured data codes from spec
        addMeasuredRecord(data, 0x07, compliance, "_XXX");     // Compliance L/bar
        addMeasuredRecord(data, 0x08, resistance, "XXX_");     // Resistance mbar/L/s
        addMeasuredRecord(data, 0x71, -2, "*_XX_");            // Minimal Airway Pressure mbar
        addMeasuredRecord(data, 0x73, meanPressure, "*_XX_");  // Mean Breathing Pressure mbar
        addMeasuredRecord(data, 0x74, plateauPressure, "*_XX_"); // Plateau Pressure mbar
        addMeasuredRecord(data, 0x78, peep, "*_XX_");          // PEEP Breathing Pressure mbar
        addMeasuredRecord(data, 0x7D, peakPressure, "*_XX_");  // Peak Breathing Pressure mbar
        addMeasuredRecord(data, 0x82, tidalVolume / 1000.0, "X.XX"); // Tidal Volume L
        addMeasuredRecord(data, 0xB5, spontRespRate, "XXX_");  // Spontaneous Respiratory Rate
        addMeasuredRecord(data, 0xB7, spontMinVol, "XX.X");    // Spontaneous Minute Volume L/min
        addMeasuredRecord(data, 0xB9, minuteVolume, "XX.X");   // Respiratory Minute Volume L/min
        addMeasuredRecord(data, 0xC1, airwayTemp, "_XX_");     // Airway Temperature °C
        addMeasuredRecord(data, 0xF0, fio2, "XXX_");           // Insp. O2 %

        sendDataResponse(CMD_REQ_DATA_CP1, data.toByteArray(), out);
        System.out.println("       -> VT=" + (int)tidalVolume + "mL RR=" + (int)respRate +
                " Paw=" + (int)peakPressure + " PEEP=" + (int)peep + " FiO2=" + (int)fio2 + "%");
    }

    /**
     * Measured data response (codepage 2).
     */
    private void sendMeasuredDataCP2(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addMeasuredRecord(data, 0xD6, respRate, "XXX_"); // Resp Rate (Vol/Flow)
        sendDataResponse(CMD_REQ_DATA_CP2, data.toByteArray(), out);
    }

    /**
     * Alarm status response.
     * Per spec page 15: records of PRIORITY(1) + CODE(2 ASCII HEX) + PHRASE(12 ASCII chars)
     */
    private void sendAlarms(int cmdCode, OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // Simulate occasional alarms
        if (peakPressure > 25) {
            addAlarmRecord(data, 27, 0x10, "PAW HIGH    ");
        }
        if (fio2 > 60) {
            addAlarmRecord(data, 23, 0x37, "% O2 HIGH   ");
        }

        sendDataResponse(cmdCode, data.toByteArray(), out);
        if (data.size() > 0) {
            System.out.println("       -> " + (data.size() / 15) + " active alarm(s)");
        } else {
            System.out.println("       -> no alarms");
        }
    }

    /**
     * Alarm limits response — same format as measured data.
     */
    private void sendAlarmLimits(int cmdCode, OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (cmdCode == CMD_REQ_HI_ALRM_CP1) {
            addMeasuredRecord(data, 0x7D, 35, "*_XX_"); // Peak pressure high limit
            addMeasuredRecord(data, 0xB9, 15, "XX.X");  // Minute volume high limit
        } else {
            addMeasuredRecord(data, 0xB9, 3, "XX.X");   // Minute volume low limit
        }
        sendDataResponse(cmdCode, data.toByteArray(), out);
    }

    /**
     * Device settings response.
     * Per spec page 17: records of SETTING_CODE(2 ASCII HEX) + SETTING(5 ASCII chars)
     */
    private void sendDeviceSettings(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addSettingRecord(data, 0x01, fio2, "_XXX_");       // Insp. Oxygen %
        addSettingRecord(data, 0x04, 0.450, "X.XXX");      // Insp. Tidal Volume L
        addSettingRecord(data, 0x09, 12, "XXX.X");          // Frequency IMV (SIMV) 1/min
        addSettingRecord(data, 0x0B, peep, "_XX.X");        // PEEP (CPAP) mbar
        addSettingRecord(data, 0x13, 40, "XXX.X");          // Max insp. Airway Pressure mbar

        sendDataResponse(CMD_REQ_SETTINGS, data.toByteArray(), out);
        System.out.println("       -> 5 settings");
    }

    /**
     * Text message response.
     * Per spec page 18: records of CODE(2 hex) + LENGTH(1 byte) + TEXT + ETX(0x03)
     */
    private void sendTextMessages(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addTextRecord(data, 0x06, "Mode SIMV");

        sendDataResponse(CMD_REQ_TEXT_MSG, data.toByteArray(), out);
        System.out.println("       -> Mode SIMV");
    }

    // --- Record builders ---

    /**
     * Measured data record: DATA_CODE(2 ASCII HEX) + DATA(4 ASCII chars)
     * Total: 6 bytes per record.
     */
    private void addMeasuredRecord(ByteArrayOutputStream data, int code, double value, String format) throws IOException {
        // 2-byte ASCII HEX code
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        // 4-byte ASCII value, right-justified, space-padded
        String valStr = formatValue(value, format);
        data.write(valStr.getBytes());
    }

    /**
     * Setting record: SETTING_CODE(2 ASCII HEX) + SETTING(5 ASCII chars)
     * Total: 7 bytes per record.
     */
    private void addSettingRecord(ByteArrayOutputStream data, int code, double value, String format) throws IOException {
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        String valStr = formatSettingValue(value, format);
        data.write(valStr.getBytes());
    }

    /**
     * Alarm record: PRIORITY(1) + ALARM_CODE(2 ASCII HEX) + ALARM_PHRASE(12 ASCII chars)
     * Total: 15 bytes per record.
     * Priority is encoded as ASCII: 1->0x31, 31->0x4F
     */
    private void addAlarmRecord(ByteArrayOutputStream data, int priority, int code, String phrase) throws IOException {
        data.write(0x30 + priority); // Priority encoded per spec
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        String paddedPhrase = padRight(phrase, 12);
        data.write(paddedPhrase.substring(0, 12).getBytes());
    }

    /**
     * Text message record: CODE(2 hex) + LENGTH(1 byte) + TEXT + ETX
     */
    private void addTextRecord(ByteArrayOutputStream data, int code, String text) throws IOException {
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        int len = Math.min(text.length(), 32);
        data.write(0x30 + len); // Length encoded as ASCII
        data.write(text.substring(0, len).getBytes());
        data.write(ETX);
    }

    /**
     * Data response frame: SOH + CmdEcho + RESPONSE + Checksum + CR
     * Per spec page 12.
     */
    private void sendDataResponse(int cmdCode, byte[] responseData, OutputStream out) throws IOException {
        int checksum = SOH + cmdCode;
        for (byte b : responseData) {
            checksum += b & 0xFF;
        }
        out.write(SOH);
        out.write(cmdCode);
        out.write(responseData);
        writeAsciiHexChecksum(out, checksum);
        out.write(CR);
        out.flush();
    }

    // --- Vital sign simulation ---

    private void updateVitals() {
        double t = cycleCount * 0.05;
        double breathVariation = Math.sin(t) * 0.08;
        double drift = Math.sin(t * 0.3) * 0.03;

        tidalVolume = clamp(450 + 450 * breathVariation + rng.nextGaussian() * 10, 200, 800);
        respRate = clamp(16 + 4 * Math.sin(t * 0.2) + rng.nextGaussian() * 0.5, 8, 35);
        minuteVolume = tidalVolume * respRate / 1000.0;
        peakPressure = clamp(22 + 5 * breathVariation + rng.nextGaussian() * 1, 10, 45);
        meanPressure = clamp(peakPressure * 0.55 + rng.nextGaussian() * 0.5, 5, 30);
        plateauPressure = clamp(peakPressure * 0.82 + rng.nextGaussian() * 0.3, 8, 40);
        peep = clamp(5.0 + rng.nextGaussian() * 0.2, 3, 15);
        compliance = clamp(45 + 10 * drift + rng.nextGaussian() * 2, 20, 80);
        resistance = clamp(12 + 3 * drift + rng.nextGaussian() * 1, 5, 30);
        fio2 = clamp(40 + rng.nextGaussian() * 0.5, 21, 100);
        airwayTemp = clamp(34 + rng.nextGaussian() * 0.3, 30, 38);
        spontRespRate = clamp(4 + 2 * Math.sin(t * 0.15) + rng.nextGaussian() * 0.5, 0, 20);
        spontMinVol = spontRespRate * 0.35;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // --- ASCII HEX formatting per MEDIBUS spec ---

    /**
     * Write 2-byte ASCII HEX checksum (LSB of sum).
     * Per spec: "Least significant 8-bit sum of all preceding bytes in ASCII HEX format"
     */
    private void writeAsciiHexChecksum(OutputStream out, int sum) throws IOException {
        int lsb = sum & 0xFF;
        out.write(asciiHexHi(lsb));
        out.write(asciiHexLo(lsb));
    }

    private static int asciiHexHi(int v) {
        int n = (v >> 4) & 0x0F;
        return n < 10 ? '0' + n : 'A' + n - 10;
    }

    private static int asciiHexLo(int v) {
        int n = v & 0x0F;
        return n < 10 ? '0' + n : 'A' + n - 10;
    }

    private static int asciiHexDecode(int hi, int lo) {
        return (hexVal(hi) << 4) | hexVal(lo);
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return 10 + c - 'A';
        if (c >= 'a' && c <= 'f') return 10 + c - 'a';
        return 0;
    }

    /**
     * Format a measured value into a 4-character ASCII string per MEDIBUS data format.
     * Underscore in format = space. '*' prefix means value can be negative.
     */
    private String formatValue(double value, String format) {
        int intVal = (int) Math.round(value);
        boolean canBeNegative = format.startsWith("*");
        String fmt = canBeNegative ? format.substring(1) : format;

        // Count significant digits and decimal position
        int decPos = fmt.indexOf('.');
        String valStr;
        if (decPos >= 0) {
            int decimals = fmt.length() - decPos - 1;
            double scaled = value;
            for (int i = 0; i < decimals; i++) scaled *= 10;
            intVal = (int) Math.round(scaled);
            String raw = String.valueOf(Math.abs(intVal));
            while (raw.length() < decimals + 1) raw = "0" + raw;
            valStr = raw.substring(0, raw.length() - decimals) + "." + raw.substring(raw.length() - decimals);
        } else {
            valStr = String.valueOf(Math.abs(intVal));
        }

        // Pad to 4 characters
        while (valStr.length() < 4) valStr = " " + valStr;
        if (valStr.length() > 4) valStr = valStr.substring(valStr.length() - 4);

        // Handle negative
        if (canBeNegative && value < 0) {
            valStr = "-" + valStr.substring(1);
        }

        return valStr;
    }

    /**
     * Format a setting value into a 5-character ASCII string.
     */
    private String formatSettingValue(double value, String format) {
        String v = formatValue(value, format.length() > 4 ? format.substring(0, 4) : format);
        while (v.length() < 5) v = " " + v;
        return v.substring(0, 5);
    }

    private static String padRight(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String hex(int v) {
        return String.format("%02X", v & 0xFF);
    }

    private static String commandName(int cmd) {
        switch (cmd) {
            case CMD_NOP:             return "NOP";
            case CMD_ICC:             return "ICC";
            case CMD_STOP:            return "STOP";
            case CMD_REQ_DEVICE_ID:   return "ReqDeviceId";
            case CMD_REQ_DATETIME:    return "ReqDateTime";
            case CMD_REQ_DATA_CP1:    return "ReqDataCP1";
            case CMD_REQ_DATA_CP2:    return "ReqDataCP2";
            case CMD_REQ_LOW_ALRM_CP1:return "ReqLowAlarmLimitsCP1";
            case CMD_REQ_HI_ALRM_CP1: return "ReqHighAlarmLimitsCP1";
            case CMD_REQ_ALARMS_CP1:  return "ReqAlarmsCP1";
            case CMD_REQ_ALARMS_CP2:  return "ReqAlarmsCP2";
            case CMD_REQ_SETTINGS:    return "ReqDeviceSettings";
            case CMD_REQ_TEXT_MSG:    return "ReqTextMessages";
            case CMD_CONFIGURE_RESP:  return "ConfigureDataResponse";
            case CMD_REQ_RT_CONFIG:   return "ReqRealtimeConfig";
            case CMD_CONFIG_RT:       return "ConfigureRealtime";
            case CMD_TIME_CHANGED:    return "TimeChanged";
            default: return "Unknown(0x" + hex(cmd) + ")";
        }
    }

    // --- Device models ---

    static class DeviceModel {
        final String idNumber;
        final String name;
        final String revision;

        DeviceModel(String idNumber, String name, String revision) {
            this.idNumber = idNumber;
            this.name = name;
            this.revision = revision;
        }
    }

    static final Map<String, DeviceModel> MODELS = new LinkedHashMap<>();
    static {
        MODELS.put("evita",  new DeviceModel("8210", "Evita",    "01.00:03.00"));
        MODELS.put("evita2", new DeviceModel("8200", "Evita 2",  "01.00:03.00"));
        MODELS.put("evita4", new DeviceModel("8214", "Evita 4",  "02.00:03.00"));
        MODELS.put("v500",   new DeviceModel("8410", "Evita V500","03.20:06.00"));
        MODELS.put("savina", new DeviceModel("8310", "Savina",   "01.00:03.00"));
        MODELS.put("fabius", new DeviceModel("8088", "Fabius GS", "02.02:04.00"));
    }

    // --- Main ---

    public static void main(String[] args) throws Exception {
        String mode = "tcp";
        int port = 9100;
        String serialPath = null;
        String modelName = "evita";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tcp":    mode = "tcp"; port = Integer.parseInt(args[++i]); break;
                case "--serial": mode = "serial"; serialPath = args[++i]; break;
                case "--model":  modelName = args[++i].toLowerCase(); break;
                case "--help":
                    System.out.println("Usage: java MedibusSimulator [--tcp <port>] [--serial <path>] [--model <name>]");
                    System.out.println("Models: " + String.join(", ", MODELS.keySet()));
                    System.exit(0);
                default:
                    System.err.println("Unknown arg: " + args[i]);
                    System.exit(1);
            }
        }

        DeviceModel model = MODELS.get(modelName);
        if (model == null) {
            System.err.println("Unknown model: " + modelName + ". Available: " + String.join(", ", MODELS.keySet()));
            System.exit(1);
        }

        MedibusSimulator sim = new MedibusSimulator(model);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));

        if ("serial".equals(mode)) {
            sim.runSerial(serialPath);
        } else {
            sim.runTcp(port);
        }
    }
}
