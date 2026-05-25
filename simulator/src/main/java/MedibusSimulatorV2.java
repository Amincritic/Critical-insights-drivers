import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Enhanced Draeger MEDIBUS simulator (V2).
 *
 * Follows the Draeger RS 232 MEDIBUS Protocol Definition (Rev 6.00) and
 * MEDIBUS for Draeger Intensive Care Devices manual.
 *
 * Enhancements over MedibusSimulator:
 *   - Realtime waveform data (Configure RT 0x54, Request RT Config 0x53)
 *   - Realtime Data Records: airway pressure, flow, volume, exp. volume
 *   - Sync command C6H with argument C0H (Start of Ventilator Inspiratory Cycle)
 *   - Configure Data Response (0x4A) with selective data code filtering
 *   - Full device settings response (0x29) with 7-byte records
 *   - Full text messages response (0x2A) with proper record format
 *   - Negative value formatting for pressure codes
 *   - Trend data support (0x6C status, 0x6D data)
 *   - Dynamic alarm simulation based on actual vital values
 *   - Six device models: evita, evita2, evita4, v500, savina, fabius
 *   - TCP and serial modes
 *   - Realistic synchronized waveform generation
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
 *   - Realtime fast bytes (high bit set, 0x80+) interleaved with slow data
 *
 * Usage:
 *   ../gateway/gradlew installDist
 *
 *   # TCP mode
 *   java -cp build/install/simulator/lib/* MedibusSimulatorV2 --tcp 9100
 *
 *   # Serial/PTY mode
 *   java -cp build/install/simulator/lib/* MedibusSimulatorV2 --serial /tmp/draeger-device
 *
 *   # Custom device model
 *   java -cp build/install/simulator/lib/* MedibusSimulatorV2 --tcp 9100 --model evita4
 *
 * Models: evita (default), evita2, evita4, v500, savina, fabius
 */
public class MedibusSimulatorV2 {

    // Protocol framing
    static final int ESC = 0x1B;
    static final int SOH = 0x01;
    static final int CR  = 0x0D;
    static final int DC1 = 0x11; // suspend transmission
    static final int DC3 = 0x13; // resume transmission
    static final int NAK = 0x15;
    static final int ETX = 0x03;

    // Realtime sync
    static final int RT_SYNC_BYTE = 0xD0;
    static final int RT_SYNC = 0xC6;
    static final int RT_SYNC_INSP_START = 0xC0;

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
    static final int CMD_REQ_ALARMS_CP3   = 0x23;
    static final int CMD_REQ_ALARMS_CP1   = 0x27;
    static final int CMD_REQ_ALARMS_CP2   = 0x2E;
    static final int CMD_REQ_SETTINGS     = 0x29;
    static final int CMD_REQ_TEXT_MSG     = 0x2A;
    static final int CMD_CONFIGURE_RESP   = 0x4A;
    static final int CMD_REQ_RT_CONFIG    = 0x53;
    static final int CMD_CONFIG_RT        = 0x54;
    static final int CMD_TIME_CHANGED     = 0x49;
    static final int CMD_REQ_TREND_STATUS = 0x6C;
    static final int CMD_REQ_TREND_DATA   = 0x6D;

    // Realtime waveform codes (per MEDIBUS spec)
    static final int RT_CODE_AIRWAY_PRESSURE = 0x00;  // mbar
    static final int RT_CODE_FLOW             = 0x01;  // L/min
    static final int RT_CODE_VOLUME           = 0x03;  // mL
    static final int RT_CODE_EXP_VOLUME       = 0x24;  // mL

    private final DeviceModel model;
    private volatile boolean running = true;
    private int cycleCount = 0;
    private boolean communicationInitialized = false;
    private File controlFile;
    private long controlFileLastModified = -1;
    private final Properties control = new Properties();

    // Simulated vital signs with realistic ranges
    private final Random rng = new Random();
    private double tidalVolume = 450;
    private double respRate = 16;
    private double minuteVolume = 7.2;
    private double peakPressure = 22;
    private double peep = 5.0;
    private double meanPressure = 12;
    private double plateauPressure = 18;
    private double minPressure = -2;
    private double compliance = 45;
    private double resistance = 12;
    private double fio2 = 40;
    private double airwayTemp = 34;
    private double spontRespRate = 4;
    private double spontMinVol = 2.1;
    private double inspFlow = 40;
    private double ieRatioI = 1;
    private double ieRatioE = 2;

    // Realtime waveform state
    private volatile boolean realtimeEnabled = false;
    private final Set<Integer> realtimeConfiguredCodes = new LinkedHashSet<>();
    private volatile OutputStream realtimeOut = null;
    private volatile boolean realtimeSending = false;
    private volatile boolean transmissionSuspended = false;
    private long breathStartTimeMs = System.currentTimeMillis();
    private long waveformSampleIndex = 0;

    // Configure Data Response filtering
    private Set<Integer> configuredDataCodes = null; // null = send all

    // Trend data storage (ring buffer of last 60 minutes, one sample per minute)
    private final int TREND_MAX_SAMPLES = 60;
    private final List<double[]> trendSamples = new ArrayList<>();
    // Each trend sample: [tidalVolume, respRate, peakPressure, peep, fio2, minuteVolume]
    private long lastTrendSampleTime = 0;

    // Waveform generation constants
    private static final double WAVEFORM_SAMPLE_RATE_HZ = 100.0; // 100 Hz sample rate
    private static final long WAVEFORM_INTERVAL_MS = (long)(1000.0 / WAVEFORM_SAMPLE_RATE_HZ);

    public MedibusSimulatorV2(DeviceModel model) {
        this.model = model;
    }

    public void runTcp(int port) throws IOException {
        ServerSocket server = new ServerSocket(port);
        System.out.println("MedibusSimulatorV2 [" + model.name + "] listening on TCP port " + port);
        System.out.println("Connect gateway: --tcp-host 127.0.0.1 --tcp-port " + port);
        System.out.println();

        while (running) {
            try (Socket client = server.accept()) {
                System.out.println("Gateway connected from " + client.getRemoteSocketAddress());
                resetState();
                handleConnection(client.getInputStream(), client.getOutputStream());
            } catch (IOException e) {
                if (running) System.err.println("Client disconnected: " + e.getMessage());
            } finally {
                stopRealtime();
            }
        }
    }

    public void runSerial(String devicePath) throws Exception {
        System.out.println("MedibusSimulatorV2 [" + model.name + "] opening: " + devicePath);
        System.out.println("Connect gateway: --serial <other-end-of-pty>");
        System.out.println();

        try (FileInputStream fin = new FileInputStream(devicePath);
             FileOutputStream fout = new FileOutputStream(devicePath)) {
            resetState();
            handleConnection(fin, fout);
        } finally {
            stopRealtime();
        }
    }

    private void resetState() {
        communicationInitialized = false;
        cycleCount = 0;
        realtimeEnabled = false;
        realtimeConfiguredCodes.clear();
        realtimeOut = null;
        realtimeSending = false;
        configuredDataCodes = null;
        waveformSampleIndex = 0;
        breathStartTimeMs = System.currentTimeMillis();
        trendSamples.clear();
        lastTrendSampleTime = 0;
    }

    private void handleConnection(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[512];
        int pos = 0;
        boolean inFrame = false;

        while (running) {
            int b = in.read();
            if (b == -1) break;

            // Per MEDIBUS: DC1 suspends transmission, DC3 resumes it.
            if (b == DC1) {
                transmissionSuspended = true;
                System.out.println("  [flow] DC1 received -- suspending transmission");
                continue;
            }
            if (b == DC3) {
                transmissionSuspended = false;
                System.out.println("  [flow] DC3 received -- resuming transmission");
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
            argument = new byte[len - 4];
            System.arraycopy(buf, 2, argument, 0, argument.length);
        }

        cycleCount++;
        String cmdName = commandName(cmdCode);
        System.out.println("  [" + cycleCount + "] " + cmdName + " (0x" + hex(cmdCode) + ")");

        // Update simulated vitals
        updateVitals();

        // Record trend data every 60 seconds
        recordTrendSample();

        // Send realtime waveform data between command/response cycles if enabled
        if (!transmissionSuspended && realtimeEnabled && realtimeOut != null) {
            sendRealtimeWaveformBurst(out);
        }

        switch (cmdCode) {
            case CMD_ICC:
                communicationInitialized = true;
                configuredDataCodes = null; // Reset data code filter on ICC
                realtimeEnabled = false;
                realtimeConfiguredCodes.clear();
                stopRealtime();
                sendControlResponse(cmdCode, out);
                System.out.println("       -> ICC acknowledged, communication initialized");
                break;
            case CMD_NOP:
                sendControlResponse(cmdCode, out);
                break;
            case CMD_STOP:
                communicationInitialized = false;
                stopRealtime();
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
            case CMD_REQ_ALARMS_CP3:
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
                handleConfigureDataResponse(argument, out);
                break;
            case CMD_REQ_RT_CONFIG:
                sendRealtimeConfiguration(out);
                break;
            case CMD_CONFIG_RT:
                handleConfigureRealtime(argument, out);
                break;
            case CMD_REQ_TREND_STATUS:
                sendTrendDataStatus(out);
                break;
            case CMD_REQ_TREND_DATA:
                sendTrendData(argument, out);
                break;
            default:
                sendControlResponse(cmdCode, out);
                System.out.println("       -> Unknown command acknowledged");
                break;
        }
    }

    // =========================================================================
    // Response builders
    // =========================================================================

    /**
     * Control/unknown command response: SOH + CmdEcho + Checksum + CR
     * Per spec page 13: no data, just acknowledge.
     */
    private void sendControlResponse(int cmdCode, OutputStream out) throws IOException {
        if (transmissionSuspended) { return; }
        int checksum = SOH + cmdCode;
        out.write(SOH);
        out.write(cmdCode);
        writeAsciiHexChecksum(out, checksum);
        out.write(CR);
        out.flush();
    }

    /**
     * NAK response for corrupt commands: SOH + NAK + Checksum + CR
     */
    private void sendNak(OutputStream out) throws IOException {
        if (transmissionSuspended) { return; }
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
        data.write(padRight(model.idNumber, 4).getBytes());
        data.write(0x27); // opening apostrophe
        data.write(model.name.getBytes());
        data.write(0x27); // closing apostrophe
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
     *
     * If Configure Data Response (0x4A) was used, only configured codes are returned.
     */
    private void sendMeasuredDataCP1(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // All available CP1 data codes with their values
        maybeAddMeasuredRecord(data, 0x07, compliance, "_XXX");        // Compliance L/bar
        maybeAddMeasuredRecord(data, 0x08, resistance, "XXX_");        // Resistance mbar/L/s
        maybeAddMeasuredRecord(data, 0x71, minPressure, "*_XX_");      // Min Airway Pressure mbar
        maybeAddMeasuredRecord(data, 0x73, meanPressure, "*_XX_");     // Mean Breathing Pressure mbar
        maybeAddMeasuredRecord(data, 0x74, plateauPressure, "*_XX_");  // Plateau Pressure mbar
        maybeAddMeasuredRecord(data, 0x78, peep, "*_XX_");             // PEEP Breathing Pressure mbar
        maybeAddMeasuredRecord(data, 0x7D, peakPressure, "*_XX_");     // Peak Breathing Pressure mbar
        maybeAddMeasuredRecord(data, 0x82, tidalVolume / 1000.0, "X.XX"); // Tidal Volume L
        maybeAddMeasuredRecord(data, 0xB5, spontRespRate, "XXX_");     // Spontaneous Respiratory Rate
        maybeAddMeasuredRecord(data, 0xB7, spontMinVol, "XX.X");       // Spontaneous Minute Volume L/min
        maybeAddMeasuredRecord(data, 0xB9, minuteVolume, "XX.X");      // Respiratory Minute Volume L/min
        maybeAddMeasuredRecord(data, 0xC1, airwayTemp, "_XX_");        // Airway Temperature C
        maybeAddMeasuredRecord(data, 0xF0, fio2, "XXX_");              // Insp. O2 %

        sendDataResponse(CMD_REQ_DATA_CP1, data.toByteArray(), out);
        System.out.println("       -> VT=" + (int)tidalVolume + "mL RR=" + (int)respRate +
                " Paw=" + (int)peakPressure + " PEEP=" + (int)peep + " FiO2=" + (int)fio2 + "%");
    }

    /**
     * Measured data response (codepage 2).
     */
    private void sendMeasuredDataCP2(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        maybeAddMeasuredRecord(data, 0xD6, respRate, "XXX_"); // Resp Rate (Vol/Flow)
        sendDataResponse(CMD_REQ_DATA_CP2, data.toByteArray(), out);
    }

    /**
     * Conditionally add a measured record based on Configure Data Response filter.
     */
    private void maybeAddMeasuredRecord(ByteArrayOutputStream data, int code, double value, String format) throws IOException {
        if (configuredDataCodes == null || configuredDataCodes.contains(code)) {
            addMeasuredRecord(data, code, value, format);
        }
    }

    /**
     * Dynamic alarm status response.
     * Per spec page 15: records of PRIORITY(1) + CODE(2 ASCII HEX) + PHRASE(12 ASCII chars)
     *
     * Alarms trigger based on actual vital values:
     *   - PAW HIGH when peak pressure > 35 mbar
     *   - MIN VOL LOW when minute volume < 3 L/min
     *   - APNEA when respiratory rate < 4 /min
     *   - O2 HIGH when FiO2 > 60%
     */
    private void sendAlarms(int cmdCode, OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        if (cmdCode == CMD_REQ_ALARMS_CP1 || cmdCode == CMD_REQ_ALARMS_CP3) {
            // Priority is encoded as (priority_value + 0x30) per spec
            if (peakPressure > 35) {
                addAlarmRecord(data, 2, 0x10, "PAW HIGH    "); // priority 2 = medium
            }
            if (minuteVolume < 3.0) {
                addAlarmRecord(data, 1, 0x0C, "MIN VOL LOW "); // priority 1 = high
            }
            if (respRate < 4.0) {
                addAlarmRecord(data, 1, 0x1A, "APNEA       "); // priority 1 = high
            }
            if (fio2 > 60) {
                addAlarmRecord(data, 3, 0x37, "% O2 HIGH   "); // priority 3 = low
            }
        }
        // CP2 alarms: none simulated currently

        sendDataResponse(cmdCode, data.toByteArray(), out);
        if (data.size() > 0) {
            System.out.println("       -> " + (data.size() / 15) + " active alarm(s)");
        } else {
            System.out.println("       -> no alarms");
        }
    }

    /**
     * Alarm limits response.
     */
    private void sendAlarmLimits(int cmdCode, OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (cmdCode == CMD_REQ_HI_ALRM_CP1) {
            addMeasuredRecord(data, 0x7D, 35, "*_XX_"); // Peak pressure high limit
            addMeasuredRecord(data, 0xB9, 15, "XX.X");  // Minute volume high limit
            addMeasuredRecord(data, 0xF0, 100, "XXX_"); // FiO2 high limit
        } else {
            addMeasuredRecord(data, 0xB9, 3, "XX.X");   // Minute volume low limit
            addMeasuredRecord(data, 0xF0, 18, "XXX_");  // FiO2 low limit
        }
        sendDataResponse(cmdCode, data.toByteArray(), out);
    }

    /**
     * Full device settings response (0x29).
     * Per spec page 17: records of SETTING_CODE(2 ASCII HEX) + SETTING(5 ASCII chars)
     * Total: 7 bytes per record.
     *
     * Evita settings:
     *   01H - Insp. Oxygen (%)
     *   02H - Max insp. Flow (L/min)
     *   04H - Insp. Tidal Volume (L)
     *   07H - I:E I-Part
     *   08H - I:E E-Part
     *   09H - Frequency IMV (1/min)
     *   0BH - PEEP (mbar)
     *   13H - Max insp. Airway Pressure (mbar)
     */
    private void sendDeviceSettings(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addSettingRecord(data, 0x01, fio2, "_XXX_");           // Insp. Oxygen %
        addSettingRecord(data, 0x02, inspFlow, "_XX.X");       // Max insp. Flow L/min
        addSettingRecord(data, 0x04, tidalVolume / 1000.0, "X.XXX"); // Insp. Tidal Volume L
        addSettingRecord(data, 0x07, ieRatioI, "__X.X");       // I:E I-Part
        addSettingRecord(data, 0x08, ieRatioE, "__X.X");       // I:E E-Part
        addSettingRecord(data, 0x09, 12, "XXX.X");             // Frequency IMV (SIMV) 1/min
        addSettingRecord(data, 0x0B, peep, "_XX.X");           // PEEP (CPAP) mbar
        addSettingRecord(data, 0x13, 40, "XXX.X");             // Max insp. Airway Pressure mbar

        sendDataResponse(CMD_REQ_SETTINGS, data.toByteArray(), out);
        System.out.println("       -> 8 settings (O2=" + (int)fio2 + "% VT=" +
                (int)tidalVolume + "mL PEEP=" + (int)peep + " Pmax=40)");
    }

    /**
     * Full text messages response (0x2A).
     * Per spec page 18: records of CODE(2 hex) + LENGTH(1 byte) + TEXT + ETX(0x03)
     */
    private void sendTextMessages(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addTextRecord(data, 0x06, "Mode SIMV");           // Current ventilation mode

        sendDataResponse(CMD_REQ_TEXT_MSG, data.toByteArray(), out);
        System.out.println("       -> Mode SIMV");
    }

    // =========================================================================
    // Configure Data Response (0x4A)
    // =========================================================================

    /**
     * Handle Configure Data Response command.
     * The argument contains a list of 2-byte ASCII HEX data codes the gateway wants.
     * Only those codes will be returned in subsequent measured data responses.
     * Reset to all codes after ICC.
     */
    private void handleConfigureDataResponse(byte[] argument, OutputStream out) throws IOException {
        if (argument != null && argument.length >= 2) {
            configuredDataCodes = new LinkedHashSet<>();
            for (int i = 0; i + 1 < argument.length; i += 2) {
                int code = asciiHexDecode(argument[i], argument[i + 1]);
                configuredDataCodes.add(code);
            }
            System.out.println("       -> Configured " + configuredDataCodes.size() +
                    " data codes: " + formatCodeSet(configuredDataCodes));
        } else {
            // Empty argument: reset to all
            configuredDataCodes = null;
            System.out.println("       -> Reset to all data codes");
        }
        sendControlResponse(CMD_CONFIGURE_RESP, out);
    }

    // =========================================================================
    // Realtime Waveform Data (0x53, 0x54)
    // =========================================================================

    /**
     * Request Realtime Configuration (0x53) response.
     * Returns supported realtime data records with interval, min, max, and maxbin.
     */
    private void sendRealtimeConfiguration(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] codes = { RT_CODE_AIRWAY_PRESSURE, RT_CODE_FLOW, RT_CODE_VOLUME, RT_CODE_EXP_VOLUME };
        for (int code : codes) {
            addRealtimeConfigRecord(data, code);
        }
        sendDataResponse(CMD_REQ_RT_CONFIG, data.toByteArray(), out);
        System.out.println("       -> RT config: " +
                (realtimeEnabled ? formatCodeSet(realtimeConfiguredCodes) : "disabled"));
    }

    private void addRealtimeConfigRecord(ByteArrayOutputStream data, int code) throws IOException {
        int min = 0;
        int max = 511;
        int maxbin = 511;
        switch (code) {
            case RT_CODE_AIRWAY_PRESSURE:
                min = -10;
                max = 60;
                break;
            case RT_CODE_FLOW:
                min = -60;
                max = 60;
                break;
            case RT_CODE_VOLUME:
            case RT_CODE_EXP_VOLUME:
                min = 0;
                max = 800;
                break;
            default:
                break;
        }
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        data.write(String.format("%08d", Integer.valueOf(10)).getBytes());
        data.write(String.format("%5d", Integer.valueOf(min)).getBytes());
        data.write(String.format("%5d", Integer.valueOf(max)).getBytes());
        data.write(String.format("%03X", Integer.valueOf(maxbin)).getBytes());
    }

    /**
     * Configure Realtime Transmission (0x54).
     * Argument contains pairs of bytes: waveform_code + interval.
     * If argument is empty or null, disable realtime.
     * Waveform codes: 00=airway pressure, 01=flow, 03=volume, 24=exp volume.
     */
    private void handleConfigureRealtime(byte[] argument, OutputStream out) throws IOException {
        if (argument != null && argument.length >= 2) {
            realtimeConfiguredCodes.clear();
            // Parse pairs: each pair is 2 ASCII HEX bytes for the waveform code
            // followed by 2 ASCII HEX bytes for the interval
            for (int i = 0; i + 3 < argument.length; i += 4) {
                int code = asciiHexDecode(argument[i], argument[i + 1]);
                realtimeConfiguredCodes.add(code);
            }
            // If only one code without interval pair, handle gracefully
            if (realtimeConfiguredCodes.isEmpty() && argument.length >= 2) {
                int code = asciiHexDecode(argument[0], argument[1]);
                realtimeConfiguredCodes.add(code);
            }
            realtimeEnabled = !realtimeConfiguredCodes.isEmpty();
            realtimeOut = out;
            breathStartTimeMs = System.currentTimeMillis();
            waveformSampleIndex = 0;
            System.out.println("       -> Realtime enabled for codes: " +
                    formatCodeSet(realtimeConfiguredCodes));
        } else {
            stopRealtime();
            System.out.println("       -> Realtime disabled");
        }
        sendControlResponse(CMD_CONFIG_RT, out);
    }

    private void stopRealtime() {
        realtimeEnabled = false;
        realtimeConfiguredCodes.clear();
        realtimeOut = null;
        realtimeSending = false;
    }

    /**
     * Send a burst of realtime waveform data between command/response cycles.
     *
     * Per MEDIBUS spec: realtime data records are 2 bytes each (high byte | low byte)
     * transmitted with high bit set (0x80+) as fast bytes, interleaved with slow data.
     *
     * Each configured waveform sends multiple samples per burst to maintain the
     * sample rate. A sync command (C6H with C0H argument) is sent at the start
     * of each inspiratory cycle.
     */
    private void sendRealtimeWaveformBurst(OutputStream out) throws IOException {
        if (transmissionSuspended || !realtimeEnabled || realtimeConfiguredCodes.isEmpty()) return;

        // Send ~10 samples per burst (100ms worth at 100Hz)
        int samplesPerBurst = 10;

        for (int s = 0; s < samplesPerBurst; s++) {
            double t = waveformSampleIndex / WAVEFORM_SAMPLE_RATE_HZ;
            double breathPeriod = 60.0 / respRate;
            double breathPhase = (t % breathPeriod) / breathPeriod;
            double inspRatio = ieRatioI / (ieRatioI + ieRatioE);
            int streamCount = Math.min(realtimeConfiguredCodes.size(), 4);
            int sync = RT_SYNC_BYTE;
            for (int i = 0; i < streamCount; i++) {
                sync |= 1 << i;
            }
            out.write(sync);

            // Detect start of inspiratory cycle for sync
            double prevT = (waveformSampleIndex - 1) / WAVEFORM_SAMPLE_RATE_HZ;
            double prevPhase = (prevT % breathPeriod) / breathPeriod;
            if (prevPhase > 0.9 && breathPhase < 0.1 && waveformSampleIndex > 0) {
                // Sync command: C6H with argument C0H (Start of Ventilator Inspiratory Cycle)
                out.write(RT_SYNC);
                out.write(RT_SYNC_INSP_START);
            }

            for (int code : realtimeConfiguredCodes) {
                int sampleValue = generateWaveformSample(code, breathPhase, inspRatio);
                int first = 0x80 | ((sampleValue >> 6) & 0x3F);
                int second = 0x80 | (sampleValue & 0x3F);
                out.write(first);
                out.write(second);
            }
            waveformSampleIndex++;
        }
        out.flush();
    }

    /**
     * Generate a single waveform sample value for the given code and breath phase.
     *
     * Waveform shapes (synchronized with breath cycle):
     *   Airway pressure: inspiratory plateau + exponential decay during expiration
     *   Flow: square wave inspiration + exponential decay expiration
     *   Volume: linear ramp up during inspiration + rapid reset on expiration
     *   Exp. volume: ramp during expiration
     *
     * @param code waveform code
     * @param breathPhase 0.0 = start of inspiration, 1.0 = end of breath
     * @param inspRatio fraction of breath that is inspiration (I / (I+E))
     * @return sample value (unsigned integer, 0-511 for 9-bit range)
     */
    private int generateWaveformSample(int code, double breathPhase, double inspRatio) {
        double value = 0;
        boolean isInspiration = breathPhase < inspRatio;

        switch (code) {
            case RT_CODE_AIRWAY_PRESSURE: {
                // Airway pressure in mbar
                // Inspiration: rapid rise to peak, plateau at plateau pressure
                // Expiration: exponential decay to PEEP
                if (isInspiration) {
                    double inspPhase = breathPhase / inspRatio;
                    if (inspPhase < 0.1) {
                        // Rapid rise
                        value = peep + (peakPressure - peep) * (inspPhase / 0.1);
                    } else if (inspPhase < 0.3) {
                        // Peak
                        value = peakPressure;
                    } else {
                        // Settle to plateau
                        value = plateauPressure + (peakPressure - plateauPressure) *
                                Math.exp(-(inspPhase - 0.3) * 5);
                    }
                } else {
                    double expPhase = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = peep + (plateauPressure - peep) * Math.exp(-expPhase * 4);
                }
                value += rng.nextGaussian() * 0.3;
                // Scale: value is in mbar, range typically 0-50, map to 0-511
                value = clamp(value, -10, 60);
                return (int) Math.round((value + 10) * 511.0 / 70.0);
            }
            case RT_CODE_FLOW: {
                // Flow in L/min
                // Inspiration: positive square wave at inspFlow
                // Expiration: negative exponential decay
                if (isInspiration) {
                    double inspPhase = breathPhase / inspRatio;
                    if (inspPhase < 0.05) {
                        value = inspFlow * (inspPhase / 0.05); // ramp up
                    } else if (inspPhase < 0.95) {
                        value = inspFlow; // square wave
                    } else {
                        value = inspFlow * (1.0 - (inspPhase - 0.95) / 0.05); // ramp down
                    }
                } else {
                    double expPhase = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = -inspFlow * 0.8 * Math.exp(-expPhase * 3);
                }
                value += rng.nextGaussian() * 0.5;
                // Scale: range typically -60 to +60, map to 0-511
                value = clamp(value, -60, 60);
                return (int) Math.round((value + 60) * 511.0 / 120.0);
            }
            case RT_CODE_VOLUME: {
                // Volume in mL
                // Inspiration: linear ramp up from 0 to tidal volume
                // Expiration: rapid decrease back to 0
                if (isInspiration) {
                    double inspPhase = breathPhase / inspRatio;
                    value = tidalVolume * inspPhase;
                } else {
                    double expPhase = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = tidalVolume * Math.exp(-expPhase * 4);
                }
                value += rng.nextGaussian() * 2;
                // Scale: range 0-800 mL, map to 0-511
                value = clamp(value, 0, 800);
                return (int) Math.round(value * 511.0 / 800.0);
            }
            case RT_CODE_EXP_VOLUME: {
                // Expiratory volume in mL
                // Zero during inspiration, ramp up during expiration
                if (isInspiration) {
                    value = 0;
                } else {
                    double expPhase = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = tidalVolume * (1.0 - Math.exp(-expPhase * 4));
                }
                value += rng.nextGaussian() * 2;
                value = clamp(value, 0, 800);
                return (int) Math.round(value * 511.0 / 800.0);
            }
            default:
                return 0;
        }
    }

    // =========================================================================
    // Trend Data (0x6C, 0x6D)
    // =========================================================================

    /**
     * Record a trend sample (called every processing cycle, but only stores
     * one sample per 60 seconds).
     */
    private void recordTrendSample() {
        long now = System.currentTimeMillis();
        if (now - lastTrendSampleTime >= 60000) {
            lastTrendSampleTime = now;
            double[] sample = new double[] {
                tidalVolume, respRate, peakPressure, peep, fio2, minuteVolume
            };
            synchronized (trendSamples) {
                trendSamples.add(sample);
                if (trendSamples.size() > TREND_MAX_SAMPLES) {
                    trendSamples.remove(0);
                }
            }
        }
    }

    // Trend parameter codes (mapped to indices in sample array)
    private static final int TREND_VT   = 0x82;
    private static final int TREND_RR   = 0xD6;
    private static final int TREND_PAW  = 0x7D;
    private static final int TREND_PEEP = 0x78;
    private static final int TREND_FIO2 = 0xF0;
    private static final int TREND_MV   = 0xB9;

    /**
     * Request Trend Data Status (0x6C) response.
     * Returns CODE PAGE, DATA CODE, COUNT, and BEGIN records.
     */
    private void sendTrendDataStatus(OutputStream out) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] trendCodes = { TREND_VT, TREND_RR, TREND_PAW, TREND_PEEP, TREND_FIO2, TREND_MV };
        int count;
        synchronized (trendSamples) {
            count = trendSamples.size();
        }
        long begin = count == 0 ? 0L : medibusTimestamp(System.currentTimeMillis() - ((long) count - 1L) * 60000L);
        for (int code : trendCodes) {
            data.write('2');
            data.write('4');
            data.write(asciiHexHi(code));
            data.write(asciiHexLo(code));
            data.write(String.format("%06X", Integer.valueOf(count)).getBytes());
            data.write(String.format("%08X", Long.valueOf(begin)).getBytes());
        }
        sendDataResponse(CMD_REQ_TREND_STATUS, data.toByteArray(), out);
        System.out.println("       -> " + trendCodes.length + " trend parameters available, " +
                trendSamples.size() + " samples stored");
    }

    /**
     * Request Trend Data (0x6D) response.
     * Argument: CODE PAGE(2) + DATA CODE(2) + COUNT(2) + BEGIN(8).
     * Response: CODE PAGE + DATA CODE + COUNT + VALUE/TIME pairs.
     */
    private void sendTrendData(byte[] argument, OutputStream out) throws IOException {
        int codepage = 0x24;
        int requestedCode = 0;
        int requestedCount = 0xFF;
        if (argument != null && argument.length >= 4) {
            codepage = asciiHexDecode(argument[0], argument[1]);
            requestedCode = asciiHexDecode(argument[2], argument[3]);
            if (argument.length >= 6) {
                requestedCount = asciiHexDecode(argument[4], argument[5]);
            }
        } else if (argument != null && argument.length >= 2) {
            requestedCode = asciiHexDecode(argument[0], argument[1]);
        }

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(asciiHexHi(codepage));
        data.write(asciiHexLo(codepage));
        data.write(asciiHexHi(requestedCode));
        data.write(asciiHexLo(requestedCode));

        int count;
        synchronized (trendSamples) {
            count = Math.min(Math.min(requestedCount, 255), trendSamples.size());
            data.write(asciiHexHi(count));
            data.write(asciiHexLo(count));
            int start = Math.max(0, trendSamples.size() - count);
            long baseTime = System.currentTimeMillis() - ((long) trendSamples.size() - 1L) * 60000L;
            for (int i = start; i < trendSamples.size(); i++) {
                double[] sample = trendSamples.get(i);
                double val = getTrendValue(sample, requestedCode);
                String formatted = formatTrendValue(val, requestedCode);
                data.write(formatted.getBytes());
                long ts = medibusTimestamp(baseTime + (long) i * 60000L);
                data.write(String.format("%08X", Long.valueOf(ts)).getBytes());
            }
        }

        sendDataResponse(CMD_REQ_TREND_DATA, data.toByteArray(), out);
        System.out.println("       -> Trend data for code 0x" + hex(requestedCode) +
                ": " + trendSamples.size() + " samples");
    }

    private long medibusTimestamp(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        int year = Math.max(0, c.get(Calendar.YEAR) - 1980) & 0x7F;
        int month = (c.get(Calendar.MONTH) + 1) & 0x0F;
        int day = c.get(Calendar.DAY_OF_MONTH) & 0x1F;
        int hour = c.get(Calendar.HOUR_OF_DAY) & 0x1F;
        int minute = c.get(Calendar.MINUTE) & 0x3F;
        int second = (c.get(Calendar.SECOND) / 2) & 0x1F;
        return ((long) year << 25) | ((long) month << 21) | ((long) day << 16) |
                ((long) hour << 11) | ((long) minute << 5) | second;
    }

    private double getTrendValue(double[] sample, int code) {
        switch (code) {
            case TREND_VT:   return sample[0] / 1000.0; // mL -> L
            case TREND_RR:   return sample[1];
            case TREND_PAW:  return sample[2];
            case TREND_PEEP: return sample[3];
            case TREND_FIO2: return sample[4];
            case TREND_MV:   return sample[5];
            default: return 0;
        }
    }

    private String formatTrendValue(double val, int code) {
        switch (code) {
            case TREND_VT:   return formatValue(val, "X.XX");
            case TREND_RR:   return formatValue(val, "XXX_");
            case TREND_PAW:  return formatValue(val, "*_XX_");
            case TREND_PEEP: return formatValue(val, "*_XX_");
            case TREND_FIO2: return formatValue(val, "XXX_");
            case TREND_MV:   return formatValue(val, "XX.X");
            default:         return formatValue(val, "XXXX");
        }
    }

    // =========================================================================
    // Record builders
    // =========================================================================

    /**
     * Measured data record: DATA_CODE(2 ASCII HEX) + DATA(4 ASCII chars)
     * Total: 6 bytes per record.
     */
    private void addMeasuredRecord(ByteArrayOutputStream data, int code, double value, String format) throws IOException {
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
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
     * Priority is encoded as ASCII: priority + 0x30 per spec.
     */
    private void addAlarmRecord(ByteArrayOutputStream data, int priority, int code, String phrase) throws IOException {
        data.write(0x30 + priority); // Priority encoded per spec
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        String paddedPhrase = padRight(phrase, 12);
        data.write(paddedPhrase.substring(0, 12).getBytes());
    }

    /**
     * Text message record: CODE(2 hex) + LENGTH(1 byte) + TEXT + ETX(0x03)
     */
    private void addTextRecord(ByteArrayOutputStream data, int code, String text) throws IOException {
        data.write(asciiHexHi(code));
        data.write(asciiHexLo(code));
        int len = Math.min(text.length(), 32);
        data.write(0x30 + len);
        data.write(text.substring(0, len).getBytes());
        data.write(ETX);
    }

    /**
     * Data response frame: SOH + CmdEcho + RESPONSE + Checksum + CR
     */
    private void sendDataResponse(int cmdCode, byte[] responseData, OutputStream out) throws IOException {
        if (transmissionSuspended) { return; }
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

    // =========================================================================
    // Vital sign simulation
    // =========================================================================

    private void updateVitals() {
        if (loadControlFile()) {
            tidalVolume = number("tidalVolume", tidalVolume);
            respRate = number("respRate", respRate);
            peakPressure = number("peakPressure", peakPressure);
            peep = number("peep", peep);
            fio2 = number("fio2", fio2);
            compliance = number("compliance", compliance);
            resistance = number("resistance", resistance);
            airwayTemp = number("airwayTemp", airwayTemp);
            minuteVolume = tidalVolume * respRate / 1000.0;
            meanPressure = peakPressure * 0.55;
            plateauPressure = peakPressure * 0.82;
            spontRespRate = Math.max(0, respRate * 0.25);
            spontMinVol = spontRespRate * tidalVolume / 1000.0;
            inspFlow = Math.max(0, tidalVolume / 1000.0 / Math.max(0.1, 60.0 / Math.max(1.0, respRate)) * 60.0);
            return;
        }
        double t = cycleCount * 0.05;
        double breathVariation = Math.sin(t) * 0.08;
        double drift = Math.sin(t * 0.3) * 0.03;

        tidalVolume = clamp(450 + 450 * breathVariation + rng.nextGaussian() * 10, 200, 800);
        respRate = clamp(16 + 4 * Math.sin(t * 0.2) + rng.nextGaussian() * 0.5, 8, 35);
        minuteVolume = tidalVolume * respRate / 1000.0;
        peakPressure = clamp(22 + 5 * breathVariation + rng.nextGaussian() * 1, 10, 45);
        meanPressure = clamp(peakPressure * 0.55 + rng.nextGaussian() * 0.5, 5, 30);
        plateauPressure = clamp(peakPressure * 0.82 + rng.nextGaussian() * 0.3, 8, 40);
        minPressure = clamp(-2 + rng.nextGaussian() * 1.5, -10, 5);
        peep = clamp(5.0 + rng.nextGaussian() * 0.2, 3, 15);
        compliance = clamp(45 + 10 * drift + rng.nextGaussian() * 2, 20, 80);
        resistance = clamp(12 + 3 * drift + rng.nextGaussian() * 1, 5, 30);
        fio2 = clamp(40 + rng.nextGaussian() * 0.5, 21, 100);
        airwayTemp = clamp(34 + rng.nextGaussian() * 0.3, 30, 38);
        spontRespRate = clamp(4 + 2 * Math.sin(t * 0.15) + rng.nextGaussian() * 0.5, 0, 20);
        spontMinVol = spontRespRate * 0.35;
        inspFlow = clamp(40 + rng.nextGaussian() * 2, 20, 80);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private boolean loadControlFile() {
        if (controlFile == null || !controlFile.isFile()) return false;
        long modified = controlFile.lastModified();
        if (modified != controlFileLastModified) {
            try (FileInputStream in = new FileInputStream(controlFile)) {
                control.clear();
                control.load(in);
                controlFileLastModified = modified;
            } catch (IOException e) {
                System.out.println("  [warn] Could not read control file: " + e.getMessage());
            }
        }
        return !control.isEmpty();
    }

    private double number(String key, double fallback) {
        String value = control.getProperty(key);
        if (value == null || value.trim().isEmpty()) return fallback;
        try { return Double.parseDouble(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    // =========================================================================
    // ASCII HEX formatting per MEDIBUS spec
    // =========================================================================

    /**
     * Write 2-byte ASCII HEX checksum (LSB of sum).
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
     *
     * Negative value formatting: when value is negative, first character is '-'.
     */
    private String formatValue(double value, String format) {
        boolean canBeNegative = format.startsWith("*");
        String fmt = canBeNegative ? format.substring(1) : format;

        int decPos = fmt.indexOf('.');
        String valStr;
        if (decPos >= 0) {
            int decimals = fmt.length() - decPos - 1;
            double scaled = Math.abs(value);
            for (int i = 0; i < decimals; i++) scaled *= 10;
            int intVal = (int) Math.round(scaled);
            String raw = String.valueOf(intVal);
            while (raw.length() < decimals + 1) raw = "0" + raw;
            valStr = raw.substring(0, raw.length() - decimals) + "." + raw.substring(raw.length() - decimals);
        } else {
            int intVal = (int) Math.round(Math.abs(value));
            valStr = String.valueOf(intVal);
        }

        // Pad to 4 characters
        while (valStr.length() < 4) valStr = " " + valStr;
        if (valStr.length() > 4) valStr = valStr.substring(valStr.length() - 4);

        // Handle negative: first character becomes '-'
        if (canBeNegative && value < 0) {
            valStr = "-" + valStr.substring(1);
        }

        return valStr;
    }

    /**
     * Format a setting value into a 5-character ASCII string.
     */
    private String formatSettingValue(double value, String format) {
        // Use 5-char format: strip leading format chars if needed
        boolean canBeNegative = format.startsWith("*");
        String fmt = canBeNegative ? format.substring(1) : format;

        int decPos = fmt.indexOf('.');
        String valStr;
        if (decPos >= 0) {
            int decimals = fmt.length() - decPos - 1;
            double scaled = Math.abs(value);
            for (int i = 0; i < decimals; i++) scaled *= 10;
            int intVal = (int) Math.round(scaled);
            String raw = String.valueOf(intVal);
            while (raw.length() < decimals + 1) raw = "0" + raw;
            valStr = raw.substring(0, raw.length() - decimals) + "." + raw.substring(raw.length() - decimals);
        } else {
            int intVal = (int) Math.round(Math.abs(value));
            valStr = String.valueOf(intVal);
        }

        while (valStr.length() < 5) valStr = " " + valStr;
        if (valStr.length() > 5) valStr = valStr.substring(valStr.length() - 5);

        if (canBeNegative && value < 0) {
            valStr = "-" + valStr.substring(1);
        }

        return valStr;
    }

    private static String padRight(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String hex(int v) {
        return String.format("%02X", v & 0xFF);
    }

    private static String formatCodeSet(Set<Integer> codes) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int c : codes) {
            if (!first) sb.append(", ");
            sb.append("0x").append(hex(c));
            first = false;
        }
        sb.append("]");
        return sb.toString();
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
            case CMD_REQ_ALARMS_CP3:  return "ReqAlarmsCP3";
            case CMD_REQ_ALARMS_CP1:  return "ReqAlarmsCP1";
            case CMD_REQ_ALARMS_CP2:  return "ReqAlarmsCP2";
            case CMD_REQ_SETTINGS:    return "ReqDeviceSettings";
            case CMD_REQ_TEXT_MSG:    return "ReqTextMessages";
            case CMD_CONFIGURE_RESP:  return "ConfigureDataResponse";
            case CMD_REQ_RT_CONFIG:   return "ReqRealtimeConfig";
            case CMD_CONFIG_RT:       return "ConfigureRealtime";
            case CMD_TIME_CHANGED:    return "TimeChanged";
            case CMD_REQ_TREND_STATUS:return "ReqTrendDataStatus";
            case CMD_REQ_TREND_DATA:  return "ReqTrendData";
            default: return "Unknown(0x" + hex(cmd) + ")";
        }
    }

    // =========================================================================
    // Device models
    // =========================================================================

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
        MODELS.put("evita",  new DeviceModel("8210", "Evita",      "01.00:03.00"));
        MODELS.put("evita2", new DeviceModel("8200", "Evita 2",    "01.00:03.00"));
        MODELS.put("evita4", new DeviceModel("8214", "Evita 4",    "02.00:03.00"));
        MODELS.put("v500",   new DeviceModel("8410", "Evita V500", "03.20:06.00"));
        MODELS.put("savina", new DeviceModel("8310", "Savina",     "01.00:03.00"));
        MODELS.put("fabius", new DeviceModel("8088", "Fabius GS",  "02.02:04.00"));
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        String mode = "tcp";
        int port = 9100;
        String serialPath = null;
        String modelName = "evita";
        String controlPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tcp":    mode = "tcp"; port = Integer.parseInt(args[++i]); break;
                case "--serial": mode = "serial"; serialPath = args[++i]; break;
                case "--model":  modelName = args[++i].toLowerCase(); break;
                case "--control-file": controlPath = args[++i]; break;
                case "--help":
                    System.out.println("Usage: java MedibusSimulatorV2 [--tcp <port>] [--serial <path>] [--model <name>] [--control-file <properties>]");
                    System.out.println("Models: " + String.join(", ", MODELS.keySet()));
                    System.out.println();
                    System.out.println("Features:");
                    System.out.println("  - Full MEDIBUS protocol (ICC, NOP, STOP, NAK, DC1/DC3)");
                    System.out.println("  - Measured data CP1/CP2 with configurable data code filtering");
                    System.out.println("  - Realtime waveform data (airway pressure, flow, volume)");
                    System.out.println("  - Device settings (8 Evita settings)");
                    System.out.println("  - Text messages (ventilation mode)");
                    System.out.println("  - Dynamic alarm simulation");
                    System.out.println("  - Trend data storage and retrieval");
                    System.out.println("  - Negative pressure formatting");
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

        MedibusSimulatorV2 sim = new MedibusSimulatorV2(model);
        if (controlPath != null) {
            sim.controlFile = new File(controlPath);
            System.out.println("Using control file: " + sim.controlFile.getAbsolutePath());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));

        if ("serial".equals(mode)) {
            sim.runSerial(serialPath);
        } else {
            sim.runTcp(port);
        }
    }
}
