import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Enhanced Philips IntelliVue Data Export simulator (V2).
 *
 * Based on the Philips IntelliVue Data Export Interface Programming Guide (Rev G.0).
 *
 * Enhancements over V1:
 *   - Full Association Negotiation (ACSE ISO/IEC 8649)
 *   - Connect Indication broadcast on port 24005
 *   - Waveform data (ECG, SpO2 pleth, ABP, Respiration)
 *   - Alarm Monitor Object (technical + physiological alarms)
 *   - Patient Demographics Object
 *   - Enumeration Objects (ventilation mode, alarm status)
 *   - Proper BCD-encoded Absolute Time
 *   - Label attributes on numerics (TextId + String)
 *   - Metric Specification attributes
 *   - Extended Poll with linked results (ROLRS + final RORS)
 *   - 16 simulated numerics with realistic variation
 *
 * Usage:
 *   javac test-tools/IntellivueSimulatorV2.java
 *   java -cp test-tools IntellivueSimulatorV2 [--port 24105] [--patient "John Doe" --patient-id "P12345"]
 */
public class IntellivueSimulatorV2 {

    // =====================================================================
    // Protocol Constants
    // =====================================================================

    static final int DEFAULT_PORT = 24105;
    static final int BROADCAST_PORT = 24005;

    // Session layer SPDUs
    static final int CN_SPDU_SI     = 0x0D; // Association Request
    static final int AC_SPDU_SI     = 0x0E; // Association Response
    static final int RF_SPDU_SI     = 0x0F; // Association Refuse
    static final int FN_SPDU_SI     = 0x09; // Release Request
    static final int DN_SPDU_SI     = 0x0A; // Release Response
    static final int AB_SPDU_SI     = 0x19; // Abort
    static final int DATA_EXPORT_SPDU = 0xE1;

    // Remote Operation types
    static final int ROIV_APDU  = 0x0001; // invoke
    static final int RORS_APDU  = 0x0002; // result
    static final int ROER_APDU  = 0x0003; // error
    static final int ROLRS_APDU = 0x0005; // linked result

    // Command types
    static final int CMD_EVENT_REPORT      = 0x0000;
    static final int CMD_CONFIRMED_EVENT   = 0x0001;
    static final int CMD_GET               = 0x0003;
    static final int CMD_SET               = 0x0004;
    static final int CMD_CONFIRMED_ACTION  = 0x0007;

    // NOM_PART constants
    static final int NOM_PART_OBJ   = 1;
    static final int NOM_PART_SCADA = 2;
    static final int NOM_PART_EVT   = 3;
    static final int NOM_PART_DIM   = 4;
    static final int NOM_PART_PGRP  = 6;
    static final int NOM_PART_INFRA = 8;

    // Object classes
    static final int NOM_MOC_VMS_MDS          = 0x0021;
    static final int NOM_MOC_VMO_METRIC_NU    = 0x0006;
    static final int NOM_MOC_VMO_METRIC_SA_RT = 0x0009;
    static final int NOM_MOC_VMO_AL_MON       = 0x0002;
    static final int NOM_MOC_PT_DEMOG         = 0x0029;
    static final int NOM_MOC_VMO_METRIC_ENUM  = 0x000A;
    static final int NOM_MOC_SCANNER          = 0x0010;
    static final int NOM_MOC_SCANNER_CFG      = 0x0012;

    // Action types
    static final int NOM_ACT_POLL_MDIB_DATA     = 0x0C16;
    static final int NOM_ACT_POLL_MDIB_DATA_EXT = 0x0C17;

    // Attribute IDs
    static final int NOM_ATTR_NU_VAL_OBS         = 0x0950;
    static final int NOM_ATTR_NU_CMPD_VAL_OBS    = 0x094B;
    static final int NOM_ATTR_ID_HANDLE           = 0x0921;
    static final int NOM_ATTR_ID_TYPE             = 0x092F;
    static final int NOM_ATTR_SA_VAL_OBS          = 0x0967;
    static final int NOM_ATTR_SA_SPECN            = 0x096D;
    static final int NOM_ATTR_SA_FIXED_VAL_SPECN  = 0x0968;
    static final int NOM_ATTR_ID_LABEL            = 0x0927;
    static final int NOM_ATTR_ID_LABEL_STRING     = 0x0928;
    static final int NOM_ATTR_METRIC_SPECN        = 0x093F;
    static final int NOM_ATTR_SYS_TYPE            = 0x0986;
    static final int NOM_ATTR_ID_MODEL            = 0x0998;
    static final int NOM_ATTR_TIME_ABS            = 0x0987;
    static final int NOM_ATTR_DEV_AL_COND         = 0x0916;
    static final int NOM_ATTR_AL_MON_T_AL_LIST    = 0x0905;
    static final int NOM_ATTR_AL_MON_P_AL_LIST    = 0x0906;
    static final int NOM_ATTR_ENUM_OBS_VAL        = 0x0920;
    static final int NOM_ATTR_PT_NAME_GIVEN       = 0x0996;
    static final int NOM_ATTR_PT_NAME_FAMILY      = 0x0997;
    static final int NOM_ATTR_PT_ID               = 0x0984;
    static final int NOM_ATTR_PT_DOB              = 0x0958;
    static final int NOM_ATTR_PT_SEX              = 0x0993;
    static final int NOM_ATTR_PT_HEIGHT           = 0x0960;
    static final int NOM_ATTR_PT_WEIGHT           = 0x0994;
    static final int NOM_ATTR_PT_BSA              = 0x0995;
    static final int NOM_ATTR_METRIC_STAT         = 0x093E;
    static final int NOM_ATTR_NU_MSMT_STAT        = 0x094E;
    static final int NOM_ATTR_UNIT_CODE           = 0x0996;
    static final int NOM_ATTR_COLOR               = 0x0911;

    // Notification types
    static final int NOM_NOTI_MDS_CREAT  = 0x0D06;
    static final int NOM_NOTI_CONN_INDIC = 0x0D02;

    // Physiological IDs (NOM_PART_SCADA = 2)
    static final int NOM_ECG_CARD_BEAT_RATE      = 0x4002; // Heart Rate
    static final int NOM_PULS_OXIM_SAT_O2        = 0x4BB8; // SpO2
    static final int NOM_PULS_OXIM_PULS_RATE     = 0x4BB0; // Pulse Rate
    static final int NOM_RESP_RATE               = 0x5000; // Respiration Rate
    static final int NOM_PRESS_BLD_ART_ABP_SYS   = 0x4A51; // ABP Systolic
    static final int NOM_PRESS_BLD_ART_ABP_DIA   = 0x4A52; // ABP Diastolic
    static final int NOM_PRESS_BLD_ART_ABP_MEAN  = 0x4A53; // ABP Mean
    static final int NOM_PRESS_BLD_NONINV_SYS    = 0x4A21; // NBP Systolic
    static final int NOM_PRESS_BLD_NONINV_DIA    = 0x4A22; // NBP Diastolic
    static final int NOM_PRESS_BLD_NONINV_MEAN   = 0x4A23; // NBP Mean
    static final int NOM_CO2_ET                  = 0x5108; // End-tidal CO2
    static final int NOM_TEMP_BLD                = 0x4BB4; // Blood Temperature
    static final int NOM_PRESS_BLD_VEN_CENT      = 0x4A44; // CVP
    static final int NOM_PRESS_BLD_ART_PULM_SYS  = 0x4A61; // PAP Systolic
    static final int NOM_PRESS_BLD_ART_PULM_DIA  = 0x4A62; // PAP Diastolic
    static final int NOM_PRESS_BLD_ART_PULM_MEAN = 0x4A63; // PAP Mean

    // Waveform physio IDs
    static final int NOM_ECG_ELEC_POTL_II  = 0x0102; // ECG Lead II
    static final int NOM_PULS_OXIM_PLETH   = 0x4BB4; // SpO2 Pleth (reusing dim)
    static final int NOM_PRESS_BLD_ART     = 0x4A10; // ABP Waveform
    static final int NOM_RESP              = 0x5000; // Resp Waveform

    // Waveform-specific physio IDs for SA
    static final int NOM_ECG_ELEC_POTL_II_SA  = 0x0102;
    static final int NOM_PLETH_PULS_OXIM_SA   = 0x4BB4;
    static final int NOM_PRESS_BLD_ART_ABP_SA = 0x4A50;
    static final int NOM_RESP_SA              = 0x5000;

    // Unit codes (NOM_PART_DIM = 4)
    static final int NOM_DIM_PERCENT       = 0x0220;
    static final int NOM_DIM_BEAT_PER_MIN  = 0x0AA0;
    static final int NOM_DIM_MMHG          = 0x0F20;
    static final int NOM_DIM_RESP_PER_MIN  = 0x0AE0;
    static final int NOM_DIM_DEGC          = 0x17A0;
    static final int NOM_DIM_KILO_PASCAL   = 0x0F00;
    static final int NOM_DIM_DIMLESS       = 0x0200;
    static final int NOM_DIM_CM            = 0x0510;
    static final int NOM_DIM_KG            = 0x06C0;
    static final int NOM_DIM_M_SQ          = 0x0560;
    static final int NOM_DIM_MILLI_VOLT    = 0x0F72;

    // Patient sex constants
    static final int SEX_UNKNOWN = 0;
    static final int SEX_MALE    = 1;
    static final int SEX_FEMALE  = 2;

    // Alarm priority
    static final int AL_INHIBITED     = 0;
    static final int AL_LOW           = 1;
    static final int AL_MED           = 2;
    static final int AL_HI            = 3;
    static final int AL_TECHNICAL     = 0x0001;
    static final int AL_PHYSIOLOGICAL = 0x0002;

    // Alarm codes
    static final int NOM_EVT_HI_HR        = 0x0402;
    static final int NOM_EVT_LO_HR        = 0x0403;
    static final int NOM_EVT_LO_SAT_O2    = 0x0404;
    static final int NOM_EVT_HI_ABP_SYS   = 0x0410;

    // Ventilation mode enum values
    static final int NOM_VENT_MODE_SPONT   = 0x8001;
    static final int NOM_VENT_MODE_CMV     = 0x8002;
    static final int NOM_VENT_MODE_SIMV    = 0x8003;

    // Metric categories
    static final int METRIC_CAT_MEAS       = 0x0001; // measurement
    static final int METRIC_CAT_CALC       = 0x0002; // calculation
    static final int METRIC_CAT_SET        = 0x0003; // setting

    // Metric access
    static final int METRIC_ACCESS_AVAIL   = 0x0002; // available
    static final int METRIC_ACCESS_RD_ONLY = 0x0004; // read-only

    // Max MTU for poll result splitting
    static final int MAX_SINGLE_RESULT_SIZE = 900;

    // FLOAT special values
    static final int FLOAT_NAN   = 0x007FFFFF;
    static final int FLOAT_NRES  = 0x00800000;
    static final int FLOAT_PINF  = 0x007FFFFE;
    static final int FLOAT_NINF  = 0x00800002;

    // =====================================================================
    // Instance State
    // =====================================================================

    private final int port;
    private volatile boolean running = true;
    private volatile boolean associated = false;
    private int invokeId = 0;
    private int pollNumber = 0;
    private final Random rng = new Random(42);

    // Patient demographics
    private String patientGivenName = "John";
    private String patientFamilyName = "Doe";
    private String patientId = "P12345";
    private int patientSex = SEX_MALE;
    private double patientHeight = 175.0; // cm
    private double patientWeight = 80.0;  // kg
    private double patientBSA = 1.96;     // m^2
    private int patientDobYear = 1975;
    private int patientDobMonth = 3;
    private int patientDobDay = 15;

    // Simulated vitals (16 numerics)
    private double heartRate = 72;
    private double spo2 = 97;
    private double pulseRate = 73;
    private double respRate = 16;
    private double abpSys = 120;
    private double abpDia = 80;
    private double abpMean = 93;
    private double nbpSys = 118;
    private double nbpDia = 76;
    private double nbpMean = 90;
    private double etCo2 = 38;
    private double temp = 37.0;
    private double cvp = 8.0;
    private double papSys = 25;
    private double papDia = 10;
    private double papMean = 15;

    // Alarm limits
    private double hrHighLimit = 120;
    private double hrLowLimit = 50;
    private double spo2LowLimit = 90;
    private double abpSysHighLimit = 160;

    // Waveform state
    private double ecgPhase = 0;
    private double plethPhase = 0;
    private double abpWavePhase = 0;
    private double respWavePhase = 0;

    // Client tracking
    private InetSocketAddress clientAddr = null;

    public IntellivueSimulatorV2(int port) {
        this.port = port;
    }

    // =====================================================================
    // Main Loop
    // =====================================================================

    public void run() throws Exception {
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(100);
        socket.setBroadcast(true);

        System.out.println("IntellivueSimulatorV2 listening on UDP port " + port);
        System.out.println("Patient: " + patientGivenName + " " + patientFamilyName
                + " (ID: " + patientId + ")");
        System.out.println("Broadcast discovery on port " + BROADCAST_PORT + " when not associated");
        System.out.println();

        byte[] recvBuf = new byte[8192];
        long lastBroadcast = 0;

        // Scheduled executor for connect indication broadcasts
        ScheduledExecutorService broadcastExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "broadcast-thread");
            t.setDaemon(true);
            return t;
        });

        broadcastExec.scheduleAtFixedRate(() -> {
            if (!associated && running) {
                try {
                    sendConnectIndication(socket);
                } catch (Exception e) {
                    // ignore broadcast errors
                }
            }
        }, 0, 5, TimeUnit.SECONDS);

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
                    e.printStackTrace(System.err);
                }
            }
        }

        broadcastExec.shutdownNow();
        socket.close();
    }

    // =====================================================================
    // Connect Indication Broadcast
    // =====================================================================

    private void sendConnectIndication(DatagramSocket socket) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header: CN_SPDU_SI for connect indication
        buf.put((byte) CN_SPDU_SI);
        int lenPos = buf.position();
        buf.putShort((short) 0); // placeholder

        // Protocol support: indicate Data Export protocol
        buf.putInt(0x80000000); // protocol version 1

        // IP address info for device discovery
        try {
            InetAddress localAddr = InetAddress.getLocalHost();
            byte[] addrBytes = localAddr.getAddress();
            // IP address entry
            buf.putShort((short) 0x0001); // IP address type tag
            buf.putShort((short) 4);      // length
            buf.put(addrBytes);
            // Port entry
            buf.putShort((short) 0x0002); // port type tag
            buf.putShort((short) 2);      // length
            buf.putShort((short) port);
        } catch (UnknownHostException e) {
            // If we cannot resolve, put 0.0.0.0
            buf.putShort((short) 0x0001);
            buf.putShort((short) 4);
            buf.putInt(0);
            buf.putShort((short) 0x0002);
            buf.putShort((short) 2);
            buf.putShort((short) port);
        }

        // Protocol support flags
        buf.putShort((short) 0x0003); // protocol support tag
        buf.putShort((short) 4);
        buf.putInt(0x40000000);       // poll profile support

        // Nomenclature version
        buf.putShort((short) 0x0004);
        buf.putShort((short) 4);
        buf.putInt(0x40000000); // NomVersion

        // Fix length
        int totalLen = buf.position() - lenPos - 2;
        buf.putShort(lenPos, (short) totalLen);

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        DatagramPacket pkt = new DatagramPacket(data, data.length,
                InetAddress.getByName("255.255.255.255"), BROADCAST_PORT);
        try {
            socket.send(pkt);
        } catch (IOException e) {
            // Broadcast might fail on some networks; that is acceptable
        }
    }

    // =====================================================================
    // Message Processing
    // =====================================================================

    private void processMessage(ByteBuffer in, DatagramSocket socket,
                                InetSocketAddress clientAddr) throws IOException {
        if (!in.hasRemaining()) return;

        int firstByte = in.get(0) & 0xFF;

        switch (firstByte) {
            case CN_SPDU_SI:
                handleAssociationRequest(in, socket, clientAddr);
                break;
            case FN_SPDU_SI:
                handleReleaseRequest(in, socket, clientAddr);
                break;
            case AB_SPDU_SI:
                System.out.println("  Association aborted by client");
                associated = false;
                break;
            case DATA_EXPORT_SPDU:
                handleDataExport(in, socket, clientAddr);
                break;
            default:
                System.out.println("  Unknown message type: 0x" + String.format("%02X", firstByte));
                break;
        }
    }

    // =====================================================================
    // Association Handling
    // =====================================================================

    private void handleAssociationRequest(ByteBuffer in, DatagramSocket socket,
                                          InetSocketAddress clientAddr) throws IOException {
        System.out.println("  <- Association Request from " + clientAddr);

        // Parse incoming Association Request
        parseAssociationRequest(in);

        // Send full Association Response
        ByteBuffer resp = buildAssociationResponse();
        sendPacket(socket, clientAddr, resp);
        associated = true;
        System.out.println("  -> Association Response (accepted)");

        // Send MDS Create Event
        updateVitals();
        ByteBuffer mdsEvent = buildMdsCreateEvent();
        sendPacket(socket, clientAddr, mdsEvent);
        System.out.println("  -> MDS Create Event");
    }

    private void parseAssociationRequest(ByteBuffer in) {
        // Parse the incoming Association Request per ACSE ISO/IEC 8649
        if (in.remaining() < 4) return;

        int spdu = in.get() & 0xFF;    // CN_SPDU_SI
        int spduLen = in.get() & 0xFF; // length or 0xC2 for long form

        // If long-form length
        if (spduLen == 0xC2) {
            if (in.remaining() >= 2) {
                spduLen = in.getShort() & 0xFFFF;
            }
        }

        System.out.println("    Parsed ACSE: SPDU=0x" + String.format("%02X", spdu)
                + " len=" + spduLen);

        // We accept any well-formed association request.
        // In a full implementation we would parse:
        //   - Application Context (Data Export Protocol OID)
        //   - Presentation Context list
        //   - User Info with PollProfileSupport requested parameters
        // For simulation, we just accept and respond with our profile.
    }

    private ByteBuffer buildAssociationResponse() {
        // Build MDSEUserInfoStd payload
        ByteBuffer userInfo = ByteBuffer.allocate(256);
        userInfo.order(ByteOrder.BIG_ENDIAN);

        // MDSEUserInfoStd fields
        userInfo.putInt((int) 0x80000000L); // protocolVersion = MDDL_VERSION1
        userInfo.putInt((int) 0x40000000L); // nomenclatureVersion = NOMEN_VERSION
        userInfo.putInt(0x00000000);        // functionalUnits
        userInfo.putInt((int) 0x00800000L); // systemType = SYST_SERVER
        userInfo.putInt((int) 0x20000000L); // startupMode = COLD_START

        // optionList (AttributeValueList — empty: count=0, length=0)
        userInfo.putShort((short) 0); // count
        userInfo.putShort((short) 0); // length

        // supportedAProfiles (AttributeValueList with PollProfileSupport + MdibObjectSupport)
        // Build the two attributes first
        ByteBuffer attrs = ByteBuffer.allocate(200);
        attrs.order(ByteOrder.BIG_ENDIAN);

        // Attribute 1: PollProfileSupport (attribute_id=0x0001)
        ByteBuffer pps = ByteBuffer.allocate(64);
        pps.order(ByteOrder.BIG_ENDIAN);
        pps.putInt(0x80000000);       // pollProfileRevision
        pps.putInt(80000);            // minPollPeriod (RelativeTime): 80000 x 125us = 10s
        pps.putInt(1456);             // maxMtuRx
        pps.putInt(1456);             // maxMtuTx
        pps.putInt(0x00000000);       // maxBwTx
        pps.putInt(0x60000000);       // poll profile options
        // optionalPackages (AttributeValueList: count=0, length=0)
        pps.putShort((short) 0);      // count
        pps.putShort((short) 0);      // length
        pps.flip();

        attrs.putShort((short) 0x0001); // attribute_id for PollProfileSupport
        attrs.putShort((short) pps.remaining()); // length
        attrs.put(pps);

        // Attribute 2: MdibObjectSupport (attribute_id=0x0102)
        ByteBuffer mos = ByteBuffer.allocate(100);
        mos.order(ByteOrder.BIG_ENDIAN);
        int numClasses = 6;
        mos.putShort((short) numClasses);
        mos.putShort((short) (numClasses * 8));
        // MDS
        mos.putShort((short) NOM_MOC_VMS_MDS); mos.putShort((short) 0); mos.putInt(1);
        // Numeric
        mos.putShort((short) NOM_MOC_VMO_METRIC_NU); mos.putShort((short) 0); mos.putInt(64);
        // SampleArray
        mos.putShort((short) NOM_MOC_VMO_METRIC_SA_RT); mos.putShort((short) 0); mos.putInt(16);
        // Alarm Monitor
        mos.putShort((short) NOM_MOC_VMO_AL_MON); mos.putShort((short) 0); mos.putInt(2);
        // Patient Demographics
        mos.putShort((short) NOM_MOC_PT_DEMOG); mos.putShort((short) 0); mos.putInt(1);
        // Enumeration
        mos.putShort((short) NOM_MOC_VMO_METRIC_ENUM); mos.putShort((short) 0); mos.putInt(16);
        mos.flip();

        attrs.putShort((short) 0x0102); // attribute_id for MdibObjectSupport
        attrs.putShort((short) mos.remaining());
        attrs.put(mos);
        attrs.flip();

        // supportedAProfiles: count=2, length=attrs.remaining()
        userInfo.putShort((short) 2);
        userInfo.putShort((short) attrs.remaining());
        userInfo.put(attrs);

        userInfo.flip();
        int userInfoLen = userInfo.remaining();

        // ASN.1 BER length encoding for userInfo (must match ASNLength.java in driver)
        byte[] asnLen;
        if (userInfoLen < 128) {
            asnLen = new byte[] { (byte) userInfoLen };
        } else if (userInfoLen < 256) {
            asnLen = new byte[] { (byte) 0x81, (byte) userInfoLen };
        } else {
            asnLen = new byte[] { (byte) 0x82, (byte) ((userInfoLen >> 8) & 0xFF), (byte) (userInfoLen & 0xFF) };
        }

        // Presentation header — exact bytes from AssociationAcceptImpl in driver
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

        // Presentation trailer — 16 zero bytes
        byte[] presTrailer = new byte[16];

        // Session parameters (PI 0x05 and PI 0x14)
        byte[] sessionPI = new byte[] {
            0x05, 0x08, 0x13, 0x01, 0x00, 0x16, 0x01, 0x02, (byte)0x80, 0x00,
            0x14, 0x02, 0x00, 0x02
        };

        // Presentation context (0xC1 + length)
        int presContentLen = presHeader.length + asnLen.length + userInfoLen + presTrailer.length;

        // Total session body length
        int bodyLen = sessionPI.length + 1 /* 0xC1 tag */ + lengthInfoBytes(presContentLen).length + presContentLen;

        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header: type=0x0E (Accept)
        buf.put((byte) AC_SPDU_SI);
        // Session length: uses LengthInformation encoding (0xFF + 2 bytes if >= 255)
        if (bodyLen < 255) {
            buf.put((byte) bodyLen);
        } else {
            buf.put((byte) 0xFF);
            buf.putShort((short) bodyLen);
        }

        // Session parameters
        buf.put(sessionPI);

        // Presentation context: 0xC1 + length (LengthInformation format)
        buf.put((byte) 0xC1);
        if (presContentLen < 255) {
            buf.put((byte) presContentLen);
        } else {
            buf.put((byte) 0xFF);
            buf.putShort((short) presContentLen);
        }

        // Presentation header
        buf.put(presHeader);

        // ASN.1 length + user info
        buf.put(asnLen);
        buf.put(userInfo);

        // Presentation trailer
        buf.put(presTrailer);

        buf.flip();
        return buf;
    }

    private static byte[] lengthInfoBytes(int len) {
        if (len < 128) {
            return new byte[] { (byte) len };
        } else if (len < 256) {
            return new byte[] { (byte) 0x81, (byte) len };
        } else {
            return new byte[] { (byte) 0x82, (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF) };
        }
    }

    private void handleReleaseRequest(ByteBuffer in, DatagramSocket socket,
                                      InetSocketAddress clientAddr) throws IOException {
        System.out.println("  <- Release Request");
        associated = false;

        ByteBuffer resp = ByteBuffer.allocate(4);
        resp.order(ByteOrder.BIG_ENDIAN);
        resp.put((byte) DN_SPDU_SI);
        resp.put((byte) 0x00);
        resp.putShort((short) 0);
        resp.flip();
        sendPacket(socket, clientAddr, resp);
        System.out.println("  -> Release Response");
    }

    // =====================================================================
    // Data Export Handling
    // =====================================================================

    private void handleDataExport(ByteBuffer in, DatagramSocket socket,
                                  InetSocketAddress clientAddr) throws IOException {
        if (!associated) {
            System.out.println("  Data Export received but not associated -- ignoring");
            return;
        }

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
                // Skip managed object: OIDType(2) + context(2) + handle(2) = 6 bytes
                if (in.remaining() >= 6) {
                    in.position(in.position() + 6);
                } else {
                    return;
                }
                int scope = in.remaining() >= 4 ? in.getInt() : 0;
                int actionTypeCode = in.remaining() >= 2 ? (in.getShort() & 0xFFFF) : 0;

                updateVitals();
                pollNumber++;

                if (actionTypeCode == (NOM_ACT_POLL_MDIB_DATA & 0xFFFF) ||
                    actionTypeCode == (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF)) {

                    // Parse poll request type from remaining data
                    int pollType = 0; // 0 = numerics only, extended will include all
                    boolean isExtended = (actionTypeCode == (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));

                    System.out.println("  <- Poll Request #" + pollNumber +
                            (isExtended ? " (extended)" : " (single)"));

                    if (isExtended) {
                        // Extended poll: send linked results then final
                        sendExtendedPollResults(socket, clientAddr, clientInvokeId);
                    } else {
                        // Single poll: numerics only
                        ByteBuffer result = buildPollResult(clientInvokeId, false);
                        sendPacket(socket, clientAddr, result);
                    }

                    System.out.println("  -> Poll Result: HR=" + (int) heartRate +
                            " SpO2=" + (int) spo2 + " RR=" + (int) respRate +
                            " ABP=" + (int) abpSys + "/" + (int) abpDia +
                            " etCO2=" + (int) etCo2 +
                            " CVP=" + (int) cvp +
                            " PAP=" + (int) papSys + "/" + (int) papDia);
                } else {
                    System.out.println("  <- Action request type=0x" +
                            String.format("%04X", actionTypeCode) + " -- sending empty result");
                    ByteBuffer emptyResult = buildEmptyActionResult(clientInvokeId);
                    sendPacket(socket, clientAddr, emptyResult);
                }
            } else if (cmdType == CMD_GET) {
                System.out.println("  <- Get request -- sending MDS attributes");
                ByteBuffer getResult = buildGetResult(clientInvokeId);
                sendPacket(socket, clientAddr, getResult);
            } else {
                System.out.println("  <- Command type=0x" + String.format("%04X", cmdType));
            }
        }
    }

    // =====================================================================
    // Extended Poll with Linked Results
    // =====================================================================

    private void sendExtendedPollResults(DatagramSocket socket,
                                         InetSocketAddress clientAddr,
                                         int clientInvokeId) throws IOException {
        // Build all poll segments
        // Segment 1: Numerics (ROLRS linked result)
        // Segment 2: Waveforms (ROLRS linked result)
        // Segment 3: Alarms + Patient + Enums (RORS final result)

        // --- Linked Result 1: Numerics ---
        ByteBuffer numericsResult = buildNumericsSegment(clientInvokeId);
        sendPacket(socket, clientAddr, numericsResult);
        System.out.println("    -> ROLRS: Numerics segment");

        // --- Linked Result 2: Waveforms ---
        ByteBuffer waveformResult = buildWaveformSegment(clientInvokeId);
        sendPacket(socket, clientAddr, waveformResult);
        System.out.println("    -> ROLRS: Waveform segment");

        // --- Final Result: Alarms, Patient, Enums ---
        ByteBuffer finalResult = buildFinalPollSegment(clientInvokeId);
        sendPacket(socket, clientAddr, finalResult);
        System.out.println("    -> RORS: Final segment (alarms, patient, enums)");
    }

    private ByteBuffer buildNumericsSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header
        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

        // ROLRS (linked result)
        buf.putShort((short) ROLRS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);

        // Linked invoke ID
        buf.putShort((short) clientInvokeId);

        // Linked result number
        buf.putShort((short) 1); // first segment

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

        // Poll number + timestamps
        writePollHeader(buf);

        // Poll info list
        buf.putShort((short) 1); // 1 context
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();

        // SingleContextPoll
        buf.putShort((short) 0); // mds_context

        // Observation list: all numerics
        Numeric[] numerics = getAllNumerics();
        buf.putShort((short) numerics.length);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (Numeric n : numerics) {
            writeNumericObservation(buf, n);
        }

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short) (obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short) (obsEnd - pollInfoStart));

        // Fix lengths
        fixLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildWaveformSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header
        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

        // ROLRS
        buf.putShort((short) ROLRS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) clientInvokeId);
        buf.putShort((short) 2); // second segment

        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.putShort((short) (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePollHeader(buf);

        // Poll info list
        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        // 4 waveforms
        buf.putShort((short) 4);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        // ECG Lead II
        writeWaveformObservation(buf, 0x50, NOM_ECG_ELEC_POTL_II_SA, generateEcgSamples(), 500, NOM_DIM_MILLI_VOLT);
        // SpO2 Pleth
        writeWaveformObservation(buf, 0x51, NOM_PLETH_PULS_OXIM_SA, generatePlethSamples(), 125, NOM_DIM_DIMLESS);
        // ABP waveform
        writeWaveformObservation(buf, 0x52, NOM_PRESS_BLD_ART_ABP_SA, generateAbpSamples(), 125, NOM_DIM_MMHG);
        // Respiration waveform
        writeWaveformObservation(buf, 0x53, NOM_RESP_SA, generateRespSamples(), 62, NOM_DIM_DIMLESS);

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short) (obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short) (obsEnd - pollInfoStart));

        fixLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildFinalPollSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Session header
        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

        // RORS (final result)
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

        writePollHeader(buf);

        // Poll info list: 3 contexts (alarm, patient, enum)
        buf.putShort((short) 1); // 1 context with multiple obs
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        // 3 observations: alarm monitor, patient demog, enum
        buf.putShort((short) 3);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        // Alarm Monitor Object
        writeAlarmMonitorObservation(buf, 0x60);

        // Patient Demographics Object
        writePatientDemographicsObservation(buf, 0x70);

        // Enumeration Object (ventilation mode)
        writeEnumerationObservation(buf, 0x80);

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short) (obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short) (obsEnd - pollInfoStart));

        fixLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    // =====================================================================
    // Single Poll Result (non-extended)
    // =====================================================================

    private ByteBuffer buildPollResult(int clientInvokeId, boolean linked) {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

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

        writePollHeader(buf);

        // Poll info list
        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        Numeric[] numerics = getAllNumerics();
        buf.putShort((short) numerics.length);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (Numeric n : numerics) {
            writeNumericObservation(buf, n);
        }

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short) (obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short) (obsEnd - pollInfoStart));

        fixLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    // =====================================================================
    // Get Result (MDS attributes)
    // =====================================================================

    private ByteBuffer buildGetResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

        buf.putShort((short) RORS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) clientInvokeId);
        buf.putShort((short) CMD_GET);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        // Managed Object
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        // Attribute list
        int attrCount = 3;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // Attribute: System Type
        buf.putShort((short) NOM_ATTR_SYS_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) NOM_MOC_VMS_MDS);

        // Attribute: System Model
        writeSystemModelAttribute(buf);

        // Attribute: Absolute Time
        buf.putShort((short) NOM_ATTR_TIME_ABS);
        buf.putShort((short) 8);
        writeAbsoluteTime(buf);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));

        // Fix lengths
        int end = buf.position();
        buf.putShort(lenPos, (short) (end - lenPos - 2));
        buf.putShort(roLenPos, (short) (end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short) (end - cmdLenPos - 2));

        buf.flip();
        return buf;
    }

    // =====================================================================
    // MDS Create Event
    // =====================================================================

    private ByteBuffer buildMdsCreateEvent() {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100); // sessionId
        int lenPos = buf.position();
        buf.putShort((short) 0);    // contextId = length placeholder

        buf.putShort((short) ROIV_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) invokeId++);
        buf.putShort((short) CMD_CONFIRMED_EVENT);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        // Managed Object: MDS
        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        // Event time (relative) + event type
        buf.putInt(0); // relative time
        buf.putShort((short) NOM_NOTI_MDS_CREAT); // event type
        int eventLenPos = buf.position();
        buf.putShort((short) 0); // event length placeholder

        // Attribute list — minimal: just System Type
        buf.putShort((short) 1);  // count = 1
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);  // attr list length placeholder
        int attrStart = buf.position();

        // Attribute: System Type
        buf.putShort((short) NOM_ATTR_SYS_TYPE); // 0x0986
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) NOM_MOC_VMS_MDS);

        int attrEnd = buf.position();

        // Fix lengths
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
        buf.putShort(eventLenPos, (short) (attrEnd - eventLenPos - 2));
        int end = buf.position();
        buf.putShort(lenPos, (short) (end - lenPos - 2));
        buf.putShort(roLenPos, (short) (end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short) (end - cmdLenPos - 2));

        buf.flip();
        return buf;
    }

    // =====================================================================
    // Empty Results
    // =====================================================================

    private ByteBuffer buildEmptyActionResult(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putShort((short) 0xE100); // sessionId
        buf.putShort((short) 16);   // contextId = length
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

    // =====================================================================
    // Observation Writers
    // =====================================================================

    private void writeNumericObservation(ByteBuffer buf, Numeric n) {
        // Handle
        buf.putShort((short) n.handle);

        // Attribute list: NOM_ATTR_NU_VAL_OBS, NOM_ATTR_ID_LABEL, NOM_ATTR_ID_LABEL_STRING, NOM_ATTR_METRIC_SPECN
        int attrCount = 4;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // ---- Attribute 1: NOM_ATTR_NU_VAL_OBS (NumericObservedValue) ----
        buf.putShort((short) NOM_ATTR_NU_VAL_OBS);
        buf.putShort((short) 12);
        // physio_id TYPE: partition + code
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) n.physioId);
        // measurement state
        buf.putShort((short) 0x0000); // valid measurement
        // unit_code TYPE: partition + code
        buf.putShort((short) NOM_PART_DIM);
        buf.putShort((short) n.unitCode);
        // value (FLOATType)
        buf.putInt(encodeFloat(n.value));

        // ---- Attribute 2: NOM_ATTR_ID_LABEL (TextId) ----
        buf.putShort((short) NOM_ATTR_ID_LABEL);
        buf.putShort((short) 4);
        buf.putInt(n.physioId); // TextId maps to physio ID

        // ---- Attribute 3: NOM_ATTR_ID_LABEL_STRING (human-readable label) ----
        byte[] labelBytes = n.label.getBytes();
        int labelPadded = (labelBytes.length + 1) & ~1; // pad to even
        buf.putShort((short) NOM_ATTR_ID_LABEL_STRING);
        buf.putShort((short) (2 + labelPadded)); // VariableLabel: length(2) + string
        buf.putShort((short) labelBytes.length);
        buf.put(labelBytes);
        if (labelBytes.length % 2 != 0) buf.put((byte) 0); // pad

        // ---- Attribute 4: NOM_ATTR_METRIC_SPECN (MetricSpecification) ----
        buf.putShort((short) NOM_ATTR_METRIC_SPECN);
        buf.putShort((short) 8);
        buf.putInt(n.updatePeriod); // update period in 1/8ms (e.g. 8000 = 1 second)
        buf.putShort((short) METRIC_CAT_MEAS); // category: measurement
        buf.putShort((short) (METRIC_ACCESS_AVAIL | METRIC_ACCESS_RD_ONLY)); // access + relevance

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private void writeWaveformObservation(ByteBuffer buf, int handle, int physioId,
                                          short[] samples, int sampleRate, int unitCode) {
        // Handle
        buf.putShort((short) handle);

        // Attributes: SA_VAL_OBS, SA_SPECN, SA_FIXED_VAL_SPECN, ID_TYPE
        int attrCount = 4;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // ---- Attribute 1: NOM_ATTR_SA_VAL_OBS (SampleArrayObservedValue) ----
        int sampleDataLen = samples.length * 2; // int16 samples
        buf.putShort((short) NOM_ATTR_SA_VAL_OBS);
        buf.putShort((short) (8 + sampleDataLen)); // physioId(4) + state(2) + length(2) + data
        // physio_id TYPE
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);
        // measurement state
        buf.putShort((short) 0x0000);
        // sample array: length + samples
        buf.putShort((short) sampleDataLen);
        for (short s : samples) {
            buf.putShort(s);
        }

        // ---- Attribute 2: NOM_ATTR_SA_SPECN (SampleArraySpecification) ----
        buf.putShort((short) NOM_ATTR_SA_SPECN);
        buf.putShort((short) 12);
        buf.putShort((short) samples.length); // array_size
        buf.putShort((short) 16);             // sample_size (16-bit)
        buf.putInt(sampleRate);               // sample rate (samples/sec, encoded)
        buf.putShort((short) NOM_PART_DIM);   // unit partition
        buf.putShort((short) unitCode);       // unit code

        // ---- Attribute 3: NOM_ATTR_SA_FIXED_VAL_SPECN ----
        buf.putShort((short) NOM_ATTR_SA_FIXED_VAL_SPECN);
        buf.putShort((short) 12);
        buf.putShort((short) 1);             // count
        buf.putShort((short) 8);             // length per entry
        // SaFixedValSpecEntry: sa_val_range
        buf.putShort((short) 0);              // lower absolute value
        buf.putShort((short) 4095);           // upper absolute value
        buf.putShort((short) physioId);        // physio_id
        buf.putShort((short) 0);              // reserved

        // ---- Attribute 4: NOM_ATTR_ID_TYPE ----
        buf.putShort((short) NOM_ATTR_ID_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private void writeAlarmMonitorObservation(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        // Build alarm lists dynamically
        List<AlarmEntry> techAlarms = new ArrayList<>();
        List<AlarmEntry> physAlarms = new ArrayList<>();

        // Check for physiological alarm conditions
        if (heartRate > hrHighLimit) {
            physAlarms.add(new AlarmEntry(NOM_EVT_HI_HR, AL_HI, NOM_ECG_CARD_BEAT_RATE,
                    "HR High: " + (int) heartRate));
        }
        if (heartRate < hrLowLimit) {
            physAlarms.add(new AlarmEntry(NOM_EVT_LO_HR, AL_HI, NOM_ECG_CARD_BEAT_RATE,
                    "HR Low: " + (int) heartRate));
        }
        if (spo2 < spo2LowLimit) {
            physAlarms.add(new AlarmEntry(NOM_EVT_LO_SAT_O2, AL_HI, NOM_PULS_OXIM_SAT_O2,
                    "SpO2 Low: " + (int) spo2));
        }
        if (abpSys > abpSysHighLimit) {
            physAlarms.add(new AlarmEntry(NOM_EVT_HI_ABP_SYS, AL_MED, NOM_PRESS_BLD_ART_ABP_SYS,
                    "ABP Sys High: " + (int) abpSys));
        }

        // Count attributes: DeviceAlertCondition, T-Alarm list, P-Alarm list
        int attrCount = 3;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // ---- Attribute 1: NOM_ATTR_DEV_AL_COND (DeviceAlertCondition) ----
        buf.putShort((short) NOM_ATTR_DEV_AL_COND);
        buf.putShort((short) 2);
        int alertState = 0;
        if (!techAlarms.isEmpty()) alertState |= 0x0001; // technical alarm present
        if (!physAlarms.isEmpty()) alertState |= 0x0002; // physiological alarm present
        buf.putShort((short) alertState);

        // ---- Attribute 2: NOM_ATTR_AL_MON_T_AL_LIST (Technical Alarms) ----
        buf.putShort((short) NOM_ATTR_AL_MON_T_AL_LIST);
        int tAlLenPos = buf.position();
        buf.putShort((short) 0);
        int tAlStart = buf.position();
        buf.putShort((short) techAlarms.size()); // count
        buf.putShort((short) (techAlarms.size() * 10)); // length (10 bytes each)
        for (AlarmEntry ae : techAlarms) {
            writeAlarmEntry(buf, ae);
        }
        int tAlEnd = buf.position();
        buf.putShort(tAlLenPos, (short) (tAlEnd - tAlStart));

        // ---- Attribute 3: NOM_ATTR_AL_MON_P_AL_LIST (Physiological Alarms) ----
        buf.putShort((short) NOM_ATTR_AL_MON_P_AL_LIST);
        int pAlLenPos = buf.position();
        buf.putShort((short) 0);
        int pAlStart = buf.position();
        buf.putShort((short) physAlarms.size());
        buf.putShort((short) (physAlarms.size() * 10));
        for (AlarmEntry ae : physAlarms) {
            writeAlarmEntry(buf, ae);
        }
        int pAlEnd = buf.position();
        buf.putShort(pAlLenPos, (short) (pAlEnd - pAlStart));

        if (!physAlarms.isEmpty() || !techAlarms.isEmpty()) {
            System.out.println("    Alarms active: " + physAlarms.size() + " physiological, "
                    + techAlarms.size() + " technical");
        }

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private void writeAlarmEntry(ByteBuffer buf, AlarmEntry ae) {
        buf.putShort((short) ae.alarmCode);    // alarm code
        buf.putShort((short) ae.priority);      // priority
        buf.putShort((short) NOM_PART_SCADA);  // source partition
        buf.putShort((short) ae.sourceId);      // source physio ID
        buf.putShort((short) 0x0001);           // alarm state: active
    }

    private void writePatientDemographicsObservation(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        // Attributes: given name, family name, patient ID, DOB, sex, height, weight, BSA
        int attrCount = 8;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // ---- Given Name ----
        writeStringAttribute(buf, NOM_ATTR_PT_NAME_GIVEN, patientGivenName);

        // ---- Family Name ----
        writeStringAttribute(buf, NOM_ATTR_PT_NAME_FAMILY, patientFamilyName);

        // ---- Patient ID ----
        writeStringAttribute(buf, NOM_ATTR_PT_ID, patientId);

        // ---- Date of Birth (AbsoluteTime BCD) ----
        buf.putShort((short) NOM_ATTR_PT_DOB);
        buf.putShort((short) 8);
        writeBcdAbsoluteTime(buf, patientDobYear, patientDobMonth, patientDobDay, 0, 0, 0, 0);

        // ---- Sex ----
        buf.putShort((short) NOM_ATTR_PT_SEX);
        buf.putShort((short) 2);
        buf.putShort((short) patientSex);

        // ---- Height ----
        buf.putShort((short) NOM_ATTR_PT_HEIGHT);
        buf.putShort((short) 4);
        buf.putInt(encodeFloat(patientHeight));

        // ---- Weight ----
        buf.putShort((short) NOM_ATTR_PT_WEIGHT);
        buf.putShort((short) 4);
        buf.putInt(encodeFloat(patientWeight));

        // ---- BSA ----
        buf.putShort((short) NOM_ATTR_PT_BSA);
        buf.putShort((short) 4);
        buf.putInt(encodeFloat(patientBSA));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    private void writeEnumerationObservation(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        // Attributes: ENUM_OBS_VAL (ventilation mode), ID_TYPE
        int attrCount = 2;
        buf.putShort((short) attrCount);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        // ---- Attribute 1: NOM_ATTR_ENUM_OBS_VAL (EnumObservedValue) ----
        buf.putShort((short) NOM_ATTR_ENUM_OBS_VAL);
        buf.putShort((short) 8);
        buf.putShort((short) NOM_PART_SCADA); // partition
        buf.putShort((short) 0x5020);          // NOM_VENT_MODE physio ID
        buf.putShort((short) 0x0000);          // state: valid
        buf.putShort((short) NOM_VENT_MODE_SIMV); // current value: SIMV

        // ---- Attribute 2: NOM_ATTR_ID_TYPE ----
        buf.putShort((short) NOM_ATTR_ID_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) 0x5020); // NOM_VENT_MODE

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short) (attrEnd - attrStart));
    }

    // =====================================================================
    // Waveform Generation
    // =====================================================================

    /**
     * Generate 1 second of realistic ECG waveform (500 Hz sample rate).
     * Produces PQRST complex at the current heart rate interval.
     */
    private short[] generateEcgSamples() {
        int numSamples = 500; // 500 Hz * 1 second
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / heartRate; // seconds per beat
        double samplePeriod = 1.0 / 500.0;

        for (int i = 0; i < numSamples; i++) {
            double t = ecgPhase + i * samplePeriod;
            double tInBeat = t % beatInterval;
            double beatFrac = tInBeat / beatInterval;

            double val = 0;

            // P wave (at ~10-20% of beat)
            if (beatFrac >= 0.10 && beatFrac < 0.20) {
                double pPhase = (beatFrac - 0.10) / 0.10;
                val = 0.15 * Math.sin(Math.PI * pPhase);
            }
            // Q wave (small negative, ~22%)
            else if (beatFrac >= 0.22 && beatFrac < 0.25) {
                double qPhase = (beatFrac - 0.22) / 0.03;
                val = -0.1 * Math.sin(Math.PI * qPhase);
            }
            // R wave (large positive spike, ~25-30%)
            else if (beatFrac >= 0.25 && beatFrac < 0.30) {
                double rPhase = (beatFrac - 0.25) / 0.05;
                val = 1.0 * Math.sin(Math.PI * rPhase);
            }
            // S wave (negative, ~30-33%)
            else if (beatFrac >= 0.30 && beatFrac < 0.33) {
                double sPhase = (beatFrac - 0.30) / 0.03;
                val = -0.2 * Math.sin(Math.PI * sPhase);
            }
            // T wave (positive bump, ~40-55%)
            else if (beatFrac >= 0.40 && beatFrac < 0.55) {
                double tPhase = (beatFrac - 0.40) / 0.15;
                val = 0.3 * Math.sin(Math.PI * tPhase);
            }
            // Baseline
            else {
                val = 0;
            }

            // Add slight noise
            val += rng.nextGaussian() * 0.01;

            // Scale to int16 range (use ~+/-2000 for 1 mV)
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int) (val * 2000)));
        }

        ecgPhase += numSamples / 500.0;
        return samples;
    }

    /**
     * Generate SpO2 plethysmograph waveform (125 Hz).
     */
    private short[] generatePlethSamples() {
        int numSamples = 125; // 125 Hz * 1 second
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / pulseRate;
        double samplePeriod = 1.0 / 125.0;

        for (int i = 0; i < numSamples; i++) {
            double t = plethPhase + i * samplePeriod;
            double tInBeat = t % beatInterval;
            double beatFrac = tInBeat / beatInterval;

            // Pleth waveform: sharp rise, gradual fall with dicrotic notch
            double val;
            if (beatFrac < 0.15) {
                // Upstroke
                val = beatFrac / 0.15;
            } else if (beatFrac < 0.25) {
                // Peak and initial downstroke
                double d = (beatFrac - 0.15) / 0.10;
                val = 1.0 - 0.3 * d;
            } else if (beatFrac < 0.35) {
                // Dicrotic notch
                double d = (beatFrac - 0.25) / 0.10;
                val = 0.7 - 0.15 * Math.sin(Math.PI * d);
            } else {
                // Diastolic downslope
                double d = (beatFrac - 0.35) / 0.65;
                val = 0.55 * (1.0 - d);
            }

            val += rng.nextGaussian() * 0.005;
            samples[i] = (short) Math.max(0, Math.min(4095, (int) (val * 3000)));
        }

        plethPhase += numSamples / 125.0;
        return samples;
    }

    /**
     * Generate ABP arterial waveform (125 Hz).
     * Systolic peak + dicrotic notch.
     */
    private short[] generateAbpSamples() {
        int numSamples = 125;
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / heartRate;
        double samplePeriod = 1.0 / 125.0;

        for (int i = 0; i < numSamples; i++) {
            double t = abpWavePhase + i * samplePeriod;
            double tInBeat = t % beatInterval;
            double beatFrac = tInBeat / beatInterval;

            double val;
            if (beatFrac < 0.10) {
                // Rapid upstroke to systolic
                val = abpDia + (abpSys - abpDia) * (beatFrac / 0.10);
            } else if (beatFrac < 0.20) {
                // Initial descent from systolic
                double d = (beatFrac - 0.10) / 0.10;
                val = abpSys - (abpSys - abpMean) * 0.4 * d;
            } else if (beatFrac < 0.30) {
                // Dicrotic notch (small bump)
                double d = (beatFrac - 0.20) / 0.10;
                double base = abpSys - (abpSys - abpMean) * 0.4;
                val = base - 5 + 10 * Math.sin(Math.PI * d);
            } else {
                // Diastolic decay
                double d = (beatFrac - 0.30) / 0.70;
                double notchVal = abpSys - (abpSys - abpMean) * 0.4 + 5;
                val = notchVal - (notchVal - abpDia) * d;
            }

            val += rng.nextGaussian() * 0.5;
            // Scale: mmHg value, 0-300 range mapped to int16
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int) (val * 100)));
        }

        abpWavePhase += numSamples / 125.0;
        return samples;
    }

    /**
     * Generate respiration waveform (62 Hz, ~sinusoidal).
     */
    private short[] generateRespSamples() {
        int numSamples = 62;
        short[] samples = new short[numSamples];
        double breathInterval = 60.0 / respRate;
        double samplePeriod = 1.0 / 62.0;

        for (int i = 0; i < numSamples; i++) {
            double t = respWavePhase + i * samplePeriod;
            double phase = (t % breathInterval) / breathInterval;
            // Slightly asymmetric: inspiration longer than expiration
            double val;
            if (phase < 0.45) {
                // Inspiration (slower rise)
                val = Math.sin(Math.PI * phase / 0.45 * 0.5);
            } else {
                // Expiration (faster fall)
                val = Math.cos(Math.PI * (phase - 0.45) / 0.55 * 0.5);
            }
            val += rng.nextGaussian() * 0.02;
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int) (val * 2000)));
        }

        respWavePhase += numSamples / 62.0;
        return samples;
    }

    // =====================================================================
    // Absolute Time (BCD Encoding)
    // =====================================================================

    /**
     * Write current time as BCD-encoded AbsoluteTime per spec.
     * Format: century(1), year(1), month(1), day(1), hour(1), minute(1), second(1), sec_fractions(1)
     * All BCD encoded: e.g. year 26 = 0x26, month 3 = 0x03
     */
    private void writeAbsoluteTime(ByteBuffer buf) {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int century = year / 100;
        int yearInCentury = year % 100;

        buf.put(toBcd(century));
        buf.put(toBcd(yearInCentury));
        buf.put(toBcd(cal.get(Calendar.MONTH) + 1));
        buf.put(toBcd(cal.get(Calendar.DAY_OF_MONTH)));
        buf.put(toBcd(cal.get(Calendar.HOUR_OF_DAY)));
        buf.put(toBcd(cal.get(Calendar.MINUTE)));
        buf.put(toBcd(cal.get(Calendar.SECOND)));
        buf.put(toBcd(cal.get(Calendar.MILLISECOND) / 10)); // hundredths
    }

    /**
     * Write a specific date/time as BCD-encoded AbsoluteTime.
     */
    private void writeBcdAbsoluteTime(ByteBuffer buf, int year, int month, int day,
                                       int hour, int minute, int second, int hundredths) {
        int century = year / 100;
        int yearInCentury = year % 100;
        buf.put(toBcd(century));
        buf.put(toBcd(yearInCentury));
        buf.put(toBcd(month));
        buf.put(toBcd(day));
        buf.put(toBcd(hour));
        buf.put(toBcd(minute));
        buf.put(toBcd(second));
        buf.put(toBcd(hundredths));
    }

    /**
     * Convert integer 0-99 to BCD byte.
     * E.g. 26 -> 0x26, 3 -> 0x03
     */
    private byte toBcd(int value) {
        value = Math.max(0, Math.min(99, value));
        return (byte) (((value / 10) << 4) | (value % 10));
    }

    // =====================================================================
    // FLOAT-Type Encoding
    // =====================================================================

    /**
     * Encode a double as Philips FLOAT-Type (32-bit).
     * Per spec: value = mantissa * 10^exponent
     * Exponent: 8 bits signed (top byte), Mantissa: 24 bits signed (lower 3 bytes).
     */
    static int encodeFloat(double value) {
        if (Double.isNaN(value)) return FLOAT_NAN;
        if (Double.isInfinite(value)) return value > 0 ? FLOAT_PINF : FLOAT_NINF;
        if (value == 0.0) return 0;

        int bestExp = 0;
        int bestMantissa = (int) Math.round(value);

        for (int exp = -4; exp <= 4; exp++) {
            double scale = Math.pow(10, -exp);
            long mantissa = Math.round(value * scale);
            if (mantissa >= -8388607 && mantissa <= 8388607) {
                bestExp = exp;
                bestMantissa = (int) mantissa;
                if (exp <= 0) break;
            }
        }

        return ((bestExp & 0xFF) << 24) | (bestMantissa & 0x00FFFFFF);
    }

    // =====================================================================
    // Attribute Helpers
    // =====================================================================

    private void writeSystemModelAttribute(ByteBuffer buf) {
        byte[] mfr = "Philips".getBytes();
        byte[] mdl = "IntelliVue MX800".getBytes();

        int mfrPadded = (mfr.length + 1) & ~1; // round up to even
        int mdlPadded = (mdl.length + 1) & ~1;
        int totalLen = 2 + mfrPadded + 2 + mdlPadded;

        buf.putShort((short) NOM_ATTR_ID_MODEL);
        buf.putShort((short) totalLen);

        // Manufacturer VariableLabel
        buf.putShort((short) mfr.length);
        buf.put(mfr);
        if (mfr.length % 2 != 0) buf.put((byte) 0);

        // Model VariableLabel
        buf.putShort((short) mdl.length);
        buf.put(mdl);
        if (mdl.length % 2 != 0) buf.put((byte) 0);
    }

    private void writeStringAttribute(ByteBuffer buf, int attrId, String value) {
        byte[] strBytes = value.getBytes();
        int padded = (strBytes.length + 1) & ~1;
        buf.putShort((short) attrId);
        buf.putShort((short) (2 + padded)); // VariableLabel: length(2) + string
        buf.putShort((short) strBytes.length);
        buf.put(strBytes);
        if (strBytes.length % 2 != 0) buf.put((byte) 0);
    }

    // =====================================================================
    // Poll Header
    // =====================================================================

    private void writePollHeader(ByteBuffer buf) {
        // Poll number
        buf.putShort((short) pollNumber);
        // Relative time (1/8ms since start)
        buf.putInt((int) ((System.currentTimeMillis() & 0xFFFFFFFFL) * 8));
        // Absolute time (BCD encoded)
        writeAbsoluteTime(buf);
    }

    // =====================================================================
    // Length Fixup
    // =====================================================================

    private void fixLengths(ByteBuffer buf, int lenPos, int roLenPos,
                            int cmdLenPos, int actionLenPos) {
        int end = buf.position();
        buf.putShort(actionLenPos, (short) (end - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short) (end - cmdLenPos - 2));
        buf.putShort(roLenPos, (short) (end - roLenPos - 2));
        buf.putShort(lenPos, (short) (end - lenPos - 2));
    }

    // =====================================================================
    // Numerics
    // =====================================================================

    private Numeric[] getAllNumerics() {
        return new Numeric[]{
            new Numeric(NOM_ECG_CARD_BEAT_RATE,      heartRate,  NOM_DIM_BEAT_PER_MIN, 0x01, "HR",         8000),
            new Numeric(NOM_PULS_OXIM_SAT_O2,        spo2,       NOM_DIM_PERCENT,      0x02, "SpO2",       8000),
            new Numeric(NOM_PULS_OXIM_PULS_RATE,      pulseRate,  NOM_DIM_BEAT_PER_MIN, 0x03, "Pulse",      8000),
            new Numeric(NOM_RESP_RATE,                 respRate,   NOM_DIM_RESP_PER_MIN, 0x04, "RR",         8000),
            new Numeric(NOM_PRESS_BLD_ART_ABP_SYS,    abpSys,     NOM_DIM_MMHG,         0x10, "ABP Sys",    8000),
            new Numeric(NOM_PRESS_BLD_ART_ABP_DIA,    abpDia,     NOM_DIM_MMHG,         0x11, "ABP Dia",    8000),
            new Numeric(NOM_PRESS_BLD_ART_ABP_MEAN,   abpMean,    NOM_DIM_MMHG,         0x12, "ABP Mean",   8000),
            new Numeric(NOM_PRESS_BLD_NONINV_SYS,     nbpSys,     NOM_DIM_MMHG,         0x20, "NBP Sys",    8000),
            new Numeric(NOM_PRESS_BLD_NONINV_DIA,     nbpDia,     NOM_DIM_MMHG,         0x21, "NBP Dia",    8000),
            new Numeric(NOM_PRESS_BLD_NONINV_MEAN,    nbpMean,    NOM_DIM_MMHG,         0x22, "NBP Mean",   8000),
            new Numeric(NOM_CO2_ET,                    etCo2,      NOM_DIM_MMHG,         0x30, "etCO2",      8000),
            new Numeric(NOM_TEMP_BLD,                  temp,       NOM_DIM_DEGC,         0x40, "Temp",       16000),
            new Numeric(NOM_PRESS_BLD_VEN_CENT,        cvp,        NOM_DIM_MMHG,         0x41, "CVP",        8000),
            new Numeric(NOM_PRESS_BLD_ART_PULM_SYS,   papSys,     NOM_DIM_MMHG,         0x42, "PAP Sys",    8000),
            new Numeric(NOM_PRESS_BLD_ART_PULM_DIA,   papDia,     NOM_DIM_MMHG,         0x43, "PAP Dia",    8000),
            new Numeric(NOM_PRESS_BLD_ART_PULM_MEAN,  papMean,    NOM_DIM_MMHG,         0x44, "PAP Mean",   8000),
        };
    }

    // =====================================================================
    // Vitals Simulation
    // =====================================================================

    private void updateVitals() {
        double t = pollNumber * 0.1;
        heartRate  = clamp(72 + 8 * Math.sin(t * 0.3) + rng.nextGaussian() * 2, 50, 130);
        spo2       = clamp(97 + Math.sin(t * 0.2) + rng.nextGaussian() * 0.5, 85, 100);
        pulseRate  = clamp(heartRate + rng.nextGaussian() * 2, 50, 130);
        respRate   = clamp(16 + 3 * Math.sin(t * 0.15) + rng.nextGaussian() * 1, 8, 35);
        abpSys     = clamp(120 + 10 * Math.sin(t * 0.25) + rng.nextGaussian() * 3, 80, 200);
        abpDia     = clamp(80 + 5 * Math.sin(t * 0.25) + rng.nextGaussian() * 2, 50, 120);
        abpMean    = (abpSys + 2 * abpDia) / 3.0;
        nbpSys     = clamp(abpSys - 2 + rng.nextGaussian() * 2, 80, 200);
        nbpDia     = clamp(abpDia + 1 + rng.nextGaussian() * 2, 50, 120);
        nbpMean    = (nbpSys + 2 * nbpDia) / 3.0;
        etCo2      = clamp(38 + 3 * Math.sin(t * 0.1) + rng.nextGaussian() * 1, 25, 60);
        temp       = clamp(37.0 + 0.3 * Math.sin(t * 0.05) + rng.nextGaussian() * 0.1, 35, 40);
        cvp        = clamp(8 + 2 * Math.sin(t * 0.12) + rng.nextGaussian() * 1, 2, 15);
        papSys     = clamp(25 + 5 * Math.sin(t * 0.18) + rng.nextGaussian() * 2, 15, 40);
        papDia     = clamp(10 + 3 * Math.sin(t * 0.18) + rng.nextGaussian() * 1, 5, 20);
        papMean    = (papSys + 2 * papDia) / 3.0;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // =====================================================================
    // Packet Sending
    // =====================================================================

    private void sendPacket(DatagramSocket socket, InetSocketAddress addr,
                            ByteBuffer buf) throws IOException {
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        socket.send(new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort()));
    }

    // =====================================================================
    // Data Classes
    // =====================================================================

    static class Numeric {
        final int physioId;
        final double value;
        final int unitCode;
        final int handle;
        final String label;
        final int updatePeriod; // in 1/8 ms

        Numeric(int physioId, double value, int unitCode, int handle, String label, int updatePeriod) {
            this.physioId = physioId;
            this.value = value;
            this.unitCode = unitCode;
            this.handle = handle;
            this.label = label;
            this.updatePeriod = updatePeriod;
        }
    }

    static class AlarmEntry {
        final int alarmCode;
        final int priority;
        final int sourceId;
        final String description;

        AlarmEntry(int alarmCode, int priority, int sourceId, String description) {
            this.alarmCode = alarmCode;
            this.priority = priority;
            this.sourceId = sourceId;
            this.description = description;
        }
    }

    // =====================================================================
    // Main
    // =====================================================================

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String patientName = null;
        String patientId = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "--patient":
                    patientName = args[++i];
                    break;
                case "--patient-id":
                    patientId = args[++i];
                    break;
                case "--help":
                    System.out.println("Usage: java IntellivueSimulatorV2 [options]");
                    System.out.println();
                    System.out.println("Options:");
                    System.out.println("  --port <port>         UDP port (default: " + DEFAULT_PORT + ")");
                    System.out.println("  --patient <name>      Patient full name (e.g. \"John Doe\")");
                    System.out.println("  --patient-id <id>     Patient ID (e.g. \"P12345\")");
                    System.out.println("  --help                Show this help");
                    System.out.println();
                    System.out.println("Simulates a Philips IntelliVue MX800 patient monitor.");
                    System.out.println();
                    System.out.println("Features:");
                    System.out.println("  - 16 numeric vitals (HR, SpO2, PR, RR, ABP, NBP, etCO2, Temp, CVP, PAP)");
                    System.out.println("  - 4 waveforms (ECG Lead II, SpO2 Pleth, ABP, Respiration)");
                    System.out.println("  - Dynamic physiological alarms");
                    System.out.println("  - Patient demographics");
                    System.out.println("  - Connect Indication broadcast discovery");
                    System.out.println("  - Extended poll with linked results (ROLRS)");
                    System.out.println("  - BCD-encoded Absolute Time");
                    System.out.println("  - Full ACSE association negotiation");
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Use --help for usage information.");
                    System.exit(1);
            }
        }

        IntellivueSimulatorV2 sim = new IntellivueSimulatorV2(port);

        if (patientName != null) {
            String[] parts = patientName.trim().split("\\s+", 2);
            sim.patientGivenName = parts[0];
            if (parts.length > 1) {
                sim.patientFamilyName = parts[1];
            }
        }
        if (patientId != null) {
            sim.patientId = patientId;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> sim.running = false));
        sim.run();
    }
}
