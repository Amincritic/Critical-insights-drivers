import java.io.*;
import java.nio.file.*;

/**
 * Fake Draeger MEDIBUS device for HIL (Hardware-in-the-Loop) testing.
 *
 * Reads/writes directly to a serial port or PTY device file,
 * responding to MEDIBUS poll commands with synthetic vital signs.
 *
 * Usage with socat virtual serial ports:
 *   socat -d -d pty,raw,echo=0,link=/tmp/draeger-device pty,raw,echo=0,link=/tmp/draeger-gateway &
 *   java -cp test-tools FakeDraegerSerial /tmp/draeger-device
 *
 * Then connect the gateway to the other end:
 *   ./run.sh --draeger-serial /tmp/draeger-gateway --stdout
 */
public class FakeDraegerSerial {

    static final byte SOH = 0x01;
    static final byte ESC = 0x1B;
    static final byte CR  = 0x0D;
    static final byte DC1 = 0x11;

    static final byte CMD_REQ_DEVICE_ID     = 0x52;
    static final byte CMD_REQ_DATETIME      = 0x44;
    static final byte CMD_REQ_MEASURED_CP1  = 0x24;
    static final byte CMD_REQ_MEASURED_CP2  = 0x2B;
    static final byte CMD_REQ_ALARMS_CP1    = 0x27;
    static final byte CMD_REQ_ALARMS_CP2    = 0x28;

    private final String devicePath;
    private volatile boolean running = true;
    private int cycleCount = 0;

    public FakeDraegerSerial(String devicePath) {
        this.devicePath = devicePath;
    }

    public void run() throws Exception {
        System.out.println("FakeDraegerSerial opening: " + devicePath);
        System.out.println("Waiting for gateway commands...");

        try (FileInputStream fin = new FileInputStream(devicePath);
             FileOutputStream fout = new FileOutputStream(devicePath)) {

            // Send XON to indicate ready
            fout.write(DC1);
            fout.flush();

            byte[] buf = new byte[256];
            int pos = 0;
            boolean inFrame = false;

            while (running) {
                int b = fin.read();
                if (b == -1) {
                    System.out.println("EOF on serial port, reopening...");
                    Thread.sleep(1000);
                    continue;
                }

                if (b == ESC) {
                    inFrame = true;
                    pos = 0;
                } else if (b == CR && inFrame) {
                    inFrame = false;
                    if (pos > 0) {
                        handleCommand(buf, pos, fout);
                    }
                    pos = 0;
                } else if (inFrame && pos < buf.length) {
                    buf[pos++] = (byte) b;
                }
            }
        }
    }

    private void handleCommand(byte[] buf, int len, OutputStream out) throws IOException {
        if (len < 1) return;
        byte cmd = buf[0];
        cycleCount++;

        System.out.println("  [" + cycleCount + "] Cmd: 0x" + String.format("%02X", cmd) +
                " (" + describeCmd(cmd) + ")");

        switch (cmd) {
            case CMD_REQ_DEVICE_ID:     sendDeviceId(out); break;
            case CMD_REQ_DATETIME:      sendDateTime(out); break;
            case CMD_REQ_MEASURED_CP1:  sendMeasuredCP1(out); break;
            case CMD_REQ_MEASURED_CP2:  sendMeasuredCP2(out); break;
            default:                    sendEmpty(cmd, out); break;
        }
    }

    private void sendDeviceId(OutputStream out) throws IOException {
        String id = "Evita V500  SN:HIL001  SW:03.20n";
        sendResponse(CMD_REQ_DEVICE_ID, id.getBytes(), out);
    }

    private void sendDateTime(OutputStream out) throws IOException {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String dt = String.format("%02d%02d%02d%02d%02d%02d",
                now.getDayOfMonth(), now.getMonthValue(), now.getYear() % 100,
                now.getHour(), now.getMinute(), now.getSecond());
        sendResponse(CMD_REQ_DATETIME, dt.getBytes(), out);
    }

    private void sendMeasuredCP1(OutputStream out) throws IOException {
        double v = Math.sin(cycleCount * 0.1) * 0.1 + 1.0;
        StringBuilder d = new StringBuilder();
        d.append("01").append(fmt(450 * v));   // Tidal Volume mL
        d.append("02").append(fmt(16 * v));    // Respiratory Rate /min
        d.append("03").append(fmt(7.2 * v));   // Minute Volume L/min
        d.append("05").append(fmt(22 * v));    // Airway Pressure Peak cmH2O
        d.append("06").append(fmt(5.0));       // PEEP cmH2O
        d.append("08").append(fmt(40.0));      // FiO2 %
        d.append("09").append(fmt(1.2 * v));   // Inspiratory Time s
        sendResponse(CMD_REQ_MEASURED_CP1, d.toString().getBytes(), out);
        System.out.println("       VT=" + (int)(450*v) + " RR=" + (int)(16*v) +
                " Paw=" + (int)(22*v) + " FiO2=40 PEEP=5");
    }

    private void sendMeasuredCP2(OutputStream out) throws IOException {
        double v = Math.sin(cycleCount * 0.1) * 0.1 + 1.0;
        StringBuilder d = new StringBuilder();
        d.append("30").append(fmt(45 * v));    // Compliance
        d.append("31").append(fmt(12 * v));    // Resistance
        sendResponse(CMD_REQ_MEASURED_CP2, d.toString().getBytes(), out);
    }

    private void sendEmpty(byte cmd, OutputStream out) throws IOException {
        sendResponse(cmd, new byte[0], out);
    }

    private void sendResponse(byte cmd, byte[] data, OutputStream out) throws IOException {
        int checksum = cmd;
        for (byte b : data) { checksum += (b & 0xFF); }
        checksum &= 0xFF;

        byte[] frame = new byte[data.length + 4];
        frame[0] = SOH;
        frame[1] = cmd;
        System.arraycopy(data, 0, frame, 2, data.length);
        frame[frame.length - 3] = hexHi(checksum);
        frame[frame.length - 2] = hexLo(checksum);
        frame[frame.length - 1] = CR;

        out.write(frame);
        out.flush();
    }

    private static String fmt(double val) {
        String s = String.valueOf((int) Math.round(val));
        while (s.length() < 4) s = " " + s;
        return s.length() > 4 ? s.substring(0, 4) : s;
    }

    private static byte hexHi(int v) { int n = (v >> 4) & 0x0F; return (byte)(n < 10 ? '0'+n : 'A'+n-10); }
    private static byte hexLo(int v) { int n = v & 0x0F;        return (byte)(n < 10 ? '0'+n : 'A'+n-10); }

    private static String describeCmd(byte cmd) {
        switch (cmd) {
            case CMD_REQ_DEVICE_ID:    return "DeviceId";
            case CMD_REQ_DATETIME:     return "DateTime";
            case CMD_REQ_MEASURED_CP1: return "MeasuredCP1";
            case CMD_REQ_MEASURED_CP2: return "MeasuredCP2";
            case CMD_REQ_ALARMS_CP1:   return "AlarmsCP1";
            case CMD_REQ_ALARMS_CP2:   return "AlarmsCP2";
            default: return "Unknown";
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java FakeDraegerSerial <device-path>");
            System.out.println("  e.g. java FakeDraegerSerial /tmp/draeger-device");
            System.out.println("  e.g. java FakeDraegerSerial /dev/ttyS0");
            System.exit(1);
        }
        FakeDraegerSerial device = new FakeDraegerSerial(args[0]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> device.running = false));
        device.run();
    }
}
