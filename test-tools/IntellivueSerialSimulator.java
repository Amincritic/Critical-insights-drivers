import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * Philips IntelliVue MIB/RS232 simulator using the Fixed Baudrate Protocol.
 *
 * Follows the Philips IntelliVue Data Export Interface Programming Guide (Rev G.0),
 * Chapter 4: Transport Protocols for the MIB/RS232 Interface.
 *
 * Frame format:
 *   BOF(0xC0) | Header + UserData (escaped, with FCS computed) | FCS | EOF(0xC1)
 *
 * Header:
 *   protocol_id(0x11) + msg_type(0x01) + length(2 bytes, big-endian)
 *
 * Transparency (byte escaping):
 *   For bytes 0xC0, 0xC1, 0x7D in Header/UserData/FCS:
 *     insert 0x7D before the byte, then XOR the byte with 0x20
 *
 * FCS:
 *   16-bit CRC-CCITT (PPP style), one's complement, LSB first
 *
 * Usage:
 *   javac test-tools/IntellivueSerialSimulator.java
 *
 *   # With socat virtual serial ports
 *   socat -d -d pty,raw,echo=0,link=/tmp/philips-device pty,raw,echo=0,link=/tmp/philips-gateway &
 *   java -cp test-tools IntellivueSerialSimulator --serial /tmp/philips-device
 *   ./run.sh --philips-serial /tmp/philips-gateway --stdout
 *
 *   # Or direct file path (real serial port)
 *   java -cp test-tools IntellivueSerialSimulator --serial /dev/ttyS0
 */
public class IntellivueSerialSimulator {

    // Frame markers
    static final int BOF = 0xC0;
    static final int EOF_MARKER = 0xC1;
    static final int ESCAPE = 0x7D;

    // Protocol
    static final int PROTOCOL_ID = 0x11;
    static final int MSG_TYPE = 0x01;

    // Session/RO types
    static final int DATA_EXPORT_SPDU = 0xE1;
    static final int ASSOC_REQ = 0x0D;
    static final int ASSOC_RESP = 0x0E;
    static final int ROIV_APDU = 0x0001;
    static final int RORS_APDU = 0x0002;

    // Commands
    static final int CMD_CONFIRMED_ACTION = 0x0007;
    static final int CMD_CONFIRMED_EVENT = 0x0001;
    static final int CMD_GET = 0x0003;

    // OIDs
    static final int NOM_MOC_VMS_MDS = 0x0021;
    static final int NOM_ACT_POLL_MDIB_DATA_EXT = 0x0C17;

    // Physio IDs (NOM_PART_SCADA = 2)
    static final int NOM_ECG_CARD_BEAT_RATE = 0x4002;
    static final int NOM_PULS_OXIM_SAT_O2 = 0x4BB8;
    static final int NOM_PULS_OXIM_PULS_RATE = 0x4BB0;
    static final int NOM_RESP_RATE = 0x5000;
    static final int NOM_PRESS_BLD_ART_ABP_SYS = 0x4A51;
    static final int NOM_PRESS_BLD_ART_ABP_DIA = 0x4A52;
    static final int NOM_PRESS_BLD_ART_ABP_MEAN = 0x4A53;
    static final int NOM_PRESS_BLD_NONINV_SYS = 0x4A21;
    static final int NOM_PRESS_BLD_NONINV_DIA = 0x4A22;
    static final int NOM_PRESS_BLD_NONINV_MEAN = 0x4A23;
    static final int NOM_CO2_ET = 0x5108;
    static final int NOM_TEMP_BLD = 0x4BB4;

    // Units (NOM_PART_DIM = 4)
    static final int NOM_DIM_PERCENT = 0x0220;
    static final int NOM_DIM_BEAT_PER_MIN = 0x0AA0;
    static final int NOM_DIM_MMHG = 0x0F20;
    static final int NOM_DIM_RESP_PER_MIN = 0x0AE0;
    static final int NOM_DIM_DEGC = 0x17A0;

    // CRC-CCITT lookup table (PPP FCS, same as driver)
    static final int[] FCSTAB = {
        0x0000,0x1189,0x2312,0x329b,0x4624,0x57ad,0x6536,0x74bf,
        0x8c48,0x9dc1,0xaf5a,0xbed3,0xca6c,0xdbe5,0xe97e,0xf8f7,
        0x1081,0x0108,0x3393,0x221a,0x56a5,0x472c,0x75b7,0x643e,
        0x9cc9,0x8d40,0xbfdb,0xae52,0xdaed,0xcb64,0xf9ff,0xe876,
        0x2102,0x308b,0x0210,0x1399,0x6726,0x76af,0x4434,0x55bd,
        0xad4a,0xbcc3,0x8e58,0x9fd1,0xeb6e,0xfae7,0xc87c,0xd9f5,
        0x3183,0x200a,0x1291,0x0318,0x77a7,0x662e,0x54b5,0x453c,
        0xbdcb,0xac42,0x9ed9,0x8f50,0xfbef,0xea66,0xd8fd,0xc974,
        0x4204,0x538d,0x6116,0x709f,0x0420,0x15a9,0x2732,0x36bb,
        0xce4c,0xdfc5,0xed5e,0xfcd7,0x8868,0x99e1,0xab7a,0xbaf3,
        0x5285,0x430c,0x7197,0x601e,0x14a1,0x0528,0x37b3,0x263a,
        0xdecd,0xcf44,0xfddf,0xec56,0x98e9,0x8960,0xbbfb,0xaa72,
        0x6306,0x728f,0x4014,0x519d,0x2522,0x34ab,0x0630,0x17b9,
        0xef4e,0xfec7,0xcc5c,0xddd5,0xa96a,0xb8e3,0x8a78,0x9bf1,
        0x7387,0x620e,0x5095,0x411c,0x35a3,0x242a,0x16b1,0x0738,
        0xffcf,0xee46,0xdcdd,0xcd54,0xb9eb,0xa862,0x9af9,0x8b70,
        0x8408,0x9581,0xa71a,0xb693,0xc22c,0xd3a5,0xe13e,0xf0b7,
        0x0840,0x19c9,0x2b52,0x3adb,0x4e64,0x5fed,0x6d76,0x7cff,
        0x9489,0x8500,0xb79b,0xa612,0xd2ad,0xc324,0xf1bf,0xe036,
        0x18c1,0x0948,0x3bd3,0x2a5a,0x5ee5,0x4f6c,0x7df7,0x6c7e,
        0xa50a,0xb483,0x8618,0x9791,0xe32e,0xf2a7,0xc03c,0xd1b5,
        0x2942,0x38cb,0x0a50,0x1bd9,0x6f66,0x7eef,0x4c74,0x5dfd,
        0xb58b,0xa402,0x9699,0x8710,0xf3af,0xe226,0xd0bd,0xc134,
        0x39c3,0x284a,0x1ad1,0x0b58,0x7fe7,0x6e6e,0x5cf5,0x4d7c,
        0xc60c,0xd785,0xe51e,0xf497,0x8028,0x91a1,0xa33a,0xb2b3,
        0x4a44,0x5bcd,0x6956,0x78df,0x0c60,0x1de9,0x2f72,0x3efb,
        0xd68d,0xc704,0xf59f,0xe416,0x90a9,0x8120,0xb3bb,0xa232,
        0x5ac5,0x4b4c,0x79d7,0x685e,0x1ce1,0x0d68,0x3ff3,0x2e7a,
        0xe70e,0xf687,0xc41c,0xd595,0xa12a,0xb0a3,0x8238,0x93b1,
        0x6b46,0x7acf,0x4854,0x59dd,0x2d62,0x3ceb,0x0e70,0x1ff9,
        0xf78f,0xe606,0xd49d,0xc514,0xb1ab,0xa022,0x92b9,0x8330,
        0x7bc7,0x6a4e,0x58d5,0x495c,0x3de3,0x2c6a,0x1ef1,0x0f78
    };
    static final int INIT_FCS = 0xFFFF;

    private volatile boolean running = true;
    private int invokeId = 0;
    private int pollNumber = 0;
    private final Random rng = new Random();

    // Simulated vitals
    private double heartRate = 72, spo2 = 97, pulseRate = 73, respRate = 16;
    private double abpSys = 120, abpDia = 80, nbpSys = 118, nbpDia = 76;
    private double etCo2 = 38, temp = 37.0;

    public void run(String serialPath) throws Exception {
        System.out.println("IntellivueSerialSimulator opening: " + serialPath);
        System.out.println("Protocol: MIB/RS232 Fixed Baudrate (115200 8N1)");
        System.out.println("Frame format: BOF(0xC0) + Hdr + Data + FCS + EOF(0xC1)");
        System.out.println();

        try (FileInputStream fin = new FileInputStream(serialPath);
             FileOutputStream fout = new FileOutputStream(serialPath)) {

            byte[] buf = new byte[4096];
            int pos = 0;
            boolean inFrame = false;
            boolean escaped = false;

            while (running) {
                int b = fin.read();
                if (b == -1) { Thread.sleep(100); continue; }

                if (b == BOF) {
                    inFrame = true;
                    pos = 0;
                    escaped = false;
                    continue;
                }
                if (b == EOF_MARKER && inFrame) {
                    inFrame = false;
                    if (pos >= 4) { // min: protocol_id + msg_type + length(2)
                        processFrame(buf, pos, fout);
                    }
                    continue;
                }
                if (b == ESCAPE && inFrame) {
                    escaped = true;
                    continue;
                }
                if (inFrame && pos < buf.length) {
                    if (escaped) {
                        buf[pos++] = (byte)(b ^ 0x20);
                        escaped = false;
                    } else {
                        buf[pos++] = (byte) b;
                    }
                }
            }
        }
    }

    private void processFrame(byte[] buf, int len, OutputStream out) throws IOException {
        // Verify FCS
        int fcs = INIT_FCS;
        for (int i = 0; i < len; i++) {
            fcs = (fcs >> 8) ^ FCSTAB[(fcs ^ (buf[i] & 0xFF)) & 0xFF];
        }
        if (fcs != 0xF0B8) { // GOOD_FINAL_FCS_VALUE
            System.out.println("  [warn] Bad FCS — frame discarded");
            return;
        }

        // Strip FCS (last 2 bytes) from data
        int dataLen = len - 2;

        int protocolId = buf[0] & 0xFF;
        int msgType = buf[1] & 0xFF;
        int userDataLen = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);

        if (protocolId != PROTOCOL_ID || msgType != MSG_TYPE) {
            System.out.println("  [warn] Unknown protocol_id=0x" + hex(protocolId) +
                    " msg_type=0x" + hex(msgType));
            return;
        }

        // User data starts at offset 4
        byte[] userData = new byte[dataLen - 4];
        System.arraycopy(buf, 4, userData, 0, userData.length);
        ByteBuffer bb = ByteBuffer.wrap(userData);
        bb.order(ByteOrder.BIG_ENDIAN);

        if (!bb.hasRemaining()) return;

        int firstByte = bb.get(0) & 0xFF;

        if (firstByte == ASSOC_REQ) {
            System.out.println("  <- Association Request (MIB/RS232)");
            updateVitals();

            // Send association response
            sendSerialFrame(out, buildAssocResponse());
            System.out.println("  -> Association Response");

            // Send MDS Create Event
            sendSerialFrame(out, buildMdsCreateEvent());
            System.out.println("  -> MDS Create Event");

        } else if (firstByte == DATA_EXPORT_SPDU) {
            bb.position(1);
            bb.getShort(); // session length
            int roType = bb.getShort() & 0xFFFF;

            if (roType == ROIV_APDU) {
                bb.getShort(); // ro length
                int clientInvokeId = bb.getShort() & 0xFFFF;
                int cmdType = bb.getShort() & 0xFFFF;

                if (cmdType == CMD_CONFIRMED_ACTION) {
                    pollNumber++;
                    updateVitals();
                    System.out.println("  <- Poll Request #" + pollNumber);

                    sendSerialFrame(out, buildPollResult(clientInvokeId));
                    System.out.println("  -> Poll Result: HR=" + (int)heartRate +
                            " SpO2=" + (int)spo2 + " RR=" + (int)respRate +
                            " ABP=" + (int)abpSys + "/" + (int)abpDia +
                            " etCO2=" + (int)etCo2 + " T=" + String.format("%.1f", temp));
                } else {
                    sendSerialFrame(out, buildEmptyResult(clientInvokeId, cmdType));
                }
            }
        } else {
            System.out.println("  <- Unknown message type: 0x" + hex(firstByte));
        }
    }

    /**
     * Send a Data Export message wrapped in MIB/RS232 fixed baudrate frame.
     * BOF | escaped(protocol_id + msg_type + length + userData) | FCS | EOF
     */
    private void sendSerialFrame(OutputStream out, byte[] userData) throws IOException {
        // Build unescaped frame content: header + user data
        byte[] header = new byte[4];
        header[0] = (byte) PROTOCOL_ID;
        header[1] = (byte) MSG_TYPE;
        header[2] = (byte) ((userData.length >> 8) & 0xFF);
        header[3] = (byte) (userData.length & 0xFF);

        // Compute FCS over header + userData (before escaping)
        int fcs = INIT_FCS;
        for (byte b : header) fcs = (fcs >> 8) ^ FCSTAB[(fcs ^ (b & 0xFF)) & 0xFF];
        for (byte b : userData) fcs = (fcs >> 8) ^ FCSTAB[(fcs ^ (b & 0xFF)) & 0xFF];
        int invertedFcs = ~fcs;
        byte fcsLo = (byte) (invertedFcs & 0xFF);        // LSB first
        byte fcsHi = (byte) ((invertedFcs >> 8) & 0xFF);

        // Write frame with byte escaping
        out.write(BOF);
        writeEscaped(out, header);
        writeEscaped(out, userData);
        writeEscapedByte(out, fcsLo);
        writeEscapedByte(out, fcsHi);
        out.write(EOF_MARKER);
        out.flush();
    }

    private void writeEscaped(OutputStream out, byte[] data) throws IOException {
        for (byte b : data) writeEscapedByte(out, b);
    }

    private void writeEscapedByte(OutputStream out, byte b) throws IOException {
        int v = b & 0xFF;
        if (v == BOF || v == EOF_MARKER || v == ESCAPE) {
            out.write(ESCAPE);
            out.write(v ^ 0x20);
        } else {
            out.write(v);
        }
    }

    // --- Message builders (same protocol as UDP, just different transport) ---

    private byte[] buildAssocResponse() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ASSOC_RESP);
        buf.put((byte) 0x00);
        for (int i = 0; i < 48; i++) buf.put((byte) 0);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private byte[] buildMdsCreateEvent() {
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) ROIV_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) invokeId++);
        buf.putShort((short) CMD_CONFIRMED_EVENT);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putInt(0); // relative time
        buf.putShort((short) 0x0D06); // MDS Create
        buf.putShort((short) 4);
        buf.putShort((short) 0); // empty attr list
        buf.putShort((short) 0);

        int end = buf.position();
        buf.putShort(lenPos, (short)(end - lenPos - 2));
        buf.putShort(roLenPos, (short)(end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short)(end - cmdLenPos - 2));

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private byte[] buildPollResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) RORS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) clientInvokeId);
        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.putShort((short) (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) pollNumber);
        buf.putInt((int)(System.currentTimeMillis() * 8));
        buf.putLong(0xFFFFFFFFFFFFFFFFL); // invalid abs time

        // Poll info list
        buf.putShort((short) 1); // 1 context
        int infoLenPos = buf.position();
        buf.putShort((short) 0);
        int infoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        // Numerics
        int[][] nums = {
            {NOM_ECG_CARD_BEAT_RATE, (int)heartRate, NOM_DIM_BEAT_PER_MIN, 0x01},
            {NOM_PULS_OXIM_SAT_O2, (int)spo2, NOM_DIM_PERCENT, 0x02},
            {NOM_PULS_OXIM_PULS_RATE, (int)pulseRate, NOM_DIM_BEAT_PER_MIN, 0x03},
            {NOM_RESP_RATE, (int)respRate, NOM_DIM_RESP_PER_MIN, 0x04},
            {NOM_PRESS_BLD_ART_ABP_SYS, (int)abpSys, NOM_DIM_MMHG, 0x10},
            {NOM_PRESS_BLD_ART_ABP_DIA, (int)abpDia, NOM_DIM_MMHG, 0x11},
            {NOM_PRESS_BLD_ART_ABP_MEAN, (int)((abpSys+2*abpDia)/3), NOM_DIM_MMHG, 0x12},
            {NOM_PRESS_BLD_NONINV_SYS, (int)nbpSys, NOM_DIM_MMHG, 0x20},
            {NOM_PRESS_BLD_NONINV_DIA, (int)nbpDia, NOM_DIM_MMHG, 0x21},
            {NOM_PRESS_BLD_NONINV_MEAN, (int)((nbpSys+2*nbpDia)/3), NOM_DIM_MMHG, 0x22},
            {NOM_CO2_ET, (int)etCo2, NOM_DIM_MMHG, 0x30},
            {NOM_TEMP_BLD, (int)(temp*10), NOM_DIM_DEGC, 0x40},
        };

        buf.putShort((short) nums.length);
        int obsLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (int[] n : nums) {
            buf.putShort((short) n[3]); // handle
            buf.putShort((short) 1);    // 1 attribute
            buf.putShort((short) 12);   // attr list length
            buf.putShort((short) 0x0950); // NOM_ATTR_NU_VAL_OBS
            buf.putShort((short) 12);   // attr value length
            buf.putShort((short) 2);    // NOM_PART_SCADA
            buf.putShort((short) n[0]); // physio id
            buf.putShort((short) 0);    // msmt state (valid)
            buf.putShort((short) 4);    // NOM_PART_DIM
            buf.putShort((short) n[2]); // unit code
            // FLOAT-Type value
            if (n[0] == NOM_TEMP_BLD) {
                buf.putInt((0xFF << 24) | (n[1] & 0x00FFFFFF)); // exp=-1
            } else {
                buf.putInt(n[1] & 0x00FFFFFF); // exp=0
            }
        }

        int obsEnd = buf.position();
        buf.putShort(obsLenPos, (short)(obsEnd - obsStart));
        buf.putShort(infoLenPos, (short)(obsEnd - infoStart));
        buf.putShort(actionLenPos, (short)(obsEnd - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short)(obsEnd - cmdLenPos - 2));
        buf.putShort(roLenPos, (short)(obsEnd - roLenPos - 2));
        buf.putShort(lenPos, (short)(obsEnd - lenPos - 2));

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private byte[] buildEmptyResult(int invokeId, int cmdType) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) DATA_EXPORT_SPDU);
        buf.putShort((short) 16);
        buf.putShort((short) RORS_APDU);
        buf.putShort((short) 12);
        buf.putShort((short) invokeId);
        buf.putShort((short) cmdType);
        buf.putShort((short) 6);
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private void updateVitals() {
        double t = pollNumber * 0.1;
        heartRate = clamp(72 + 8 * Math.sin(t * 0.3) + rng.nextGaussian() * 2, 50, 120);
        spo2 = clamp(97 + Math.sin(t * 0.2) + rng.nextGaussian() * 0.5, 88, 100);
        pulseRate = clamp(heartRate + rng.nextGaussian() * 2, 50, 120);
        respRate = clamp(16 + 3 * Math.sin(t * 0.15) + rng.nextGaussian() * 1, 8, 30);
        abpSys = clamp(120 + 10 * Math.sin(t * 0.25) + rng.nextGaussian() * 3, 80, 180);
        abpDia = clamp(80 + 5 * Math.sin(t * 0.25) + rng.nextGaussian() * 2, 50, 110);
        nbpSys = clamp(abpSys - 2 + rng.nextGaussian() * 2, 80, 180);
        nbpDia = clamp(abpDia + 1 + rng.nextGaussian() * 2, 50, 110);
        etCo2 = clamp(38 + 3 * Math.sin(t * 0.1) + rng.nextGaussian() * 1, 25, 55);
        temp = clamp(37.0 + 0.3 * Math.sin(t * 0.05) + rng.nextGaussian() * 0.1, 35, 39);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String hex(int v) { return String.format("%02X", v & 0xFF); }

    public static void main(String[] args) throws Exception {
        String serialPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--serial": serialPath = args[++i]; break;
                case "--help":
                    System.out.println("Usage: java IntellivueSerialSimulator --serial <device-path>");
                    System.out.println();
                    System.out.println("Simulates a Philips MX800 over MIB/RS232 fixed baudrate protocol.");
                    System.out.println("Use with socat for virtual serial ports:");
                    System.out.println("  socat -d -d pty,raw,echo=0,link=/tmp/philips-device pty,raw,echo=0,link=/tmp/philips-gateway &");
                    System.out.println("  java -cp test-tools IntellivueSerialSimulator --serial /tmp/philips-device");
                    System.out.println("  ./run.sh --philips-serial /tmp/philips-gateway --stdout");
                    System.exit(0);
                default:
                    System.err.println("Unknown arg: " + args[i]);
                    System.exit(1);
            }
        }

        if (serialPath == null) {
            System.err.println("Usage: java IntellivueSerialSimulator --serial <device-path>");
            System.exit(1);
        }

        IntellivueSerialSimulator sim = new IntellivueSerialSimulator();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));
        sim.run(serialPath);
    }
}
