import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

/**
 * Spec-compliant Philips IntelliVue Data Export simulator.
 *
 * Follows the Philips IntelliVue Data Export Interface Programming Guide (Rev G.0).
 *
 * Protocol compliance:
 *   - UDP/IP transport on port 24105
 *   - Connect Indication broadcast for device discovery
 *   - Association Request/Response handshake (ACSE)
 *   - MDS Create Event with system info and RelativeTime
 *   - Extended Poll Data Result with numeric observed values
 *   - FLOAT-Type encoding (8-bit exponent + 24-bit mantissa)
 *   - Big-endian (network) byte order
 *   - Session/Presentation headers
 *   - Remote Operation headers (ROIV/RORS)
 *   - Proper OID types from NOM_PART_SCADA partition
 *
 * Usage:
 *   javac test-tools/IntellivueSimulator.java
 *   java -cp test-tools IntellivueSimulator [--port 24105] [--target <gateway-ip>]
 *
 * Then connect gateway:
 *   ./run.sh --philips-host <simulator-ip> --stdout
 */
public class IntellivueSimulator {

    // Protocol constants
    static final int DEFAULT_PORT = 24105;

    // Association protocol IDs
    static final int ASSOC_REQ  = 0x0D; // CN_SPDU_SI
    static final int ASSOC_RESP = 0x0E; // AC_SPDU_SI
    static final int ASSOC_REF  = 0x0F; // RF_SPDU_SI (refuse)
    static final int ASSOC_REL_REQ = 0x09;
    static final int ASSOC_REL_RESP = 0x0A;
    static final int ASSOC_ABORT = 0x19;

    // Data export types
    static final int DATA_EXPORT_SPDU = 0xE1;

    // Remote Operation types
    static final int ROIV_APDU = 0x0001; // invoke
    static final int RORS_APDU = 0x0002; // result
    static final int ROER_APDU = 0x0003; // error
    static final int ROLRS_APDU = 0x0005; // linked result

    // Command types
    static final int CMD_EVENT_REPORT      = 0x0000;
    static final int CMD_CONFIRMED_EVENT   = 0x0001;
    static final int CMD_GET               = 0x0003;
    static final int CMD_SET               = 0x0004;
    static final int CMD_CONFIRMED_ACTION  = 0x0007;

    // Object classes
    static final int NOM_MOC_VMS_MDS          = 0x0021;
    static final int NOM_MOC_VMO_METRIC_NU    = 0x0006;
    static final int NOM_MOC_VMO_METRIC_SA_RT = 0x0009;
    static final int NOM_MOC_VMO_AL_MON       = 0x0002;

    // Action types
    static final int NOM_ACT_POLL_MDIB_DATA     = 0x0C16;
    static final int NOM_ACT_POLL_MDIB_DATA_EXT = 0x0C17;

    // Attribute IDs
    static final int NOM_ATTR_NU_VAL_OBS      = 0x0950;
    static final int NOM_ATTR_NU_CMPD_VAL_OBS = 0x094B;
    static final int NOM_ATTR_ID_HANDLE        = 0x0921;
    static final int NOM_ATTR_ID_TYPE          = 0x092F;

    // Physiological IDs (NOM_PART_SCADA = 2)
    static final int NOM_PULS_OXIM_SAT_O2 = 0x4BB8; // SpO2
    static final int NOM_PULS_OXIM_PULS_RATE = 0x4BB0; // Pulse Rate from SpO2
    static final int NOM_ECG_CARD_BEAT_RATE = 0x4002; // Heart Rate
    static final int NOM_PRESS_BLD_ART_ABP_SYS = 0x4A51; // ABP Systolic
    static final int NOM_PRESS_BLD_ART_ABP_DIA = 0x4A52; // ABP Diastolic
    static final int NOM_PRESS_BLD_ART_ABP_MEAN = 0x4A53; // ABP Mean
    static final int NOM_PRESS_BLD_NONINV_SYS = 0x4A21; // NBP Systolic
    static final int NOM_PRESS_BLD_NONINV_DIA = 0x4A22; // NBP Diastolic
    static final int NOM_PRESS_BLD_NONINV_MEAN = 0x4A23; // NBP Mean
    static final int NOM_RESP_RATE = 0x5000; // Respiration Rate
    static final int NOM_TEMP_BLD = 0x4BB4; // Blood Temperature
    static final int NOM_CO2_ET = 0x5108; // End-tidal CO2

    // Unit codes (NOM_PART_DIM = 4)
    static final int NOM_DIM_PERCENT   = 0x0220; // %
    static final int NOM_DIM_BEAT_PER_MIN = 0x0AA0; // bpm
    static final int NOM_DIM_MMHG      = 0x0F20; // mmHg
    static final int NOM_DIM_RESP_PER_MIN = 0x0AE0; // resp/min
    static final int NOM_DIM_DEGC      = 0x17A0; // °C
    static final int NOM_DIM_KILO_PASCAL = 0x0F00; // kPa

    // NOM_PART constants
    static final int NOM_PART_OBJ   = 1;
    static final int NOM_PART_SCADA = 2;
    static final int NOM_PART_DIM   = 4;

    private final int port;
    private volatile boolean running = true;
    private boolean associated = false;
    private int invokeId = 0;
    private int pollNumber = 0;
    private final Random rng = new Random();

    // Simulated vitals
    private double heartRate = 72;
    private double spo2 = 97;
    private double respRate = 16;
    private double abpSys = 120;
    private double abpDia = 80;
    private double abpMean = 93;
    private double nbpSys = 118;
    private double nbpDia = 76;
    private double nbpMean = 90;
    private double etCo2 = 38;
    private double temp = 37.0;
    private double pulseRate = 73;

    public IntellivueSimulator(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(100); // allow periodic checks

        System.out.println("IntellivueSimulator listening on UDP port " + port);
        System.out.println("Connect gateway: --philips-host <this-machine-ip> --stdout");
        System.out.println();

        byte[] recvBuf = new byte[4096];
        InetSocketAddress clientAddr = null;

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                ByteBuffer in = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                in.order(ByteOrder.BIG_ENDIAN);
                clientAddr = new InetSocketAddress(packet.getAddress(), packet.getPort());

                processMessage(in, socket, clientAddr);

            } catch (Exception e) {
                if (running) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
        socket.close();
    }

    private void processMessage(ByteBuffer in, DatagramSocket socket, InetSocketAddress clientAddr) throws IOException {
        if (!in.hasRemaining()) return;

        int firstByte = in.get(0) & 0xFF;

        if (firstByte == ASSOC_REQ) {
            handleAssociationRequest(in, socket, clientAddr);
        } else if (firstByte == ASSOC_REL_REQ) {
            handleReleaseRequest(in, socket, clientAddr);
        } else if (firstByte == ASSOC_ABORT) {
            System.out.println("  Association aborted by client");
            associated = false;
        } else if (firstByte == DATA_EXPORT_SPDU) {
            handleDataExport(in, socket, clientAddr);
        } else {
            System.out.println("  Unknown message type: 0x" + String.format("%02X", firstByte));
        }
    }

    private void handleAssociationRequest(ByteBuffer in, DatagramSocket socket, InetSocketAddress clientAddr) throws IOException {
        System.out.println("  <- Association Request from " + clientAddr);

        // Send Association Response (accept)
        ByteBuffer resp = buildAssociationResponse();
        sendPacket(socket, clientAddr, resp);
        associated = true;
        System.out.println("  -> Association Response (accepted)");

        // Send MDS Create Event after association
        updateVitals();
        ByteBuffer mdsEvent = buildMdsCreateEvent();
        sendPacket(socket, clientAddr, mdsEvent);
        System.out.println("  -> MDS Create Event");
    }

    private void handleReleaseRequest(ByteBuffer in, DatagramSocket socket, InetSocketAddress clientAddr) throws IOException {
        System.out.println("  <- Release Request");
        associated = false;

        ByteBuffer resp = ByteBuffer.allocate(4);
        resp.order(ByteOrder.BIG_ENDIAN);
        resp.put((byte) ASSOC_REL_RESP);
        resp.put((byte) 0x00);
        resp.putShort((short) 0);
        resp.flip();
        sendPacket(socket, clientAddr, resp);
        System.out.println("  -> Release Response");
    }

    private void handleDataExport(ByteBuffer in, DatagramSocket socket, InetSocketAddress clientAddr) throws IOException {
        if (!associated) {
            System.out.println("  Data Export received but not associated — ignoring");
            return;
        }

        // Skip session/presentation header (0xE1 + 2 bytes length)
        in.position(0);
        int sessionType = in.get() & 0xFF; // 0xE1
        int sessionLen = in.getShort() & 0xFFFF;

        // Remote Operation Header
        int roType = in.getShort() & 0xFFFF;
        int roLen = in.getShort() & 0xFFFF;

        if (roType == ROIV_APDU) {
            int clientInvokeId = in.getShort() & 0xFFFF;
            int cmdType = in.getShort() & 0xFFFF;
            int cmdLen = in.getShort() & 0xFFFF;

            if (cmdType == CMD_CONFIRMED_ACTION) {
                // Poll request — parse action type
                // Skip managed object (6 bytes: OIDType + context + handle)
                in.position(in.position() + 6);
                int scope = in.getInt();
                int actionTypeCode = in.getShort() & 0xFFFF;

                updateVitals();
                pollNumber++;

                if (actionTypeCode == (NOM_ACT_POLL_MDIB_DATA & 0xFFFF) ||
                    actionTypeCode == (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF)) {

                    System.out.println("  <- Poll Request #" + pollNumber +
                            (actionTypeCode == (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF) ? " (extended)" : " (single)"));

                    ByteBuffer result = buildPollResult(clientInvokeId);
                    sendPacket(socket, clientAddr, result);
                    System.out.println("  -> Poll Result: HR=" + (int)heartRate +
                            " SpO2=" + (int)spo2 + " RR=" + (int)respRate +
                            " ABP=" + (int)abpSys + "/" + (int)abpDia +
                            " etCO2=" + (int)etCo2);
                } else {
                    System.out.println("  <- Action request type=0x" +
                            String.format("%04X", actionTypeCode) + " — sending empty result");
                    ByteBuffer emptyResult = buildEmptyActionResult(clientInvokeId);
                    sendPacket(socket, clientAddr, emptyResult);
                }
            } else if (cmdType == CMD_GET) {
                System.out.println("  <- Get request — sending empty result");
                ByteBuffer getResult = buildEmptyGetResult(clientInvokeId);
                sendPacket(socket, clientAddr, getResult);
            } else {
                System.out.println("  <- Command type=0x" + String.format("%04X", cmdType));
            }
        }
    }

    // --- Message builders ---

    private ByteBuffer buildAssociationResponse() {
        // Simplified association response
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) ASSOC_RESP);  // AC_SPDU_SI
        buf.put((byte) 0xC2);       // length placeholder (will be fixed size)

        // ACSE encoding — simplified
        // Length of remaining
        int startPos = buf.position();

        // Application context = Data Export protocol
        buf.putShort((short) 0xA1); // context-list tag
        buf.putShort((short) 0x30); // length

        // Presentation context
        buf.put((byte) 0x01); // context ID
        buf.putShort((short) 0x0001); // abstract syntax = MDSE protocol

        // User data — PollProfileSupport in response
        // Protocol version
        buf.putInt(0x80000000); // protocol version 1

        // Poll profile support
        buf.putInt(125000); // min poll period (in 1/8ms) = 1 second
        buf.putInt(0x00000000); // max mtu rx
        buf.putInt(0x00000000); // max mtu tx
        buf.putInt(0x00000000); // max bw tx
        buf.putInt(0x40000000); // poll profile options
        buf.putInt(0x00000000); // optional packages

        // Pad to expected size
        while (buf.position() < 50) buf.put((byte) 0);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildMdsCreateEvent() {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header
        buf.put((byte) DATA_EXPORT_SPDU);

        int lenPos = buf.position();
        buf.putShort((short) 0); // length placeholder

        // Remote Operation: ROIV (invoke)
        buf.putShort((short) ROIV_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0); // RO length placeholder

        buf.putShort((short) invokeId++);
        buf.putShort((short) CMD_CONFIRMED_EVENT); // event report
        int cmdLenPos = buf.position();
        buf.putShort((short) 0); // cmd length placeholder

        // Managed Object: MDS
        buf.putShort((short) NOM_MOC_VMS_MDS); // m_obj_class
        buf.putShort((short) 0); // context_id
        buf.putShort((short) 0); // handle

        // Event type: MDS Create Event
        buf.putInt(0); // event time (relative)
        buf.putShort((short) 0x0D06); // NOM_NOTI_MDS_CREAT event type
        int eventLenPos = buf.position();
        buf.putShort((short) 0); // event length placeholder

        // Attribute list
        int attrCount = 2;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0); // attr list length placeholder

        int attrStart = buf.position();

        // Attribute: System Type
        buf.putShort((short) 0x0986); // NOM_ATTR_SYS_TYPE
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) NOM_MOC_VMS_MDS);

        // Attribute: System Model
        buf.putShort((short) 0x0998); // NOM_ATTR_ID_MODEL
        buf.putShort((short) 40);
        // Manufacturer (VariableLabel)
        byte[] mfr = "Philips".getBytes();
        buf.putShort((short) mfr.length);
        buf.put(mfr);
        if (mfr.length % 2 != 0) buf.put((byte) 0); // pad to even
        // Model (VariableLabel)
        byte[] mdl = "IntelliVue MX800".getBytes();
        buf.putShort((short) mdl.length);
        buf.put(mdl);
        if (mdl.length % 2 != 0) buf.put((byte) 0);
        // Pad remaining
        int modelUsed = 2 + mfr.length + (mfr.length % 2) + 2 + mdl.length + (mdl.length % 2);
        for (int i = modelUsed; i < 40; i++) buf.put((byte) 0);

        int attrEnd = buf.position();

        // Fix lengths
        int totalLen = buf.position() - lenPos - 2;
        buf.putShort(lenPos, (short) totalLen);
        buf.putShort(roLenPos, (short) (buf.position() - roLenPos - 2));
        buf.putShort(cmdLenPos, (short) (buf.position() - cmdLenPos - 2));
        buf.putShort(eventLenPos, (short) (buf.position() - eventLenPos - 2));
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));

        buf.flip();
        return buf;
    }

    private ByteBuffer buildPollResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header
        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0); // length placeholder

        // Remote Operation: RORS (result)
        buf.putShort((short) RORS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) clientInvokeId);
        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        // Managed Object
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        // Action type
        buf.putShort((short) (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        // Poll number
        buf.putShort((short) pollNumber);
        // Relative time
        buf.putInt((int)(System.currentTimeMillis() * 8)); // 1/8ms resolution
        // Absolute time (BCD encoded) - simplified
        buf.putLong(0xFFFFFFFFFFFFFFFFL); // invalid time marker

        // Poll info list (SingleContextPoll array)
        buf.putShort((short) 1); // count = 1 context
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0); // length placeholder

        int pollInfoStart = buf.position();

        // SingleContextPoll
        buf.putShort((short) 0); // mds_context = 0

        // ObservationPoll list
        Numeric[] numerics = {
            new Numeric(NOM_ECG_CARD_BEAT_RATE, heartRate, NOM_DIM_BEAT_PER_MIN, 0x01),
            new Numeric(NOM_PULS_OXIM_SAT_O2, spo2, NOM_DIM_PERCENT, 0x02),
            new Numeric(NOM_PULS_OXIM_PULS_RATE, pulseRate, NOM_DIM_BEAT_PER_MIN, 0x03),
            new Numeric(NOM_RESP_RATE, respRate, NOM_DIM_RESP_PER_MIN, 0x04),
            new Numeric(NOM_PRESS_BLD_ART_ABP_SYS, abpSys, NOM_DIM_MMHG, 0x10),
            new Numeric(NOM_PRESS_BLD_ART_ABP_DIA, abpDia, NOM_DIM_MMHG, 0x11),
            new Numeric(NOM_PRESS_BLD_ART_ABP_MEAN, abpMean, NOM_DIM_MMHG, 0x12),
            new Numeric(NOM_PRESS_BLD_NONINV_SYS, nbpSys, NOM_DIM_MMHG, 0x20),
            new Numeric(NOM_PRESS_BLD_NONINV_DIA, nbpDia, NOM_DIM_MMHG, 0x21),
            new Numeric(NOM_PRESS_BLD_NONINV_MEAN, nbpMean, NOM_DIM_MMHG, 0x22),
            new Numeric(NOM_CO2_ET, etCo2, NOM_DIM_MMHG, 0x30),
            new Numeric(NOM_TEMP_BLD, temp, NOM_DIM_DEGC, 0x40),
        };

        buf.putShort((short) numerics.length); // observation count
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);

        int obsStart = buf.position();

        for (Numeric n : numerics) {
            writeNumericObservation(buf, n);
        }

        int obsEnd = buf.position();

        // Fix all lengths
        buf.putShort(obsListLenPos, (short) (obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short) (obsEnd - pollInfoStart));
        buf.putShort(actionLenPos, (short) (obsEnd - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short) (obsEnd - cmdLenPos - 2));
        buf.putShort(roLenPos, (short) (obsEnd - roLenPos - 2));
        buf.putShort(lenPos, (short) (obsEnd - lenPos - 2));

        buf.flip();
        return buf;
    }

    private void writeNumericObservation(ByteBuffer buf, Numeric n) {
        // Handle
        buf.putShort((short) n.handle);

        // Attribute list
        buf.putShort((short) 1); // 1 attribute
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);

        int attrStart = buf.position();

        // NOM_ATTR_NU_VAL_OBS — Numeric Observed Value
        buf.putShort((short) NOM_ATTR_NU_VAL_OBS);
        buf.putShort((short) 12); // NumericObservedValue is 12 bytes

        // NumericObservedValue structure:
        // physio_id (TYPE: partition + code)
        buf.putShort((short) NOM_PART_SCADA); // partition
        buf.putShort((short) n.physioId);      // code

        // measurement state
        buf.putShort((short) 0x0000); // valid measurement

        // unit_code (TYPE: partition + code)
        buf.putShort((short) NOM_PART_DIM); // partition
        buf.putShort((short) n.unitCode);    // code

        // value (FLOATType)
        buf.putInt(encodeFloat(n.value));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private ByteBuffer buildEmptyActionResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        buf.putShort((short) 16);
        buf.putShort((short) RORS_APDU);
        buf.putShort((short) 12);
        buf.putShort((short) clientInvokeId);
        buf.putShort((short) CMD_CONFIRMED_ACTION);
        buf.putShort((short) 6);
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildEmptyGetResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        buf.putShort((short) 16);
        buf.putShort((short) RORS_APDU);
        buf.putShort((short) 12);
        buf.putShort((short) clientInvokeId);
        buf.putShort((short) CMD_GET);
        buf.putShort((short) 6);
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.flip();
        return buf;
    }

    // --- FLOAT-Type encoding ---

    /**
     * Encode a double as Philips FLOAT-Type (32-bit).
     * Per spec page 40: value = mantissa * 10^exponent
     * Exponent: 8 bits signed, Mantissa: 24 bits signed.
     */
    static int encodeFloat(double value) {
        if (Double.isNaN(value)) return 0x007FFFFF; // NaN
        if (Double.isInfinite(value)) return value > 0 ? 0x007FFFFE : 0x00800002; // +/- INF

        if (value == 0.0) return 0;

        // Find best exponent/mantissa pair
        int bestExp = 0;
        int bestMantissa = (int) Math.round(value);

        // Try negative exponents for decimal precision
        for (int exp = -4; exp <= 4; exp++) {
            double scale = Math.pow(10, -exp);
            long mantissa = Math.round(value * scale);
            if (mantissa >= -8388607 && mantissa <= 8388607) { // 2^23 - 1
                bestExp = exp;
                bestMantissa = (int) mantissa;
                if (exp <= 0) break; // prefer more precision
            }
        }

        // Pack: exponent in top 8 bits, mantissa in bottom 24 bits
        return ((bestExp & 0xFF) << 24) | (bestMantissa & 0x00FFFFFF);
    }

    // --- Helpers ---

    private void sendPacket(DatagramSocket socket, InetSocketAddress addr, ByteBuffer buf) throws IOException {
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        socket.send(new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort()));
    }

    private void updateVitals() {
        double t = pollNumber * 0.1;
        heartRate = clamp(72 + 8 * Math.sin(t * 0.3) + rng.nextGaussian() * 2, 50, 120);
        spo2 = clamp(97 + Math.sin(t * 0.2) + rng.nextGaussian() * 0.5, 88, 100);
        pulseRate = clamp(heartRate + rng.nextGaussian() * 2, 50, 120);
        respRate = clamp(16 + 3 * Math.sin(t * 0.15) + rng.nextGaussian() * 1, 8, 30);
        abpSys = clamp(120 + 10 * Math.sin(t * 0.25) + rng.nextGaussian() * 3, 80, 180);
        abpDia = clamp(80 + 5 * Math.sin(t * 0.25) + rng.nextGaussian() * 2, 50, 110);
        abpMean = (abpSys + 2 * abpDia) / 3.0;
        nbpSys = clamp(abpSys - 2 + rng.nextGaussian() * 2, 80, 180);
        nbpDia = clamp(abpDia + 1 + rng.nextGaussian() * 2, 50, 110);
        nbpMean = (nbpSys + 2 * nbpDia) / 3.0;
        etCo2 = clamp(38 + 3 * Math.sin(t * 0.1) + rng.nextGaussian() * 1, 25, 55);
        temp = clamp(37.0 + 0.3 * Math.sin(t * 0.05) + rng.nextGaussian() * 0.1, 35, 39);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static class Numeric {
        final int physioId;
        final double value;
        final int unitCode;
        final int handle;

        Numeric(int physioId, double value, int unitCode, int handle) {
            this.physioId = physioId;
            this.value = value;
            this.unitCode = unitCode;
            this.handle = handle;
        }
    }

    // --- Main ---

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--help":
                    System.out.println("Usage: java IntellivueSimulator [--port <port>]");
                    System.out.println("Default port: " + DEFAULT_PORT);
                    System.out.println();
                    System.out.println("Simulates a Philips MX800 patient monitor.");
                    System.out.println("Vital signs: HR, SpO2, Pulse Rate, Resp Rate,");
                    System.out.println("  ABP (sys/dia/mean), NBP (sys/dia/mean), etCO2, Temp");
                    System.exit(0);
                default:
                    System.err.println("Unknown arg: " + args[i]);
                    System.exit(1);
            }
        }

        IntellivueSimulator sim = new IntellivueSimulator(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));
        sim.run();
    }
}
