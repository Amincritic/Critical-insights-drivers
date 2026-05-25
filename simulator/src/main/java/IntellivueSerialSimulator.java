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
 *   ../gateway/gradlew installDist
 *
 *   # With socat virtual serial ports
 *   socat -d -d pty,raw,echo=0,link=/tmp/philips-device pty,raw,echo=0,link=/tmp/philips-gateway &
 *   java -cp build/install/simulator/lib/* IntellivueSerialSimulator --serial /tmp/philips-device
 *   ./run.sh --philips-serial /tmp/philips-gateway --stdout
 *
 *   # Or direct file path (real serial port)
 *   java -cp build/install/simulator/lib/* IntellivueSerialSimulator --serial /dev/ttyS0
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
    static final int NOM_MOC_VMO_METRIC_NU = 0x0006;
    static final int NOM_MOC_VMO_METRIC_SA_RT = 0x0009;
    static final int NOM_MOC_VMO_AL_MON = 0x0036;
    static final int NOM_MOC_PT_DEMOG = 0x002A;
    static final int NOM_MOC_VMO_METRIC_ENUM = 0x000A;
    static final int NOM_PART_OBJ = 1;
    static final int NOM_ACT_POLL_MDIB_DATA = 0x0C16;
    static final int NOM_ACT_POLL_MDIB_DATA_EXT = 0xF13B;
    static final int NOM_ATTR_DEV_AL_COND = 0x0916;
    static final int NOM_ATTR_AL_MON_T_AL_LIST = 0x0904;
    static final int NOM_ATTR_AL_MON_P_AL_LIST = 0x0902;

    // Physio IDs (NOM_PART_SCADA = 2)
    static final int NOM_ECG_CARD_BEAT_RATE = 0x4182;
    static final int NOM_PULS_OXIM_SAT_O2 = 0x4BB8;
    static final int NOM_PULS_OXIM_PULS_RATE = 0x480A;
    static final int NOM_RESP_RATE = 0x500A;
    static final int NOM_PRESS_BLD_ART_ABP_SYS = 0x4A15;
    static final int NOM_PRESS_BLD_ART_ABP_DIA = 0x4A16;
    static final int NOM_PRESS_BLD_ART_ABP_MEAN = 0x4A17;
    static final int NOM_PRESS_BLD_NONINV_SYS = 0x4A05;
    static final int NOM_PRESS_BLD_NONINV_DIA = 0x4A06;
    static final int NOM_PRESS_BLD_NONINV_MEAN = 0x4A07;
    static final int NOM_CO2_ET = 0x50B0;
    static final int NOM_TEMP_BLD = 0xE014;
    static final int NOM_ECG_ELEC_POTL_II_SA = 0x0102;
    static final int NOM_PLETH_PULS_OXIM_SA = 0x4BB4;
    static final int NOM_PRESS_BLD_ART_ABP_SA = 0x4A14;
    static final int NOM_RESP_SA = 0x5000;

    // Units (NOM_PART_DIM = 4)
    static final int NOM_DIM_PERCENT = 0x0220;
    static final int NOM_DIM_BEAT_PER_MIN = 0x0AA0;
    static final int NOM_DIM_MMHG = 0x0F20;
    static final int NOM_DIM_RESP_PER_MIN = 0x0AE0;
    static final int NOM_DIM_DEGC = 0x17A0;
    static final int NOM_DIM_DIMLESS = 0x0200;
    static final int NOM_DIM_MILLI_VOLT = 0x0F72;

    static final int AL_MED = 2;
    static final int AL_HI = 3;
    static final int NOM_EVT_HI_HR = 0x0402;
    static final int NOM_EVT_LO_HR = 0x0403;
    static final int NOM_EVT_LO_SAT_O2 = 0x0404;
    static final int NOM_EVT_HI_ABP_SYS = 0x0410;

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
    private File controlFile;
    private long controlFileLastModified = -1;
    private final Properties control = new Properties();

    // Simulated vitals
    private double heartRate = 72, spo2 = 97, pulseRate = 73, respRate = 16;
    private double abpSys = 120, abpDia = 80, nbpSys = 118, nbpDia = 76;
    private double etCo2 = 38, temp = 37.0;
    private double ecgPhase = 0, plethPhase = 0, abpWavePhase = 0, respWavePhase = 0;

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
                if (inFrame && escaped) {
                    if (pos < buf.length) {
                        buf[pos++] = (byte)(b ^ 0x20);
                    }
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
                    buf[pos++] = (byte) b;
                }
            }
        }
    }

    private void processFrame(byte[] buf, int len, OutputStream out) throws IOException {
        if (len < 6) {
            System.out.println("  [warn] Short frame discarded: len=" + len);
            return;
        }

        int dataLen = len - 2;
        int receivedFcs = (buf[dataLen] & 0xFF) | ((buf[dataLen + 1] & 0xFF) << 8);
        int fcs = INIT_FCS;
        for (int i = 0; i < dataLen; i++) {
            fcs = (fcs >> 8) ^ FCSTAB[(fcs ^ (buf[i] & 0xFF)) & 0xFF];
        }
        boolean badFcs = (0xFFFF ^ receivedFcs) != fcs;

        int protocolId = buf[0] & 0xFF;
        int msgType = buf[1] & 0xFF;
        int userDataLen = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);

        if (protocolId != PROTOCOL_ID || msgType != MSG_TYPE) {
            System.out.println("  [warn] Unknown protocol_id=0x" + hex(protocolId) +
                    " msg_type=0x" + hex(msgType));
            return;
        }
        if (userDataLen != dataLen - 4) {
            System.out.println("  [warn] Invalid user data length header=" + userDataLen +
                    " actual=" + (dataLen - 4));
            return;
        }
        if (badFcs) {
            System.out.println("  [warn] Bad FCS - frame discarded received=0x" +
                    hex(0xFFFF ^ receivedFcs) + " calculated=0x" + hex(fcs));
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
            bb.position(0);
            bb.getShort(); // session id 0xE100
            bb.getShort(); // presentation context id
            int roType = bb.getShort() & 0xFFFF;

            if (roType == ROIV_APDU) {
                bb.getShort(); // ro length
                int clientInvokeId = bb.getShort() & 0xFFFF;
                int cmdType = bb.getShort() & 0xFFFF;

                if (cmdType == CMD_CONFIRMED_ACTION) {
                    pollNumber++;
                    updateVitals();
                    int requestedObjectClass = requestedObjectClass(bb);
                    System.out.println("  <- Poll Request #" + pollNumber + " objectClass=0x" + hex(requestedObjectClass));

                    sendSerialFrame(out, buildPollResult(clientInvokeId, requestedObjectClass));
                    System.out.println("  -> Poll Result: " + pollSummary(requestedObjectClass));
                } else {
                    sendSerialFrame(out, buildEmptyResult(clientInvokeId, cmdType));
                }
            }
        } else {
            System.out.println("  <- Unknown message type: 0x" + hex(firstByte));
        }
    }

    private int requestedObjectClass(ByteBuffer bb) {
        int originalPosition = bb.position();
        try {
            if (bb.remaining() < 16) return NOM_MOC_VMO_METRIC_NU;
            bb.getShort(); // command length
            bb.getShort(); // managed object class
            bb.getShort(); // managed object context
            bb.getShort(); // managed object handle
            bb.getInt();   // scope
            bb.getShort(); // action type
            int actionLength = bb.getShort() & 0xFFFF;
            if (actionLength >= 8 && bb.remaining() >= 8) {
                bb.getShort(); // poll number
                bb.getShort(); // object partition
                return bb.getShort() & 0xFFFF;
            }
        } catch (RuntimeException ignored) {
            return NOM_MOC_VMO_METRIC_NU;
        } finally {
            bb.position(originalPosition);
        }
        return NOM_MOC_VMO_METRIC_NU;
    }

    private String pollSummary(int requestedObjectClass) {
        switch (requestedObjectClass) {
            case NOM_MOC_VMO_METRIC_SA_RT:
                return "waveforms ECG/PLETH/ABP/RESP";
            case NOM_MOC_VMO_AL_MON:
                return "alarms HR=" + (int) heartRate + " SpO2=" + (int) spo2 + " ABPsys=" + (int) abpSys;
            case NOM_MOC_PT_DEMOG:
                return "patient demographics empty";
            case NOM_MOC_VMO_METRIC_NU:
            default:
                return "HR=" + (int)heartRate +
                        " SpO2=" + (int)spo2 + " RR=" + (int)respRate +
                        " ABP=" + (int)abpSys + "/" + (int)abpDia +
                        " etCO2=" + (int)etCo2 + " T=" + String.format("%.1f", temp);
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
        ByteBuffer userInfo = ByteBuffer.allocate(256);
        userInfo.order(ByteOrder.BIG_ENDIAN);
        userInfo.putInt((int) 0x80000000L);
        userInfo.putInt((int) 0x40000000L);
        userInfo.putInt(0x00000000);
        userInfo.putInt((int) 0x00800000L);
        userInfo.putInt((int) 0x20000000L);
        userInfo.putShort((short) 0);
        userInfo.putShort((short) 0);

        ByteBuffer attrs = ByteBuffer.allocate(200);
        attrs.order(ByteOrder.BIG_ENDIAN);

        ByteBuffer pps = ByteBuffer.allocate(64);
        pps.order(ByteOrder.BIG_ENDIAN);
        pps.putInt(0x80000000);
        pps.putInt(80000);
        pps.putInt(1456);
        pps.putInt(1456);
        pps.putInt(0x00000000);
        pps.putInt(0x60000000);
        pps.putShort((short) 0);
        pps.putShort((short) 0);
        pps.flip();
        attrs.putShort((short) 0x0001);
        attrs.putShort((short) pps.remaining());
        attrs.put(pps);

        ByteBuffer mos = ByteBuffer.allocate(100);
        mos.order(ByteOrder.BIG_ENDIAN);
        int numClasses = 6;
        mos.putShort((short) numClasses);
        mos.putShort((short) (numClasses * 8));
        mos.putShort((short) NOM_MOC_VMS_MDS); mos.putShort((short) 0); mos.putInt(1);
        mos.putShort((short) NOM_MOC_VMO_METRIC_NU); mos.putShort((short) 0); mos.putInt(64);
        mos.putShort((short) NOM_MOC_VMO_METRIC_SA_RT); mos.putShort((short) 0); mos.putInt(16);
        mos.putShort((short) NOM_MOC_VMO_AL_MON); mos.putShort((short) 0); mos.putInt(2);
        mos.putShort((short) NOM_MOC_PT_DEMOG); mos.putShort((short) 0); mos.putInt(1);
        mos.putShort((short) NOM_MOC_VMO_METRIC_ENUM); mos.putShort((short) 0); mos.putInt(16);
        mos.flip();
        attrs.putShort((short) 0x0102);
        attrs.putShort((short) mos.remaining());
        attrs.put(mos);
        attrs.flip();

        userInfo.putShort((short) 2);
        userInfo.putShort((short) attrs.remaining());
        userInfo.put(attrs);
        userInfo.flip();
        int userInfoLen = userInfo.remaining();

        byte[] asnLen = lengthInfoBytes(userInfoLen);
        byte[] presHeader = new byte[] {
            0x31, (byte)0x80, (byte)0xA0, (byte)0x80, (byte)0x80, 0x01, 0x01, 0x00, 0x00,
            (byte)0xA2, (byte)0x80, (byte)0xA0, 0x03, 0x00, 0x00, 0x01, (byte)0xA5, (byte)0x80,
            0x30, (byte)0x80, (byte)0x80, 0x01, 0x00, (byte)0x81, 0x02, 0x51, 0x01, 0x00, 0x00,
            0x30, (byte)0x80, (byte)0x80, 0x01, 0x00, (byte)0x81, 0x0C, 0x2A, (byte)0x86, 0x48,
            (byte)0xCE, 0x14, 0x02, 0x01, 0x00, 0x00, 0x00, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x61, (byte)0x80, 0x30, (byte)0x80, 0x02, 0x01, 0x01, (byte)0xA0, (byte)0x80, 0x61,
            (byte)0x80, (byte)0xA1, (byte)0x80, 0x06, 0x0C, 0x2A, (byte)0x86, 0x48, (byte)0xCE,
            0x14, 0x02, 0x01, 0x00, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, (byte)0xA2, 0x03, 0x02,
            0x01, 0x00, (byte)0xA3, 0x05, (byte)0xA1, 0x03, 0x02, 0x01, 0x00, (byte)0xBE,
            (byte)0x80, 0x28, (byte)0x80, 0x02, 0x01, 0x02, (byte)0x81
        };
        byte[] presTrailer = new byte[16];
        byte[] sessionPI = new byte[] {
            0x05, 0x08, 0x13, 0x01, 0x00, 0x16, 0x01, 0x02, (byte)0x80, 0x00,
            0x14, 0x02, 0x00, 0x02
        };

        int presContentLen = presHeader.length + asnLen.length + userInfoLen + presTrailer.length;
        int bodyLen = sessionPI.length + 1 + sessionLengthByteCount(presContentLen) + presContentLen;

        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ASSOC_RESP);
        if (bodyLen < 255) {
            buf.put((byte) bodyLen);
        } else {
            buf.put((byte) 0xFF);
            buf.putShort((short) bodyLen);
        }
        buf.put(sessionPI);
        buf.put((byte) 0xC1);
        if (presContentLen < 255) {
            buf.put((byte) presContentLen);
        } else {
            buf.put((byte) 0xFF);
            buf.putShort((short) presContentLen);
        }
        buf.put(presHeader);
        buf.put(asnLen);
        buf.put(userInfo);
        buf.put(presTrailer);
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private static byte[] lengthInfoBytes(int len) {
        if (len < 128) return new byte[] { (byte) len };
        if (len < 256) return new byte[] { (byte) 0x81, (byte) len };
        return new byte[] { (byte) 0x82, (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF) };
    }

    private static int sessionLengthByteCount(int len) {
        return len < 255 ? 1 : 3;
    }

    private byte[] buildMdsCreateEvent() {
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100);
        buf.putShort((short) 0x0002);

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
        buf.putShort(roLenPos, (short)(end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short)(end - cmdLenPos - 2));

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private byte[] buildPollResult(int clientInvokeId, int requestedObjectClass) {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100);
        buf.putShort((short) 0x0002);

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

        buf.putShort((short) (NOM_ACT_POLL_MDIB_DATA & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePollHeader(buf, requestedObjectClass);

        // Poll info list
        buf.putShort((short) 1); // 1 context
        int infoLenPos = buf.position();
        buf.putShort((short) 0);
        int infoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        switch (requestedObjectClass) {
            case NOM_MOC_VMO_METRIC_SA_RT:
                writeWaveformObservations(buf);
                break;
            case NOM_MOC_VMO_AL_MON:
                writeAlarmObservations(buf);
                break;
            case NOM_MOC_PT_DEMOG:
                writeEmptyObservations(buf);
                break;
            case NOM_MOC_VMO_METRIC_NU:
            default:
                writeNumericObservations(buf);
                break;
        }

        int obsEnd = buf.position();
        buf.putShort(infoLenPos, (short)(obsEnd - infoStart));
        buf.putShort(actionLenPos, (short)(obsEnd - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short)(obsEnd - cmdLenPos - 2));
        buf.putShort(roLenPos, (short)(obsEnd - roLenPos - 2));

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return data;
    }

    private void writePollHeader(ByteBuffer buf, int requestedObjectClass) {
        buf.putShort((short) pollNumber);
        buf.putInt((int)(System.currentTimeMillis() * 8));
        buf.putLong(0xFFFFFFFFFFFFFFFFL); // invalid abs time
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) requestedObjectClass);
        buf.putShort((short) 0x0000);
    }

    private void writeNumericObservations(ByteBuffer buf) {
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
            buf.putShort((short) 14);   // attr list length: id + length + value
            buf.putShort((short) 0x0950); // NOM_ATTR_NU_VAL_OBS
            buf.putShort((short) 10);   // attr value length
            buf.putShort((short) n[0]); // physio id
            buf.putShort((short) 0);    // msmt state (valid)
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
    }

    private void writeWaveformObservations(ByteBuffer buf) {
        buf.putShort((short) 4);
        int obsLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();
        writeWaveformObservation(buf, 0x50, NOM_ECG_ELEC_POTL_II_SA, generateEcgSamples(), 500, NOM_DIM_MILLI_VOLT);
        writeWaveformObservation(buf, 0x51, NOM_PLETH_PULS_OXIM_SA, generatePlethSamples(), 125, NOM_DIM_DIMLESS);
        writeWaveformObservation(buf, 0x52, NOM_PRESS_BLD_ART_ABP_SA, generateAbpSamples(), 125, NOM_DIM_MMHG);
        writeWaveformObservation(buf, 0x53, NOM_RESP_SA, generateRespSamples(), 62, NOM_DIM_DIMLESS);
        int obsEnd = buf.position();
        buf.putShort(obsLenPos, (short)(obsEnd - obsStart));
    }

    private void writeAlarmObservations(ByteBuffer buf) {
        buf.putShort((short) 1);
        int obsLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();
        writeAlarmMonitorObservation(buf, 0x60);
        int obsEnd = buf.position();
        buf.putShort(obsLenPos, (short)(obsEnd - obsStart));
    }

    private void writeEmptyObservations(ByteBuffer buf) {
        buf.putShort((short) 0);
        buf.putShort((short) 0);
    }

    private void writeWaveformObservation(ByteBuffer buf, int handle, int physioId, short[] samples, int sampleRate, int unitCode) {
        PhilipsWaveformFrame.writeObservation(buf, handle, physioId, samples, sampleRate, unitCode);
    }

    private void writeAlarmMonitorObservation(ByteBuffer buf, int handle) {
        List<int[]> physAlarms = new ArrayList<>();
        if (heartRate > 120) physAlarms.add(new int[]{NOM_EVT_HI_HR, AL_HI, NOM_ECG_CARD_BEAT_RATE});
        if (heartRate < 50) physAlarms.add(new int[]{NOM_EVT_LO_HR, AL_HI, NOM_ECG_CARD_BEAT_RATE});
        if (spo2 < 90) physAlarms.add(new int[]{NOM_EVT_LO_SAT_O2, AL_HI, NOM_PULS_OXIM_SAT_O2});
        if (abpSys > 160) physAlarms.add(new int[]{NOM_EVT_HI_ABP_SYS, AL_MED, NOM_PRESS_BLD_ART_ABP_SYS});

        buf.putShort((short) handle);
        buf.putShort((short) 3);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_DEV_AL_COND);
        buf.putShort((short) 2);
        buf.putShort((short) (physAlarms.isEmpty() ? 0 : 0x0002));

        buf.putShort((short) NOM_ATTR_AL_MON_T_AL_LIST);
        buf.putShort((short) 4);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.putShort((short) NOM_ATTR_AL_MON_P_AL_LIST);
        int pAlLenPos = buf.position();
        buf.putShort((short) 0);
        int pAlStart = buf.position();
        buf.putShort((short) physAlarms.size());
        buf.putShort((short) (physAlarms.size() * 18));
        for (int[] alarm : physAlarms) {
            writeAlarmEntry(buf, alarm[0], alarm[1], alarm[2]);
        }
        int pAlEnd = buf.position();
        buf.putShort(pAlLenPos, (short) (pAlEnd - pAlStart));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private void writeAlarmEntry(ByteBuffer buf, int alarmCode, int priority, int sourceId) {
        buf.putShort((short) sourceId); // al_source OIDType
        buf.putShort((short) alarmCode); // al_code OIDType
        buf.putShort((short) (priority >= AL_HI ? 0x0400 : 0x0200)); // patient alarm priority
        buf.putShort((short) 0x0008); // new active alert
        buf.putShort((short) NOM_MOC_VMO_METRIC_NU);
        buf.putShort((short) 0);
        buf.putShort((short) sourceId);
        buf.putShort((short) 0); // no alert-info payload
        buf.putShort((short) 0);
    }

    private short[] generateEcgSamples() {
        short[] samples = new short[500];
        double beatInterval = 60.0 / Math.max(1.0, heartRate);
        for (int i = 0; i < samples.length; i++) {
            double phase = ((ecgPhase + i / 500.0) % beatInterval) / beatInterval;
            double value = 0;
            if (phase >= 0.25 && phase < 0.30) value = Math.sin((phase - 0.25) / 0.05 * Math.PI);
            else if (phase >= 0.40 && phase < 0.55) value = 0.3 * Math.sin((phase - 0.40) / 0.15 * Math.PI);
            samples[i] = (short) Math.round(128 + value * 100 + rng.nextGaussian() * 2);
        }
        ecgPhase += samples.length / 500.0;
        return samples;
    }

    private short[] generatePlethSamples() {
        short[] samples = new short[125];
        double beatInterval = 60.0 / Math.max(1.0, pulseRate);
        for (int i = 0; i < samples.length; i++) {
            double phase = ((plethPhase + i / 125.0) % beatInterval) / beatInterval;
            double value = phase < 0.15 ? phase / 0.15 : Math.max(0, 1.0 - (phase - 0.15) / 0.85);
            samples[i] = (short) Math.round(40 + value * 180 + rng.nextGaussian() * 2);
        }
        plethPhase += samples.length / 125.0;
        return samples;
    }

    private short[] generateAbpSamples() {
        short[] samples = new short[125];
        double beatInterval = 60.0 / Math.max(1.0, heartRate);
        double mean = (abpSys + 2 * abpDia) / 3.0;
        for (int i = 0; i < samples.length; i++) {
            double phase = ((abpWavePhase + i / 125.0) % beatInterval) / beatInterval;
            double value = phase < 0.10 ? abpDia + (abpSys - abpDia) * (phase / 0.10)
                    : phase < 0.30 ? abpSys - (abpSys - mean) * ((phase - 0.10) / 0.20)
                    : mean - (mean - abpDia) * ((phase - 0.30) / 0.70);
            samples[i] = (short) Math.round(Math.max(0, Math.min(255, value)));
        }
        abpWavePhase += samples.length / 125.0;
        return samples;
    }

    private short[] generateRespSamples() {
        short[] samples = new short[62];
        double breathInterval = 60.0 / Math.max(1.0, respRate);
        for (int i = 0; i < samples.length; i++) {
            double phase = ((respWavePhase + i / 62.0) % breathInterval) / breathInterval;
            samples[i] = (short) Math.round(128 + 80 * Math.sin(phase * 2 * Math.PI));
        }
        respWavePhase += samples.length / 62.0;
        return samples;
    }

    private byte[] buildEmptyResult(int invokeId, int cmdType) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0xE100);
        buf.putShort((short) 0x0002);
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
        if (loadControlFile()) {
            heartRate = number("heartRate", heartRate);
            spo2 = number("spo2", spo2);
            pulseRate = number("pulseRate", heartRate);
            respRate = number("respRate", respRate);
            abpSys = number("abpSys", abpSys);
            abpDia = number("abpDia", abpDia);
            nbpSys = number("nbpSys", abpSys - 2);
            nbpDia = number("nbpDia", abpDia + 1);
            etCo2 = number("etCo2", etCo2);
            temp = number("temp", temp);
            return;
        }
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

    private static String hex(int v) { return String.format("%04X", v & 0xFFFF); }

    private static String dumpHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(len, 96);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (len > max) sb.append(" ... len=").append(len);
        if (len > max) {
            sb.append(" tail=");
            int start = Math.max(max, len - 16);
            for (int i = start; i < len; i++) {
                if (i > start) sb.append(' ');
                sb.append(String.format("%02X", data[i] & 0xFF));
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String serialPath = null;
        String controlPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--serial": serialPath = args[++i]; break;
                case "--control-file": controlPath = args[++i]; break;
                case "--help":
                    System.out.println("Usage: java IntellivueSerialSimulator --serial <device-path> [--control-file <properties>]");
                    System.out.println();
                    System.out.println("Simulates a Philips MX800 over MIB/RS232 fixed baudrate protocol.");
                    System.out.println("Use with socat for virtual serial ports:");
                    System.out.println("  socat -d -d pty,raw,echo=0,link=/tmp/philips-device pty,raw,echo=0,link=/tmp/philips-gateway &");
                    System.out.println("  java -cp simulator/build/install/simulator/lib/* IntellivueSerialSimulator --serial /tmp/philips-device");
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
        if (controlPath != null) {
            sim.controlFile = new File(controlPath);
            System.out.println("Using control file: " + sim.controlFile.getAbsolutePath());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));
        sim.run(serialPath);
    }
}
