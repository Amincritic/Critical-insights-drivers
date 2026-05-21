import java.io.*;
import java.net.*;

/**
 * Fake Draeger MEDIBUS device for testing the gateway without real hardware.
 *
 * Listens on a TCP port and responds to MEDIBUS poll commands with
 * synthetic vital sign data (tidal volume, respiratory rate, airway pressure, etc.).
 *
 * Usage:
 *   javac test-tools/FakeDraegerDevice.java
 *   java -cp test-tools FakeDraegerDevice [port]
 *
 * Then run the gateway against it:
 *   devices/draeger/build/install/draeger/bin/draeger \
 *     --tcp-host 127.0.0.1 --tcp-port 9100 \
 *     --device-id test_draeger --stdout true
 */
public class FakeDraegerDevice {

    // MEDIBUS framing
    static final byte SOH = 0x01;  // start of response
    static final byte ESC = 0x1B;  // start of command (from host)
    static final byte CR  = 0x0D;  // end of frame
    static final byte DC1 = 0x11;  // XON
    static final byte DC3 = 0x13;  // XOFF

    // Command codes (ASCII hex)
    static final byte CMD_REQ_DEVICE_ID     = 0x52; // 'R'
    static final byte CMD_REQ_DATETIME      = 0x44; // 'D'
    static final byte CMD_REQ_MEASURED_CP1  = 0x24; // '$'
    static final byte CMD_REQ_MEASURED_CP2  = 0x2B; // '+'
    static final byte CMD_REQ_ALARMS_CP1    = 0x27; // '\''
    static final byte CMD_REQ_ALARMS_CP2    = 0x28; // '('

    private final ServerSocket server;
    private volatile boolean running = true;
    private int cycleCount = 0;

    public FakeDraegerDevice(int port) throws IOException {
        this.server = new ServerSocket(port);
        System.out.println("FakeDraegerDevice listening on port " + port);
        System.out.println("Waiting for gateway connection...");
    }

    public void run() throws IOException {
        while (running) {
            try (Socket client = server.accept()) {
                System.out.println("Gateway connected from " + client.getRemoteSocketAddress());
                handleClient(client);
            } catch (IOException e) {
                if (running) {
                    System.err.println("Client disconnected: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        // Send initial XON to indicate ready
        out.write(DC1);
        out.flush();

        byte[] buf = new byte[256];
        int pos = 0;
        boolean inFrame = false;

        while (running && !client.isClosed()) {
            int b = in.read();
            if (b == -1) break;

            if (b == ESC) {
                inFrame = true;
                pos = 0;
            } else if (b == CR && inFrame) {
                inFrame = false;
                if (pos > 0) {
                    handleCommand(buf, pos, out);
                }
                pos = 0;
            } else if (inFrame && pos < buf.length) {
                buf[pos++] = (byte) b;
            }
        }
    }

    private void handleCommand(byte[] buf, int len, OutputStream out) throws IOException {
        if (len < 1) return;
        byte cmd = buf[0];
        cycleCount++;

        System.out.println("  Received command: 0x" + String.format("%02X", cmd) +
                " (" + describeCmdCode(cmd) + ") [cycle " + cycleCount + "]");

        switch (cmd) {
            case CMD_REQ_DEVICE_ID:
                sendDeviceId(out);
                break;
            case CMD_REQ_DATETIME:
                sendDateTime(out);
                break;
            case CMD_REQ_MEASURED_CP1:
                sendMeasuredDataCP1(out);
                break;
            case CMD_REQ_MEASURED_CP2:
                sendMeasuredDataCP2(out);
                break;
            case CMD_REQ_ALARMS_CP1:
            case CMD_REQ_ALARMS_CP2:
                sendEmptyResponse(cmd, out);
                break;
            default:
                sendEmptyResponse(cmd, out);
                break;
        }
    }

    private void sendDeviceId(OutputStream out) throws IOException {
        // Response: SOH <cmd> <data> <checksum> CR
        // Device ID format: "Evita V500  SN:12345  SW:03.20"
        String deviceId = "Evita V500  SN:FAKE001  SW:03.20n";
        sendResponse(CMD_REQ_DEVICE_ID, deviceId.getBytes(), out);
        System.out.println("    -> Device ID: " + deviceId.trim());
    }

    private void sendDateTime(OutputStream out) throws IOException {
        // MEDIBUS datetime: DDMMYYHHmmSS
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String dt = String.format("%02d%02d%02d%02d%02d%02d",
                now.getDayOfMonth(), now.getMonthValue(), now.getYear() % 100,
                now.getHour(), now.getMinute(), now.getSecond());
        sendResponse(CMD_REQ_DATETIME, dt.getBytes(), out);
        System.out.println("    -> DateTime: " + dt);
    }

    private void sendMeasuredDataCP1(OutputStream out) throws IOException {
        // CP1 measured data: 6-byte records: 2-byte code + 4-byte ASCII value
        // Simulated vitals with slight variation
        double variation = Math.sin(cycleCount * 0.1) * 0.1 + 1.0;

        StringBuilder data = new StringBuilder();
        // Code 01 = Tidal Volume (mL)
        data.append("01").append(formatValue(450 * variation));
        // Code 02 = Respiratory Rate (/min)
        data.append("02").append(formatValue(16 * variation));
        // Code 03 = Minute Volume (L/min)
        data.append("03").append(formatValue(7.2 * variation));
        // Code 05 = Airway Pressure Peak (cmH2O)
        data.append("05").append(formatValue(22 * variation));
        // Code 06 = PEEP (cmH2O)
        data.append("06").append(formatValue(5.0));
        // Code 08 = FiO2 (%)
        data.append("08").append(formatValue(40.0));
        // Code 09 = Inspiratory Time (s)
        data.append("09").append(formatValue(1.2 * variation));

        sendResponse(CMD_REQ_MEASURED_CP1, data.toString().getBytes(), out);
        System.out.println("    -> CP1: VT=" + (int)(450*variation) + "mL RR=" +
                (int)(16*variation) + " Paw=" + (int)(22*variation) + "cmH2O FiO2=40%");
    }

    private void sendMeasuredDataCP2(OutputStream out) throws IOException {
        double variation = Math.sin(cycleCount * 0.1) * 0.1 + 1.0;

        StringBuilder data = new StringBuilder();
        // Code 30 = Compliance (mL/cmH2O)
        data.append("30").append(formatValue(45 * variation));
        // Code 31 = Resistance (cmH2O/L/s)
        data.append("31").append(formatValue(12 * variation));

        sendResponse(CMD_REQ_MEASURED_CP2, data.toString().getBytes(), out);
        System.out.println("    -> CP2: Compliance=" + (int)(45*variation) + " Resistance=" + (int)(12*variation));
    }

    private void sendEmptyResponse(byte cmd, OutputStream out) throws IOException {
        sendResponse(cmd, new byte[0], out);
    }

    private void sendResponse(byte cmd, byte[] data, OutputStream out) throws IOException {
        // MEDIBUS response frame: SOH <cmd> <data> <checksum-high> <checksum-low> CR
        int checksum = cmd;
        for (byte b : data) {
            checksum += (b & 0xFF);
        }
        checksum &= 0xFF;

        byte[] frame = new byte[data.length + 4]; // SOH + cmd + data + 2 checksum + CR
        frame[0] = SOH;
        frame[1] = cmd;
        System.arraycopy(data, 0, frame, 2, data.length);
        frame[frame.length - 3] = toHexHigh(checksum);
        frame[frame.length - 2] = toHexLow(checksum);
        frame[frame.length - 1] = CR;

        out.write(frame);
        out.flush();
    }

    private static String formatValue(double val) {
        // 4-character right-justified ASCII value
        String s = String.valueOf((int) Math.round(val));
        while (s.length() < 4) { s = " " + s; }
        return s.length() > 4 ? s.substring(0, 4) : s;
    }

    private static byte toHexHigh(int val) {
        int nibble = (val >> 4) & 0x0F;
        return (byte) (nibble < 10 ? '0' + nibble : 'A' + nibble - 10);
    }

    private static byte toHexLow(int val) {
        int nibble = val & 0x0F;
        return (byte) (nibble < 10 ? '0' + nibble : 'A' + nibble - 10);
    }

    private static String describeCmdCode(byte cmd) {
        switch (cmd) {
            case CMD_REQ_DEVICE_ID:     return "ReqDeviceId";
            case CMD_REQ_DATETIME:      return "ReqDateTime";
            case CMD_REQ_MEASURED_CP1:  return "ReqMeasuredCP1";
            case CMD_REQ_MEASURED_CP2:  return "ReqMeasuredCP2";
            case CMD_REQ_ALARMS_CP1:    return "ReqAlarmsCP1";
            case CMD_REQ_ALARMS_CP2:    return "ReqAlarmsCP2";
            default: return "Unknown";
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;
        FakeDraegerDevice device = new FakeDraegerDevice(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            device.running = false;
            try { device.server.close(); } catch (IOException ignored) {}
        }));

        device.run();
    }
}
