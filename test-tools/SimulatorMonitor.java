import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Patient-monitor-style GUI for the Draeger MEDIBUS V2 and Philips IntelliVue V2 simulators.
 *
 * Features scrolling waveform traces, large numeric displays, and full V2 protocol support
 * for both Draeger and Philips devices. Dark background like a real bedside monitor.
 *
 * No external dependencies -- uses Java Swing only.
 *
 * Usage:
 *   javac test-tools/SimulatorMonitor.java
 *   java -cp test-tools SimulatorMonitor
 */
public class SimulatorMonitor extends JFrame {

    // =====================================================================
    // Protocol Constants -- Draeger MEDIBUS
    // =====================================================================
    static final int ESC = 0x1B;
    static final int SOH = 0x01;
    static final int CR  = 0x0D;
    static final int DC1 = 0x11;
    static final int DC3 = 0x13;
    static final int NAK = 0x15;
    static final int ETX = 0x03;

    static final int RT_SYNC = 0xC6;
    static final int RT_SYNC_INSP_START = 0xC0;

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

    static final int RT_CODE_AIRWAY_PRESSURE = 0x00;
    static final int RT_CODE_FLOW            = 0x01;
    static final int RT_CODE_VOLUME          = 0x03;
    static final int RT_CODE_EXP_VOLUME      = 0x24;

    // =====================================================================
    // Protocol Constants -- Philips IntelliVue
    // =====================================================================
    static final int BROADCAST_PORT    = 24005;
    static final int CN_SPDU_SI        = 0x0D;
    static final int AC_SPDU_SI        = 0x0E;
    static final int FN_SPDU_SI        = 0x09;
    static final int DN_SPDU_SI        = 0x0A;
    static final int DATA_EXPORT_SPDU  = 0xE1;

    static final int ROIV_APDU  = 0x0001;
    static final int RORS_APDU  = 0x0002;
    static final int ROLRS_APDU = 0x0005;

    static final int CMD_CONFIRMED_EVENT  = 0x0001;
    static final int CMD_GET              = 0x0003;
    static final int CMD_CONFIRMED_ACTION = 0x0007;

    static final int NOM_PART_OBJ   = 1;
    static final int NOM_PART_SCADA = 2;
    static final int NOM_PART_DIM   = 4;
    static final int NOM_MOC_VMS_MDS          = 0x0021;
    static final int NOM_MOC_VMO_METRIC_NU    = 0x0006;
    static final int NOM_MOC_VMO_METRIC_SA_RT = 0x0009;
    static final int NOM_MOC_VMO_AL_MON       = 0x0002;
    static final int NOM_MOC_PT_DEMOG         = 0x0029;
    static final int NOM_MOC_VMO_METRIC_ENUM  = 0x000A;
    static final int NOM_ACT_POLL_MDIB_DATA_EXT = 0x0C17;
    static final int NOM_NOTI_MDS_CREAT  = 0x0D06;

    static final int NOM_ATTR_NU_VAL_OBS         = 0x0950;
    static final int NOM_ATTR_ID_LABEL           = 0x0927;
    static final int NOM_ATTR_ID_LABEL_STRING     = 0x0928;
    static final int NOM_ATTR_METRIC_SPECN        = 0x093F;
    static final int NOM_ATTR_SA_VAL_OBS          = 0x0967;
    static final int NOM_ATTR_SA_SPECN            = 0x096D;
    static final int NOM_ATTR_SA_FIXED_VAL_SPECN  = 0x0968;
    static final int NOM_ATTR_ID_TYPE             = 0x092F;
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

    // Physio IDs
    static final int NOM_ECG_CARD_BEAT_RATE       = 0x4002;
    static final int NOM_PULS_OXIM_SAT_O2        = 0x4BB8;
    static final int NOM_PULS_OXIM_PULS_RATE     = 0x4BB0;
    static final int NOM_RESP_RATE               = 0x5000;
    static final int NOM_PRESS_BLD_ART_ABP_SYS   = 0x4A51;
    static final int NOM_PRESS_BLD_ART_ABP_DIA   = 0x4A52;
    static final int NOM_PRESS_BLD_ART_ABP_MEAN  = 0x4A53;
    static final int NOM_PRESS_BLD_NONINV_SYS    = 0x4A21;
    static final int NOM_PRESS_BLD_NONINV_DIA    = 0x4A22;
    static final int NOM_PRESS_BLD_NONINV_MEAN   = 0x4A23;
    static final int NOM_CO2_ET                  = 0x5108;
    static final int NOM_TEMP_BLD                = 0x4BB4;
    static final int NOM_PRESS_BLD_VEN_CENT      = 0x4A44;
    static final int NOM_PRESS_BLD_ART_PULM_SYS  = 0x4A61;
    static final int NOM_PRESS_BLD_ART_PULM_DIA  = 0x4A62;
    static final int NOM_PRESS_BLD_ART_PULM_MEAN = 0x4A63;

    static final int NOM_ECG_ELEC_POTL_II_SA  = 0x0102;
    static final int NOM_PLETH_PULS_OXIM_SA   = 0x4BB4;
    static final int NOM_PRESS_BLD_ART_ABP_SA = 0x4A50;
    static final int NOM_RESP_SA              = 0x5000;

    // Unit codes
    static final int NOM_DIM_PERCENT       = 0x0220;
    static final int NOM_DIM_BEAT_PER_MIN  = 0x0AA0;
    static final int NOM_DIM_MMHG          = 0x0F20;
    static final int NOM_DIM_RESP_PER_MIN  = 0x0AE0;
    static final int NOM_DIM_DEGC          = 0x17A0;
    static final int NOM_DIM_DIMLESS       = 0x0200;
    static final int NOM_DIM_MILLI_VOLT    = 0x0F72;

    static final int SEX_UNKNOWN = 0;
    static final int SEX_MALE    = 1;
    static final int SEX_FEMALE  = 2;

    static final int NOM_EVT_HI_HR      = 0x0402;
    static final int NOM_EVT_LO_HR      = 0x0403;
    static final int NOM_EVT_LO_SAT_O2  = 0x0404;
    static final int NOM_EVT_HI_ABP_SYS = 0x0410;

    static final int NOM_VENT_MODE_SIMV = 0x8003;

    static final int METRIC_CAT_MEAS       = 0x0001;
    static final int METRIC_ACCESS_AVAIL   = 0x0002;
    static final int METRIC_ACCESS_RD_ONLY = 0x0004;

    // =====================================================================
    // Waveform constants
    // =====================================================================
    static final int WAVEFORM_BUFFER_SIZE = 600;
    static final int NUM_TRACES = 6;
    static final Color DARK_BG = new Color(10, 10, 10);
    static final Color GRID_COLOR = new Color(40, 40, 40);

    // =====================================================================
    // Shared state
    // =====================================================================
    private final AtomicBoolean draegerRunning = new AtomicBoolean(false);
    private final AtomicBoolean philipsRunning = new AtomicBoolean(false);
    private Thread draegerThread;
    private Thread philipsThread;
    private volatile ServerSocket draegerServerSocket;
    private volatile DatagramSocket philipsSocket;

    // =====================================================================
    // Draeger vitals sliders
    // =====================================================================
    private final JSlider slTidalVol   = makeDarkSlider(200, 800, 450);
    private final JSlider slRespRate   = makeDarkSlider(5, 40, 16);
    private final JSlider slPeakPres   = makeDarkSlider(5, 50, 22);
    private final JSlider slPeep       = makeDarkSlider(0, 20, 5);
    private final JSlider slFio2       = makeDarkSlider(21, 100, 40);
    private final JSlider slCompliance = makeDarkSlider(10, 100, 45);
    private final JSlider slResistance = makeDarkSlider(3, 40, 12);
    private final JSlider slAirwayTemp = makeDarkSlider(28, 40, 34);

    // Draeger mode / waveform controls
    private final JComboBox<String> ventModeCombo = new JComboBox<>(
            new String[]{"IPPV", "SIMV", "CPAP", "ASB", "BIPAP", "PCV", "PSV", "APRV"});
    private final JCheckBox chkDrWaveforms = new JCheckBox("Enable Waveforms");

    // =====================================================================
    // Philips vitals sliders
    // =====================================================================
    private final JSlider slHeartRate   = makeDarkSlider(30, 200, 72);
    private final JSlider slSpo2       = makeDarkSlider(70, 100, 97);
    private final JSlider slAbpSys     = makeDarkSlider(60, 220, 120);
    private final JSlider slAbpDia     = makeDarkSlider(30, 130, 80);
    private final JSlider slEtCo2      = makeDarkSlider(15, 65, 38);
    private final JSlider slTemp       = makeDarkSlider(340, 410, 370); // x10
    private final JSlider slPhRespRate = makeDarkSlider(5, 40, 16);
    private final JSlider slNbpSys     = makeDarkSlider(60, 220, 118);
    private final JSlider slNbpDia     = makeDarkSlider(30, 130, 76);
    private final JSlider slCvp        = makeDarkSlider(0, 30, 8);
    private final JSlider slPapSys     = makeDarkSlider(15, 60, 25);
    private final JSlider slPapDia     = makeDarkSlider(5, 30, 10);

    // =====================================================================
    // Alarm toggles
    // =====================================================================
    private final JCheckBox chkPawHigh    = makeDarkCheck("PAW HIGH");
    private final JCheckBox chkO2High     = makeDarkCheck("% O2 HIGH");
    private final JCheckBox chkMinVolLow  = makeDarkCheck("MIN VOL LOW");
    private final JCheckBox chkApnea      = makeDarkCheck("APNEA");
    private final JCheckBox chkPhHrAlarm  = makeDarkCheck("HR Alarm");
    private final JCheckBox chkPhSpo2Low  = makeDarkCheck("SpO2 Low");
    private final JCheckBox chkPhAbpHigh  = makeDarkCheck("ABP High");

    // =====================================================================
    // Patient demographics
    // =====================================================================
    private final JTextField tfPatientName   = makeDarkField("John Doe", 12);
    private final JTextField tfPatientId     = makeDarkField("P12345", 8);
    private final JTextField tfPatientDob    = makeDarkField("1980-01-15", 10);
    private final JComboBox<String> cbPatientSex = new JComboBox<>(new String[]{"M", "F", "Unknown"});
    private final JTextField tfPatientHeight = makeDarkField("175", 4);
    private final JTextField tfPatientWeight = makeDarkField("75", 4);

    // Philips controls
    private final JCheckBox chkPhWaveforms = makeDarkCheck("Enable Waveforms");
    private final JCheckBox chkBroadcast   = makeDarkCheck("Broadcast");

    // =====================================================================
    // Connection controls
    // =====================================================================
    private final JTextField draegerPortField = makeDarkField("9100", 5);
    private final JTextField philipsPortField = makeDarkField("24105", 5);
    private final JComboBox<String> modelCombo = new JComboBox<>(
            new String[]{"evita", "evita2", "evita4", "v500", "savina", "fabius"});

    private final JLabel draegerStatus     = new JLabel("Stopped");
    private final JLabel philipsStatus     = new JLabel("Stopped");
    private final JLabel draegerConnStatus = new JLabel("No connection");
    private final JLabel philipsConnStatus = new JLabel("No connection");

    private final JButton btnDraegerStart = new JButton("Start");
    private final JButton btnDraegerStop  = new JButton("Stop");
    private final JButton btnPhilipsStart = new JButton("Start");
    private final JButton btnPhilipsStop  = new JButton("Stop");

    // =====================================================================
    // Log
    // =====================================================================
    private final JTextArea logArea = new JTextArea();

    // =====================================================================
    // Waveform display
    // =====================================================================
    private final WaveformPanel waveformPanel;
    private final float[][] waveformBuffers = new float[NUM_TRACES][WAVEFORM_BUFFER_SIZE];
    private int waveformWritePos = 0;
    private final Object waveformLock = new Object();

    // Trace colors
    static final Color[] TRACE_COLORS = {
        new Color(0, 255, 0),     // ECG - green
        new Color(0, 255, 255),   // SpO2 pleth - cyan
        new Color(255, 0, 0),     // ABP - red
        new Color(255, 255, 0),   // Airway pressure - yellow
        new Color(255, 255, 255), // Flow - white
        new Color(255, 0, 255)    // Volume - magenta
    };
    static final String[] TRACE_LABELS = {"II", "Pleth", "ABP", "Paw", "Flow", "Vol"};

    // Numeric display labels
    private final JLabel lblHrValue   = new JLabel("72");
    private final JLabel lblSpo2Value = new JLabel("97");
    private final JLabel lblAbpValue  = new JLabel("120/80");
    private final JLabel lblAbpMean   = new JLabel("(93)");
    private final JLabel lblRrValue   = new JLabel("16");
    private final JLabel lblEtco2Value= new JLabel("38");
    private final JLabel lblTempValue = new JLabel("37.0");
    // Draeger specific numerics
    private final JLabel lblVtValue   = new JLabel("450");
    private final JLabel lblPeepValue = new JLabel("5");
    private final JLabel lblFio2Value = new JLabel("40");
    private final JLabel lblComplValue= new JLabel("45");

    // Waveform generation phase trackers
    private double ecgPhase = 0;
    private double plethPhase = 0;
    private double abpPhase = 0;
    private double respPhase = 0;
    private final Random waveRng = new Random();

    // Draeger protocol state
    private volatile boolean drCommunicationInitialized = false;
    private volatile boolean drRealtimeEnabled = false;
    private final Set<Integer> drRealtimeConfiguredCodes = new LinkedHashSet<>();
    private Set<Integer> drConfiguredDataCodes = null;
    private long drWaveformSampleIndex = 0;
    private final Random drRng = new Random();

    // Philips protocol state
    private volatile boolean phAssociated = false;
    private int phInvokeId = 0;
    private int phPollNumber = 0;
    private final Random phRng = new Random(42);
    private double phEcgPhase = 0;
    private double phPlethPhase = 0;
    private double phAbpWavePhase = 0;
    private double phRespWavePhase = 0;
    private volatile ScheduledExecutorService broadcastExec;

    // =====================================================================
    // Constructor
    // =====================================================================

    public SimulatorMonitor() {
        super("OpenICE Patient Monitor Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(DARK_BG);
        setLayout(new BorderLayout(0, 0));

        // Initialize waveform buffers
        for (int t = 0; t < NUM_TRACES; t++) {
            Arrays.fill(waveformBuffers[t], 0.5f);
        }

        // Top: controls bar
        add(buildControlBar(), BorderLayout.NORTH);

        // Center: waveform + numerics
        waveformPanel = new WaveformPanel();
        JPanel monitorArea = new JPanel(new BorderLayout(0, 0));
        monitorArea.setBackground(DARK_BG);
        monitorArea.add(waveformPanel, BorderLayout.CENTER);
        monitorArea.add(buildNumericsPanel(), BorderLayout.EAST);
        add(monitorArea, BorderLayout.CENTER);

        // Bottom: tabbed controls
        add(buildBottomTabs(), BorderLayout.SOUTH);

        // Button actions
        btnDraegerStart.addActionListener(e -> startDraeger());
        btnDraegerStop.addActionListener(e -> stopDraeger());
        btnPhilipsStart.addActionListener(e -> startPhilips());
        btnPhilipsStop.addActionListener(e -> stopPhilips());
        btnDraegerStop.setEnabled(false);
        btnPhilipsStop.setEnabled(false);

        ventModeCombo.setSelectedItem("SIMV");

        // Waveform animation timer (30ms = ~33fps)
        javax.swing.Timer animTimer = new javax.swing.Timer(30, e -> {
            generateWaveformSamples();
            waveformPanel.repaint();
            updateNumerics();
        });
        animTimer.start();

        setPreferredSize(new Dimension(1200, 800));
        setMinimumSize(new Dimension(1000, 700));
        pack();
        setLocationRelativeTo(null);
    }

    // =====================================================================
    // Waveform Panel -- custom drawing with sweep line
    // =====================================================================

    private class WaveformPanel extends JPanel {
        private BufferedImage backBuffer;

        WaveformPanel() {
            setBackground(DARK_BG);
            setPreferredSize(new Dimension(700, 500));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (backBuffer == null || backBuffer.getWidth() != w || backBuffer.getHeight() != h) {
                backBuffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }

            Graphics2D g = backBuffer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Dark background
            g.setColor(DARK_BG);
            g.fillRect(0, 0, w, h);

            // Grid lines
            g.setColor(GRID_COLOR);
            int gridSpacing = 30;
            for (int x = 0; x < w; x += gridSpacing) g.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += gridSpacing) g.drawLine(0, y, w, y);

            int traceHeight = h / NUM_TRACES;

            synchronized (waveformLock) {
                for (int t = 0; t < NUM_TRACES; t++) {
                    int yOffset = t * traceHeight;
                    int yMid = yOffset + traceHeight / 2;

                    // Trace label
                    g.setColor(TRACE_COLORS[t]);
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                    g.drawString(TRACE_LABELS[t], 4, yOffset + 14);

                    // Separator line
                    g.setColor(new Color(60, 60, 60));
                    g.drawLine(0, yOffset, w, yOffset);

                    // Draw waveform trace
                    g.setColor(TRACE_COLORS[t]);
                    g.setStroke(new BasicStroke(1.5f));
                    int prevX = -1, prevY = -1;
                    for (int i = 0; i < WAVEFORM_BUFFER_SIZE - 1; i++) {
                        int bufIdx = (waveformWritePos + i) % WAVEFORM_BUFFER_SIZE;
                        int bufIdxNext = (waveformWritePos + i + 1) % WAVEFORM_BUFFER_SIZE;

                        // Skip drawing across the sweep line gap
                        if (bufIdxNext == waveformWritePos % WAVEFORM_BUFFER_SIZE) continue;

                        int x1 = (int)((double)i / WAVEFORM_BUFFER_SIZE * w);
                        int x2 = (int)((double)(i + 1) / WAVEFORM_BUFFER_SIZE * w);
                        float val1 = waveformBuffers[t][bufIdx];
                        float val2 = waveformBuffers[t][bufIdxNext];

                        int y1 = yOffset + (int)((1.0f - val1) * traceHeight);
                        int y2 = yOffset + (int)((1.0f - val2) * traceHeight);

                        y1 = Math.max(yOffset + 1, Math.min(yOffset + traceHeight - 1, y1));
                        y2 = Math.max(yOffset + 1, Math.min(yOffset + traceHeight - 1, y2));

                        g.drawLine(x1, y1, x2, y2);
                    }
                }

                // Sweep line
                int sweepX = (int)((double)(waveformWritePos % WAVEFORM_BUFFER_SIZE) / WAVEFORM_BUFFER_SIZE * w);
                g.setColor(new Color(255, 255, 255, 180));
                g.setStroke(new BasicStroke(2.0f));
                g.drawLine(sweepX, 0, sweepX, h);

                // Erase zone ahead of sweep
                g.setColor(new Color(10, 10, 10, 200));
                int eraseWidth = (int)(w * 0.05);
                g.fillRect(sweepX + 1, 0, eraseWidth, h);
            }

            g.dispose();
            g0.drawImage(backBuffer, 0, 0, null);
        }
    }

    // =====================================================================
    // Waveform generation -- called by animation timer
    // =====================================================================

    private void generateWaveformSamples() {
        int hr = slHeartRate.getValue();
        int rr = slPhRespRate.getValue();
        int abpSys = slAbpSys.getValue();
        int abpDia = slAbpDia.getValue();
        int peakPres = slPeakPres.getValue();
        int peep = slPeep.getValue();
        int tv = slTidalVol.getValue();

        double dt = 0.03; // ~30ms per tick
        int samplesPerTick = 6; // generate multiple samples per tick for smoothness

        synchronized (waveformLock) {
            for (int s = 0; s < samplesPerTick; s++) {
                double beatInterval = 60.0 / Math.max(hr, 30);
                double breathInterval = 60.0 / Math.max(rr, 4);

                // ECG
                double ecgBeatFrac = (ecgPhase % beatInterval) / beatInterval;
                float ecgVal = generateEcgSample(ecgBeatFrac);
                waveformBuffers[0][waveformWritePos % WAVEFORM_BUFFER_SIZE] = ecgVal;

                // SpO2 Pleth
                double plethBeatFrac = (plethPhase % beatInterval) / beatInterval;
                float plethVal = generatePlethSample(plethBeatFrac);
                waveformBuffers[1][waveformWritePos % WAVEFORM_BUFFER_SIZE] = plethVal;

                // ABP
                double abpBeatFrac = (abpPhase % beatInterval) / beatInterval;
                float abpVal = generateAbpSample(abpBeatFrac, abpSys, abpDia);
                waveformBuffers[2][waveformWritePos % WAVEFORM_BUFFER_SIZE] = abpVal;

                // Airway Pressure
                double respBreathFrac = (respPhase % breathInterval) / breathInterval;
                float pawVal = generatePawSample(respBreathFrac, peakPres, peep);
                waveformBuffers[3][waveformWritePos % WAVEFORM_BUFFER_SIZE] = pawVal;

                // Flow
                float flowVal = generateFlowSample(respBreathFrac);
                waveformBuffers[4][waveformWritePos % WAVEFORM_BUFFER_SIZE] = flowVal;

                // Volume
                float volVal = generateVolumeSample(respBreathFrac, tv);
                waveformBuffers[5][waveformWritePos % WAVEFORM_BUFFER_SIZE] = volVal;

                double sampleDt = dt / samplesPerTick;
                ecgPhase += sampleDt;
                plethPhase += sampleDt;
                abpPhase += sampleDt;
                respPhase += sampleDt;
                waveformWritePos++;
            }
        }
    }

    private float generateEcgSample(double beatFrac) {
        double val = 0.0;
        // P wave
        if (beatFrac >= 0.10 && beatFrac < 0.20) {
            val = 0.12 * Math.sin(Math.PI * (beatFrac - 0.10) / 0.10);
        }
        // Q wave
        else if (beatFrac >= 0.22 && beatFrac < 0.25) {
            val = -0.08 * Math.sin(Math.PI * (beatFrac - 0.22) / 0.03);
        }
        // R wave
        else if (beatFrac >= 0.25 && beatFrac < 0.30) {
            val = 0.85 * Math.sin(Math.PI * (beatFrac - 0.25) / 0.05);
        }
        // S wave
        else if (beatFrac >= 0.30 && beatFrac < 0.33) {
            val = -0.15 * Math.sin(Math.PI * (beatFrac - 0.30) / 0.03);
        }
        // T wave
        else if (beatFrac >= 0.40 && beatFrac < 0.55) {
            val = 0.25 * Math.sin(Math.PI * (beatFrac - 0.40) / 0.15);
        }
        val += waveRng.nextGaussian() * 0.005;
        return (float)(0.5 + val * 0.4); // normalized 0..1 centered at 0.5
    }

    private float generatePlethSample(double beatFrac) {
        double val;
        if (beatFrac < 0.12) {
            val = beatFrac / 0.12;
        } else if (beatFrac < 0.22) {
            val = 1.0 - 0.25 * ((beatFrac - 0.12) / 0.10);
        } else if (beatFrac < 0.32) {
            val = 0.75 - 0.1 * Math.sin(Math.PI * (beatFrac - 0.22) / 0.10);
        } else {
            val = 0.65 * (1.0 - (beatFrac - 0.32) / 0.68);
        }
        val += waveRng.nextGaussian() * 0.003;
        return (float)(0.1 + val * 0.8); // map to 0.1..0.9
    }

    private float generateAbpSample(double beatFrac, int sys, int dia) {
        double val;
        double abpRange = 250.0; // display range in mmHg
        if (beatFrac < 0.10) {
            val = dia + (sys - dia) * (beatFrac / 0.10);
        } else if (beatFrac < 0.20) {
            double d = (beatFrac - 0.10) / 0.10;
            double mean = (sys + 2.0 * dia) / 3.0;
            val = sys - (sys - mean) * 0.4 * d;
        } else if (beatFrac < 0.30) {
            // dicrotic notch
            double d = (beatFrac - 0.20) / 0.10;
            double mean = (sys + 2.0 * dia) / 3.0;
            double base = sys - (sys - mean) * 0.4;
            val = base - 5 + 10 * Math.sin(Math.PI * d);
        } else {
            double d = (beatFrac - 0.30) / 0.70;
            double mean = (sys + 2.0 * dia) / 3.0;
            double notchVal = sys - (sys - mean) * 0.4 + 5;
            val = notchVal - (notchVal - dia) * d;
        }
        val += waveRng.nextGaussian() * 0.3;
        return (float)(val / abpRange); // normalized
    }

    private float generatePawSample(double breathFrac, int peakPres, int peepVal) {
        double inspRatio = 1.0 / 3.0;
        boolean isInsp = breathFrac < inspRatio;
        double val;
        double range = 60.0;
        if (isInsp) {
            double ip = breathFrac / inspRatio;
            if (ip < 0.1) val = peepVal + (peakPres - peepVal) * (ip / 0.1);
            else if (ip < 0.3) val = peakPres;
            else {
                double plateau = peakPres * 0.82;
                val = plateau + (peakPres - plateau) * Math.exp(-(ip - 0.3) * 5);
            }
        } else {
            double ep = (breathFrac - inspRatio) / (1.0 - inspRatio);
            double plateau = peakPres * 0.82;
            val = peepVal + (plateau - peepVal) * Math.exp(-ep * 4);
        }
        val += waveRng.nextGaussian() * 0.2;
        return (float)((val + 5) / range); // shift up so PEEP is visible
    }

    private float generateFlowSample(double breathFrac) {
        double inspRatio = 1.0 / 3.0;
        boolean isInsp = breathFrac < inspRatio;
        double val;
        if (isInsp) {
            double ip = breathFrac / inspRatio;
            if (ip < 0.05) val = 0.7 * (ip / 0.05);
            else if (ip < 0.95) val = 0.7;
            else val = 0.7 * (1.0 - (ip - 0.95) / 0.05);
        } else {
            double ep = (breathFrac - inspRatio) / (1.0 - inspRatio);
            val = -0.5 * Math.exp(-ep * 3);
        }
        val += waveRng.nextGaussian() * 0.005;
        return (float)(0.5 + val * 0.45); // centered at 0.5
    }

    private float generateVolumeSample(double breathFrac, int tidalVol) {
        double inspRatio = 1.0 / 3.0;
        boolean isInsp = breathFrac < inspRatio;
        double val;
        if (isInsp) {
            val = breathFrac / inspRatio;
        } else {
            double ep = (breathFrac - inspRatio) / (1.0 - inspRatio);
            val = Math.exp(-ep * 4);
        }
        val += waveRng.nextGaussian() * 0.003;
        return (float)(0.05 + val * 0.85); // map to 0.05..0.9
    }

    // =====================================================================
    // UI Builders
    // =====================================================================

    private JPanel buildControlBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBackground(new Color(30, 30, 30));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 5, 3, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        // Draeger row
        c.gridy = 0;
        c.gridx = 0; bar.add(darkLabel("Draeger TCP:"), c);
        c.gridx = 1; bar.add(draegerPortField, c);
        c.gridx = 2; bar.add(darkLabel("Model:"), c);
        c.gridx = 3; bar.add(modelCombo, c);
        c.gridx = 4; bar.add(btnDraegerStart, c);
        c.gridx = 5; bar.add(btnDraegerStop, c);
        draegerStatus.setForeground(Color.GRAY);
        c.gridx = 6; bar.add(draegerStatus, c);
        draegerConnStatus.setForeground(new Color(100, 100, 100));
        c.gridx = 7; bar.add(draegerConnStatus, c);

        // Philips row
        c.gridy = 1;
        c.gridx = 0; bar.add(darkLabel("Philips UDP:"), c);
        c.gridx = 1; bar.add(philipsPortField, c);
        c.gridx = 2; bar.add(chkBroadcast, c);
        c.gridx = 3; bar.add(new JLabel(""), c);
        c.gridx = 4; bar.add(btnPhilipsStart, c);
        c.gridx = 5; bar.add(btnPhilipsStop, c);
        philipsStatus.setForeground(Color.GRAY);
        c.gridx = 6; bar.add(philipsStatus, c);
        philipsConnStatus.setForeground(new Color(100, 100, 100));
        c.gridx = 7; bar.add(philipsConnStatus, c);

        return bar;
    }

    private JPanel buildNumericsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DARK_BG);
        panel.setPreferredSize(new Dimension(200, 0));
        panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(60, 60, 60)));

        panel.add(buildNumericBox("HR", lblHrValue, "bpm", TRACE_COLORS[0]));
        panel.add(buildNumericBox("SpO2", lblSpo2Value, "%", TRACE_COLORS[1]));
        panel.add(buildAbpNumericBox());
        panel.add(buildNumericBox("Resp", lblRrValue, "/min", TRACE_COLORS[3]));
        panel.add(buildNumericBox("etCO2", lblEtco2Value, "mmHg", TRACE_COLORS[4]));
        panel.add(buildNumericBox("Temp", lblTempValue, "\u00B0C", TRACE_COLORS[5]));
        panel.add(buildNumericBox("VT", lblVtValue, "mL", new Color(100, 200, 255)));
        panel.add(buildNumericBox("PEEP", lblPeepValue, "mbar", new Color(200, 200, 100)));
        panel.add(buildNumericBox("FiO2", lblFio2Value, "%", new Color(200, 150, 255)));
        panel.add(buildNumericBox("Compl", lblComplValue, "L/bar", new Color(150, 255, 150)));

        // Glue to fill remaining space
        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private JPanel buildNumericBox(String label, JLabel valueLabel, String unit, Color color) {
        JPanel box = new JPanel(new BorderLayout(2, 0));
        box.setBackground(DARK_BG);
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 50, 50)),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        box.setMaximumSize(new Dimension(200, 55));

        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        lbl.setForeground(color);

        valueLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 32));
        valueLabel.setForeground(color);
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        JLabel unitLbl = new JLabel(unit);
        unitLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        unitLbl.setForeground(color.darker());

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBackground(DARK_BG);
        topRow.add(lbl, BorderLayout.WEST);
        topRow.add(unitLbl, BorderLayout.EAST);

        box.add(topRow, BorderLayout.NORTH);
        box.add(valueLabel, BorderLayout.CENTER);

        return box;
    }

    private JPanel buildAbpNumericBox() {
        JPanel box = new JPanel(new BorderLayout(2, 0));
        box.setBackground(DARK_BG);
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(50, 50, 50)),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        box.setMaximumSize(new Dimension(200, 55));

        JLabel lbl = new JLabel("ABP");
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        lbl.setForeground(TRACE_COLORS[2]);

        JLabel unitLbl = new JLabel("mmHg");
        unitLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        unitLbl.setForeground(TRACE_COLORS[2].darker());

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setBackground(DARK_BG);
        topRow.add(lbl, BorderLayout.WEST);
        topRow.add(unitLbl, BorderLayout.EAST);

        lblAbpValue.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
        lblAbpValue.setForeground(TRACE_COLORS[2]);
        lblAbpValue.setHorizontalAlignment(SwingConstants.RIGHT);

        lblAbpMean.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        lblAbpMean.setForeground(TRACE_COLORS[2]);
        lblAbpMean.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel valPanel = new JPanel(new BorderLayout());
        valPanel.setBackground(DARK_BG);
        valPanel.add(lblAbpValue, BorderLayout.CENTER);
        valPanel.add(lblAbpMean, BorderLayout.SOUTH);

        box.add(topRow, BorderLayout.NORTH);
        box.add(valPanel, BorderLayout.CENTER);

        return box;
    }

    private JTabbedPane buildBottomTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(new Color(30, 30, 30));
        tabs.setForeground(Color.LIGHT_GRAY);
        tabs.setPreferredSize(new Dimension(0, 200));

        tabs.addTab("Draeger Controls", buildDraegerControlsTab());
        tabs.addTab("Philips Controls", buildPhilipsControlsTab());
        tabs.addTab("Alarms", buildAlarmsTab());
        tabs.addTab("Patient", buildPatientTab());
        tabs.addTab("Log", buildLogTab());

        return tabs;
    }

    private JPanel buildDraegerControlsTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(new Color(25, 25, 25));

        JPanel sliders = new JPanel(new GridLayout(2, 4, 8, 4));
        sliders.setBackground(new Color(25, 25, 25));
        sliders.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        addDarkSliderRow(sliders, "VT (mL)", slTidalVol);
        addDarkSliderRow(sliders, "RR (/min)", slRespRate);
        addDarkSliderRow(sliders, "Paw (mbar)", slPeakPres);
        addDarkSliderRow(sliders, "PEEP (mbar)", slPeep);
        addDarkSliderRow(sliders, "FiO2 (%)", slFio2);
        addDarkSliderRow(sliders, "Compl", slCompliance);
        addDarkSliderRow(sliders, "Resist", slResistance);
        addDarkSliderRow(sliders, "AirT (\u00B0C)", slAirwayTemp);

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        modePanel.setBackground(new Color(25, 25, 25));
        modePanel.add(darkLabel("Mode:"));
        modePanel.add(ventModeCombo);
        chkDrWaveforms.setForeground(Color.LIGHT_GRAY);
        chkDrWaveforms.setBackground(new Color(25, 25, 25));
        modePanel.add(chkDrWaveforms);

        panel.add(sliders, BorderLayout.CENTER);
        panel.add(modePanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildPhilipsControlsTab() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(new Color(25, 25, 25));

        JPanel sliders = new JPanel(new GridLayout(3, 4, 8, 4));
        sliders.setBackground(new Color(25, 25, 25));
        sliders.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        addDarkSliderRow(sliders, "HR (bpm)", slHeartRate);
        addDarkSliderRow(sliders, "SpO2 (%)", slSpo2);
        addDarkSliderRow(sliders, "RR (/min)", slPhRespRate);
        addDarkSliderRow(sliders, "ABP Sys", slAbpSys);
        addDarkSliderRow(sliders, "ABP Dia", slAbpDia);
        addDarkSliderRow(sliders, "NBP Sys", slNbpSys);
        addDarkSliderRow(sliders, "NBP Dia", slNbpDia);
        addDarkSliderRow(sliders, "etCO2", slEtCo2);
        addDarkSliderRow(sliders, "Temp(x10)", slTemp);
        addDarkSliderRow(sliders, "CVP", slCvp);
        addDarkSliderRow(sliders, "PAP Sys", slPapSys);
        addDarkSliderRow(sliders, "PAP Dia", slPapDia);

        JPanel optPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        optPanel.setBackground(new Color(25, 25, 25));
        chkPhWaveforms.setForeground(Color.LIGHT_GRAY);
        chkPhWaveforms.setBackground(new Color(25, 25, 25));
        optPanel.add(chkPhWaveforms);

        panel.add(sliders, BorderLayout.CENTER);
        panel.add(optPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildAlarmsTab() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
        panel.setBackground(new Color(25, 25, 25));

        JPanel drAlarms = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        drAlarms.setBackground(new Color(25, 25, 25));
        drAlarms.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60)),
            "Draeger Alarms", TitledBorder.LEFT, TitledBorder.TOP,
            null, Color.LIGHT_GRAY));
        drAlarms.add(chkPawHigh);
        drAlarms.add(chkO2High);
        drAlarms.add(chkMinVolLow);
        drAlarms.add(chkApnea);

        JPanel phAlarms = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        phAlarms.setBackground(new Color(25, 25, 25));
        phAlarms.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60)),
            "Philips Alarms", TitledBorder.LEFT, TitledBorder.TOP,
            null, Color.LIGHT_GRAY));
        phAlarms.add(chkPhHrAlarm);
        phAlarms.add(chkPhSpo2Low);
        phAlarms.add(chkPhAbpHigh);

        panel.add(drAlarms);
        panel.add(phAlarms);
        return panel;
    }

    private JPanel buildPatientTab() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        panel.setBackground(new Color(25, 25, 25));
        panel.add(darkLabel("Name:"));  panel.add(tfPatientName);
        panel.add(darkLabel("ID:"));    panel.add(tfPatientId);
        panel.add(darkLabel("DOB:"));   panel.add(tfPatientDob);
        panel.add(darkLabel("Sex:"));   panel.add(cbPatientSex);
        panel.add(darkLabel("Ht(cm):")); panel.add(tfPatientHeight);
        panel.add(darkLabel("Wt(kg):")); panel.add(tfPatientWeight);
        return panel;
    }

    private JPanel buildLogTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(25, 25, 25));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(15, 15, 15));
        logArea.setForeground(new Color(0, 200, 0));
        logArea.setCaretColor(new Color(0, 200, 0));
        logArea.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBackground(new Color(25, 25, 25));
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> logArea.setText(""));
        buttons.add(clear);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    // =====================================================================
    // Dark UI helpers
    // =====================================================================

    private static JLabel darkLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.LIGHT_GRAY);
        return l;
    }

    private static JSlider makeDarkSlider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setPaintTicks(false);
        s.setBackground(new Color(25, 25, 25));
        s.setForeground(Color.LIGHT_GRAY);
        return s;
    }

    private static JCheckBox makeDarkCheck(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setForeground(Color.LIGHT_GRAY);
        cb.setBackground(new Color(25, 25, 25));
        return cb;
    }

    private static JTextField makeDarkField(String text, int cols) {
        JTextField tf = new JTextField(text, cols);
        tf.setBackground(new Color(40, 40, 40));
        tf.setForeground(Color.WHITE);
        tf.setCaretColor(Color.WHITE);
        return tf;
    }

    private void addDarkSliderRow(JPanel panel, String label, JSlider slider) {
        JPanel row = new JPanel(new BorderLayout(3, 0));
        row.setBackground(new Color(25, 25, 25));
        JLabel lbl = darkLabel(label);
        lbl.setPreferredSize(new Dimension(80, 18));
        lbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        JLabel val = new JLabel(String.valueOf(slider.getValue()));
        val.setForeground(new Color(0, 220, 0));
        val.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        val.setPreferredSize(new Dimension(40, 18));
        slider.addChangeListener(e -> val.setText(String.valueOf(slider.getValue())));
        row.add(lbl, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(val, BorderLayout.EAST);
        panel.add(row);
    }

    // =====================================================================
    // Update numeric displays
    // =====================================================================

    private void updateNumerics() {
        int hr = slHeartRate.getValue();
        int spo2 = slSpo2.getValue();
        int abpS = slAbpSys.getValue();
        int abpD = slAbpDia.getValue();
        int abpM = (abpS + 2 * abpD) / 3;
        int rr = slPhRespRate.getValue();
        int etco2 = slEtCo2.getValue();
        double temp = slTemp.getValue() / 10.0;

        lblHrValue.setText(String.valueOf(hr));
        lblSpo2Value.setText(String.valueOf(spo2));
        lblAbpValue.setText(abpS + "/" + abpD);
        lblAbpMean.setText("(" + abpM + ")");
        lblRrValue.setText(String.valueOf(rr));
        lblEtco2Value.setText(String.valueOf(etco2));
        lblTempValue.setText(String.format("%.1f", temp));

        lblVtValue.setText(String.valueOf(slTidalVol.getValue()));
        lblPeepValue.setText(String.valueOf(slPeep.getValue()));
        lblFio2Value.setText(String.valueOf(slFio2.getValue()));
        lblComplValue.setText(String.valueOf(slCompliance.getValue()));
    }

    // =====================================================================
    // Logging
    // =====================================================================

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            if (logArea.getDocument().getLength() > 50000) {
                try {
                    logArea.getDocument().remove(0, 10000);
                } catch (BadLocationException ignored) {}
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // =====================================================================
    // Draeger Simulator
    // =====================================================================

    private void startDraeger() {
        if (draegerRunning.get()) return;
        int port;
        try {
            port = Integer.parseInt(draegerPortField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid port number");
            return;
        }

        draegerRunning.set(true);
        btnDraegerStart.setEnabled(false);
        btnDraegerStop.setEnabled(true);
        draegerStatus.setText("Running");
        draegerStatus.setForeground(new Color(0, 200, 0));

        String model = (String) modelCombo.getSelectedItem();
        log("[Draeger] Starting on TCP port " + port + " model=" + model);

        draegerThread = new Thread(() -> runDraegerServer(port), "draeger-sim-monitor");
        draegerThread.setDaemon(true);
        draegerThread.start();
    }

    private void stopDraeger() {
        draegerRunning.set(false);
        try {
            if (draegerServerSocket != null && !draegerServerSocket.isClosed()) {
                draegerServerSocket.close();
            }
        } catch (IOException ignored) {}
        btnDraegerStart.setEnabled(true);
        btnDraegerStop.setEnabled(false);
        draegerStatus.setText("Stopped");
        draegerStatus.setForeground(Color.GRAY);
        draegerConnStatus.setText("No connection");
        drRealtimeEnabled = false;
        drRealtimeConfiguredCodes.clear();
        drConfiguredDataCodes = null;
        log("[Draeger] Stopped");
    }

    private void runDraegerServer(int port) {
        try {
            draegerServerSocket = new ServerSocket(port);
            draegerServerSocket.setSoTimeout(1000);
            log("[Draeger] Listening on port " + port);
            while (draegerRunning.get()) {
                try {
                    Socket client = draegerServerSocket.accept();
                    SwingUtilities.invokeLater(() ->
                        draegerConnStatus.setText("Connected: " + client.getRemoteSocketAddress()));
                    log("[Draeger] Gateway connected from " + client.getRemoteSocketAddress());
                    drCommunicationInitialized = false;
                    drRealtimeEnabled = false;
                    drRealtimeConfiguredCodes.clear();
                    drConfiguredDataCodes = null;
                    drWaveformSampleIndex = 0;
                    handleDraegerClient(client);
                    SwingUtilities.invokeLater(() -> draegerConnStatus.setText("Disconnected"));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            if (draegerRunning.get()) log("[Draeger] Error: " + e.getMessage());
        }
    }

    private void handleDraegerClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buf = new byte[512];
            int pos = 0;
            boolean inFrame = false;

            while (draegerRunning.get() && !client.isClosed()) {
                int b = in.read();
                if (b == -1) break;
                if (b == DC1 || b == DC3) continue;

                if (b == ESC) {
                    inFrame = true;
                    pos = 0;
                    buf[pos++] = (byte) b;
                } else if (b == CR && inFrame) {
                    inFrame = false;
                    if (pos >= 4) handleDraegerCommand(buf, pos, out);
                    pos = 0;
                } else if (inFrame && pos < buf.length) {
                    buf[pos++] = (byte) b;
                }
            }
        } catch (IOException e) {
            if (draegerRunning.get()) log("[Draeger] Client error: " + e.getMessage());
        }
    }

    private void handleDraegerCommand(byte[] buf, int len, OutputStream out) throws IOException {
        int cmd = buf[1] & 0xFF;

        // Verify checksum
        int receivedCs = asciiHexDecode(buf[len - 2], buf[len - 1]);
        int calcCs = 0;
        for (int i = 0; i < len - 2; i++) calcCs += buf[i] & 0xFF;
        calcCs &= 0xFF;
        if (receivedCs != calcCs) {
            log("[Draeger] BAD CHECKSUM cmd=0x" + hexByte(cmd));
            sendDraegerNak(out);
            return;
        }

        // Extract argument
        byte[] argument = null;
        if (len > 4) {
            argument = new byte[len - 4];
            System.arraycopy(buf, 2, argument, 0, argument.length);
        }

        String cmdName = draegerCmdName(cmd);
        log("[Draeger] <- " + cmdName);

        // Send realtime burst if enabled
        if (drRealtimeEnabled && chkDrWaveforms.isSelected()) {
            sendDraegerRealtimeBurst(out);
        }

        switch (cmd) {
            case CMD_ICC:
                drCommunicationInitialized = true;
                drConfiguredDataCodes = null;
                drRealtimeEnabled = false;
                drRealtimeConfiguredCodes.clear();
                sendDraegerControlResponse(cmd, out);
                log("[Draeger] -> ICC acknowledged");
                break;
            case CMD_NOP:
            case CMD_STOP:
                if (cmd == CMD_STOP) {
                    drCommunicationInitialized = false;
                    drRealtimeEnabled = false;
                    drRealtimeConfiguredCodes.clear();
                }
                sendDraegerControlResponse(cmd, out);
                break;
            case CMD_REQ_DEVICE_ID:
                sendDraegerDeviceId(out);
                break;
            case CMD_REQ_DATETIME:
                sendDraegerDateTime(out);
                break;
            case CMD_REQ_DATA_CP1:
                sendDraegerDataCP1(out);
                break;
            case CMD_REQ_DATA_CP2:
                sendDraegerDataCP2(out);
                break;
            case CMD_REQ_ALARMS_CP1:
            case CMD_REQ_ALARMS_CP2:
                sendDraegerAlarms(cmd, out);
                break;
            case CMD_REQ_LOW_ALRM_CP1:
            case CMD_REQ_HI_ALRM_CP1:
                sendDraegerAlarmLimits(cmd, out);
                break;
            case CMD_REQ_SETTINGS:
                sendDraegerSettings(out);
                break;
            case CMD_REQ_TEXT_MSG:
                sendDraegerTextMessages(out);
                break;
            case CMD_CONFIGURE_RESP:
                handleDraegerConfigureDataResponse(argument, out);
                break;
            case CMD_REQ_RT_CONFIG:
                sendDraegerRealtimeConfig(out);
                break;
            case CMD_CONFIG_RT:
                handleDraegerConfigureRealtime(argument, out);
                break;
            default:
                sendDraegerControlResponse(cmd, out);
                break;
        }
    }

    // --- Draeger response builders ---

    private void sendDraegerControlResponse(int cmd, OutputStream out) throws IOException {
        int cs = SOH + cmd;
        out.write(SOH); out.write(cmd);
        writeAsciiHexChecksum(out, cs);
        out.write(CR); out.flush();
    }

    private void sendDraegerNak(OutputStream out) throws IOException {
        int cs = SOH + NAK;
        out.write(SOH); out.write(NAK);
        writeAsciiHexChecksum(out, cs);
        out.write(CR); out.flush();
    }

    private void sendDraegerDataResponse(int cmd, byte[] data, OutputStream out) throws IOException {
        int cs = SOH + cmd;
        for (byte b : data) cs += b & 0xFF;
        out.write(SOH); out.write(cmd); out.write(data);
        writeAsciiHexChecksum(out, cs);
        out.write(CR); out.flush();
    }

    private void sendDraegerDeviceId(OutputStream out) throws IOException {
        String model = (String) modelCombo.getSelectedItem();
        String[][] models = {
            {"evita","8210","Evita","01.00:03.00"}, {"evita2","8200","Evita 2","01.00:03.00"},
            {"evita4","8214","Evita 4","02.00:03.00"}, {"v500","8410","Evita V500","03.20:06.00"},
            {"savina","8310","Savina","01.00:03.00"}, {"fabius","8088","Fabius GS","02.02:04.00"}
        };
        String id = "8210", name = "Evita", rev = "01.00:03.00";
        for (String[] m : models) {
            if (m[0].equals(model)) { id = m[1]; name = m[2]; rev = m[3]; break; }
        }
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(padTo(id, 4).getBytes());
        d.write(0x27); d.write(name.getBytes()); d.write(0x27);
        d.write(padTo(rev, 11).getBytes());
        sendDraegerDataResponse(CMD_REQ_DEVICE_ID, d.toByteArray(), out);
        log("[Draeger] -> DeviceID: " + id + " '" + name + "' " + rev);
    }

    private void sendDraegerDateTime(OutputStream out) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String[] m = {"JAN","FEB","MAR","APR","MAI","JUN","JUL","AUG","SEP","OKT","NOV","DEZ"};
        String dt = String.format("%02d:%02d:%02d%02d-%s-%02d",
                now.getHour(), now.getMinute(), now.getSecond(),
                now.getDayOfMonth(), m[now.getMonthValue()-1], now.getYear()%100);
        sendDraegerDataResponse(CMD_REQ_DATETIME, dt.getBytes(), out);
    }

    private void sendDraegerDataCP1(OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        int tv = slTidalVol.getValue();
        int rr = slRespRate.getValue();
        int pp = slPeakPres.getValue();
        int peep = slPeep.getValue();
        int fio2 = slFio2.getValue();
        int comp = slCompliance.getValue();
        int res = slResistance.getValue();
        int at = slAirwayTemp.getValue();
        double mvol = tv * rr / 1000.0;

        drMaybeAddRecord(d, 0x07, comp);
        drMaybeAddRecord(d, 0x08, res);
        drMaybeAddRecord(d, 0x73, (int)(pp * 0.55));
        drMaybeAddRecord(d, 0x78, peep);
        drMaybeAddRecord(d, 0x7D, pp);
        drMaybeAddRecord(d, 0x82, tv);
        drMaybeAddRecord(d, 0xB5, rr);
        drMaybeAddRecord(d, 0xB9, (int)(mvol * 10));
        drMaybeAddRecord(d, 0xC1, at);
        drMaybeAddRecord(d, 0xF0, fio2);

        sendDraegerDataResponse(CMD_REQ_DATA_CP1, d.toByteArray(), out);
        log("[Draeger] -> CP1: VT=" + tv + " RR=" + rr + " Paw=" + pp +
            " PEEP=" + peep + " FiO2=" + fio2);
    }

    private void drMaybeAddRecord(ByteArrayOutputStream d, int code, int value) throws IOException {
        if (drConfiguredDataCodes == null || drConfiguredDataCodes.contains(code)) {
            addDrRecord(d, code, value);
        }
    }

    private void sendDraegerDataCP2(OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        drMaybeAddRecord(d, 0xD6, slRespRate.getValue());
        sendDraegerDataResponse(CMD_REQ_DATA_CP2, d.toByteArray(), out);
    }

    private void sendDraegerAlarms(int cmd, OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        int pp = slPeakPres.getValue();
        double mv = slTidalVol.getValue() * slRespRate.getValue() / 1000.0;
        int rr = slRespRate.getValue();
        int fio2 = slFio2.getValue();

        if (cmd == CMD_REQ_ALARMS_CP1) {
            if (chkPawHigh.isSelected())   addAlarmRecord(d, 27, 0x10, "PAW HIGH    ");
            if (chkO2High.isSelected())    addAlarmRecord(d, 23, 0x37, "% O2 HIGH   ");
            if (chkMinVolLow.isSelected()) addAlarmRecord(d, 26, 0x19, "MIN VOL LOW ");
            if (chkApnea.isSelected())     addAlarmRecord(d, 27, 0x98, "APNEA EVITA ");

            if (pp > 35 && !chkPawHigh.isSelected())
                addAlarmRecord(d, 2, 0x10, "PAW HIGH    ");
            if (mv < 3.0 && !chkMinVolLow.isSelected())
                addAlarmRecord(d, 1, 0x0C, "MIN VOL LOW ");
            if (rr < 4 && !chkApnea.isSelected())
                addAlarmRecord(d, 1, 0x1A, "APNEA       ");
            if (fio2 > 60 && !chkO2High.isSelected())
                addAlarmRecord(d, 3, 0x37, "% O2 HIGH   ");
        }
        sendDraegerDataResponse(cmd, d.toByteArray(), out);
        if (d.size() > 0) log("[Draeger] -> " + (d.size()/15) + " alarm(s) active");
    }

    private void sendDraegerAlarmLimits(int cmd, OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        if (cmd == CMD_REQ_HI_ALRM_CP1) {
            addDrRecord(d, 0x7D, 35);
            addDrRecord(d, 0xB9, 150);
            addDrRecord(d, 0xF0, 100);
        } else {
            addDrRecord(d, 0xB9, 30);
            addDrRecord(d, 0xF0, 18);
        }
        sendDraegerDataResponse(cmd, d.toByteArray(), out);
    }

    private void sendDraegerSettings(OutputStream out) throws IOException {
        int fio2 = slFio2.getValue();
        int tv = slTidalVol.getValue();
        int peep = slPeep.getValue();

        ByteArrayOutputStream d = new ByteArrayOutputStream();
        addDrSettingRecord(d, 0x01, fio2, 0);
        addDrSettingRecord(d, 0x02, 400, 1);
        addDrSettingRecord(d, 0x04, tv, 3);
        addDrSettingRecord(d, 0x07, 10, 1);
        addDrSettingRecord(d, 0x08, 20, 1);
        addDrSettingRecord(d, 0x09, 120, 1);
        addDrSettingRecord(d, 0x0B, peep * 10, 1);
        addDrSettingRecord(d, 0x13, 400, 1);

        sendDraegerDataResponse(CMD_REQ_SETTINGS, d.toByteArray(), out);
        log("[Draeger] -> Settings: O2=" + fio2 + "% VT=" + tv + "mL PEEP=" + peep + " Pmax=40");
    }

    private void addDrSettingRecord(ByteArrayOutputStream d, int code, int value, int decimals) throws IOException {
        d.write(asciiHexHi(code));
        d.write(asciiHexLo(code));
        String valStr;
        if (decimals > 0) {
            StringBuilder fmt = new StringBuilder();
            int intPart = value;
            for (int i = 0; i < decimals; i++) {
                fmt.insert(0, (char)('0' + (intPart % 10)));
                intPart /= 10;
            }
            String intStr = String.valueOf(intPart);
            valStr = intStr + "." + fmt.toString();
        } else {
            valStr = String.valueOf(value);
        }
        while (valStr.length() < 5) valStr = " " + valStr;
        if (valStr.length() > 5) valStr = valStr.substring(valStr.length() - 5);
        d.write(valStr.getBytes());
    }

    private void sendDraegerTextMessages(OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        String mode = (String) ventModeCombo.getSelectedItem();
        String text = "Mode " + mode;
        d.write(asciiHexHi(0x06));
        d.write(asciiHexLo(0x06));
        d.write(text.length());
        d.write(text.getBytes());
        d.write(ETX);

        sendDraegerDataResponse(CMD_REQ_TEXT_MSG, d.toByteArray(), out);
        log("[Draeger] -> Text: Mode " + mode);
    }

    private void handleDraegerConfigureDataResponse(byte[] argument, OutputStream out) throws IOException {
        if (argument != null && argument.length >= 2) {
            drConfiguredDataCodes = new LinkedHashSet<>();
            for (int i = 0; i + 1 < argument.length; i += 2) {
                int code = asciiHexDecode(argument[i], argument[i + 1]);
                drConfiguredDataCodes.add(code);
            }
            log("[Draeger] -> Configured " + drConfiguredDataCodes.size() + " data codes");
        } else {
            drConfiguredDataCodes = null;
            log("[Draeger] -> Reset to all data codes");
        }
        sendDraegerControlResponse(CMD_CONFIGURE_RESP, out);
    }

    private void sendDraegerRealtimeConfig(OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        if (drRealtimeEnabled) {
            for (int code : drRealtimeConfiguredCodes) {
                d.write(asciiHexHi(code));
                d.write(asciiHexLo(code));
            }
        }
        sendDraegerDataResponse(CMD_REQ_RT_CONFIG, d.toByteArray(), out);
        log("[Draeger] -> RT config: " + (drRealtimeEnabled ? drRealtimeConfiguredCodes.size() + " codes" : "disabled"));
    }

    private void handleDraegerConfigureRealtime(byte[] argument, OutputStream out) throws IOException {
        if (argument != null && argument.length >= 2) {
            drRealtimeConfiguredCodes.clear();
            for (int i = 0; i + 3 < argument.length; i += 4) {
                int code = asciiHexDecode(argument[i], argument[i + 1]);
                drRealtimeConfiguredCodes.add(code);
            }
            if (drRealtimeConfiguredCodes.isEmpty() && argument.length >= 2) {
                int code = asciiHexDecode(argument[0], argument[1]);
                drRealtimeConfiguredCodes.add(code);
            }
            drRealtimeEnabled = !drRealtimeConfiguredCodes.isEmpty();
            drWaveformSampleIndex = 0;
            log("[Draeger] -> Realtime enabled for " + drRealtimeConfiguredCodes.size() + " codes");
        } else {
            drRealtimeEnabled = false;
            drRealtimeConfiguredCodes.clear();
            log("[Draeger] -> Realtime disabled");
        }
        sendDraegerControlResponse(CMD_CONFIG_RT, out);
    }

    private void sendDraegerRealtimeBurst(OutputStream out) throws IOException {
        if (!drRealtimeEnabled || drRealtimeConfiguredCodes.isEmpty()) return;
        if (!chkDrWaveforms.isSelected()) return;

        int rr = slRespRate.getValue();
        int pp = slPeakPres.getValue();
        int peep = slPeep.getValue();
        int tv = slTidalVol.getValue();
        double breathPeriod = 60.0 / Math.max(rr, 1);
        double ieI = 1.0, ieE = 2.0;
        double inspRatio = ieI / (ieI + ieE);

        int samplesPerBurst = 10;
        for (int s = 0; s < samplesPerBurst; s++) {
            double t = drWaveformSampleIndex / 100.0;
            double breathPhase = (t % breathPeriod) / breathPeriod;

            if (drWaveformSampleIndex > 0) {
                double prevT = (drWaveformSampleIndex - 1) / 100.0;
                double prevPhase = (prevT % breathPeriod) / breathPeriod;
                if (prevPhase > 0.9 && breathPhase < 0.1) {
                    out.write(RT_SYNC);
                    out.write(RT_SYNC_INSP_START);
                }
            }

            for (int code : drRealtimeConfiguredCodes) {
                int sampleValue = generateDrWaveformSample(code, breathPhase, inspRatio, pp, peep, tv);
                int hiByte = 0x80 | ((code & 0x1F) << 2) | ((sampleValue >> 7) & 0x03);
                int loByte = 0x80 | (sampleValue & 0x7F);
                out.write(hiByte);
                out.write(loByte);
            }
            drWaveformSampleIndex++;
        }
        out.flush();
    }

    private int generateDrWaveformSample(int code, double breathPhase, double inspRatio,
                                          int peakPres, int peepVal, int tidalVol) {
        boolean isInsp = breathPhase < inspRatio;
        double plateauPres = peakPres * 0.82;
        double value = 0;

        switch (code) {
            case RT_CODE_AIRWAY_PRESSURE:
                if (isInsp) {
                    double ip = breathPhase / inspRatio;
                    if (ip < 0.1) value = peepVal + (peakPres - peepVal) * (ip / 0.1);
                    else if (ip < 0.3) value = peakPres;
                    else value = plateauPres + (peakPres - plateauPres) * Math.exp(-(ip - 0.3) * 5);
                } else {
                    double ep = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = peepVal + (plateauPres - peepVal) * Math.exp(-ep * 4);
                }
                value += drRng.nextGaussian() * 0.3;
                value = Math.max(-10, Math.min(60, value));
                return (int) Math.round((value + 10) * 511.0 / 70.0);

            case RT_CODE_FLOW:
                double inspFlow = 40;
                if (isInsp) {
                    double ip = breathPhase / inspRatio;
                    if (ip < 0.05) value = inspFlow * (ip / 0.05);
                    else if (ip < 0.95) value = inspFlow;
                    else value = inspFlow * (1.0 - (ip - 0.95) / 0.05);
                } else {
                    double ep = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = -inspFlow * 0.8 * Math.exp(-ep * 3);
                }
                value += drRng.nextGaussian() * 0.5;
                value = Math.max(-60, Math.min(60, value));
                return (int) Math.round((value + 60) * 511.0 / 120.0);

            case RT_CODE_VOLUME:
                if (isInsp) {
                    value = tidalVol * (breathPhase / inspRatio);
                } else {
                    double ep = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = tidalVol * Math.exp(-ep * 4);
                }
                value += drRng.nextGaussian() * 2;
                value = Math.max(0, Math.min(800, value));
                return (int) Math.round(value * 511.0 / 800.0);

            case RT_CODE_EXP_VOLUME:
                if (isInsp) {
                    value = 0;
                } else {
                    double ep = (breathPhase - inspRatio) / (1.0 - inspRatio);
                    value = tidalVol * (1.0 - Math.exp(-ep * 4));
                }
                value += drRng.nextGaussian() * 2;
                value = Math.max(0, Math.min(800, value));
                return (int) Math.round(value * 511.0 / 800.0);

            default:
                return 0;
        }
    }

    private void addDrRecord(ByteArrayOutputStream d, int code, int value) throws IOException {
        d.write(asciiHexHi(code)); d.write(asciiHexLo(code));
        String v = String.valueOf(Math.abs(value));
        while (v.length() < 4) v = " " + v;
        if (v.length() > 4) v = v.substring(v.length()-4);
        d.write(v.getBytes());
    }

    private void addAlarmRecord(ByteArrayOutputStream d, int priority, int code, String phrase) throws IOException {
        d.write(0x30 + priority);
        d.write(asciiHexHi(code)); d.write(asciiHexLo(code));
        String p = padTo(phrase, 12);
        d.write(p.substring(0, 12).getBytes());
    }

    // =====================================================================
    // Philips Simulator
    // =====================================================================

    private void startPhilips() {
        if (philipsRunning.get()) return;
        int port;
        try {
            port = Integer.parseInt(philipsPortField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Invalid port number");
            return;
        }

        philipsRunning.set(true);
        btnPhilipsStart.setEnabled(false);
        btnPhilipsStop.setEnabled(true);
        philipsStatus.setText("Running");
        philipsStatus.setForeground(new Color(0, 200, 0));
        log("[Philips] Starting on UDP port " + port);

        philipsThread = new Thread(() -> runPhilipsServer(port), "philips-sim-monitor");
        philipsThread.setDaemon(true);
        philipsThread.start();
    }

    private void stopPhilips() {
        philipsRunning.set(false);
        if (broadcastExec != null) {
            broadcastExec.shutdownNow();
            broadcastExec = null;
        }
        try {
            if (philipsSocket != null && !philipsSocket.isClosed()) {
                philipsSocket.close();
            }
        } catch (Exception ignored) {}
        phAssociated = false;
        btnPhilipsStart.setEnabled(true);
        btnPhilipsStop.setEnabled(false);
        philipsStatus.setText("Stopped");
        philipsStatus.setForeground(Color.GRAY);
        philipsConnStatus.setText("No connection");
        log("[Philips] Stopped");
    }

    private void runPhilipsServer(int port) {
        try {
            philipsSocket = new DatagramSocket(port);
            philipsSocket.setSoTimeout(500);
            philipsSocket.setBroadcast(true);
            log("[Philips] Listening on UDP port " + port);

            broadcastExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ph-broadcast");
                t.setDaemon(true);
                return t;
            });
            broadcastExec.scheduleAtFixedRate(() -> {
                if (!phAssociated && philipsRunning.get() && chkBroadcast.isSelected()) {
                    try {
                        sendPhilipsConnectIndication(philipsSocket, port);
                        SwingUtilities.invokeLater(() -> philipsConnStatus.setText("Broadcasting..."));
                    } catch (Exception ignored) {}
                }
            }, 0, 5, TimeUnit.SECONDS);

            byte[] recvBuf = new byte[8192];
            phAssociated = false;
            phPollNumber = 0;

            while (philipsRunning.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);
                    philipsSocket.receive(pkt);
                    ByteBuffer in = ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength());
                    in.order(ByteOrder.BIG_ENDIAN);
                    InetSocketAddress client = new InetSocketAddress(pkt.getAddress(), pkt.getPort());

                    int first = in.get(0) & 0xFF;

                    if (first == CN_SPDU_SI) {
                        log("[Philips] <- Association Request from " + client);
                        SwingUtilities.invokeLater(() ->
                            philipsConnStatus.setText("Connected: " + client));

                        sendPhilipsAssocResponse(philipsSocket, client);
                        phAssociated = true;
                        log("[Philips] -> Association Response (accepted)");

                        sendPhilipsMdsEvent(philipsSocket, client);
                        log("[Philips] -> MDS Create Event");

                    } else if (first == DATA_EXPORT_SPDU && phAssociated) {
                        in.position(0);
                        in.get(); // skip 0xE1
                        in.getShort(); // session len
                        int roType = in.getShort() & 0xFFFF;

                        if (roType == ROIV_APDU) {
                            in.getShort(); // ro length
                            int invokeId = in.getShort() & 0xFFFF;
                            int cmdType = in.getShort() & 0xFFFF;
                            int cmdLen = in.getShort() & 0xFFFF;

                            if (cmdType == CMD_CONFIRMED_ACTION) {
                                if (in.remaining() >= 6) in.position(in.position() + 6);
                                int scope = in.remaining() >= 4 ? in.getInt() : 0;
                                int actionType = in.remaining() >= 2 ? (in.getShort() & 0xFFFF) : 0;

                                phPollNumber++;
                                log("[Philips] <- Poll Request #" + phPollNumber);

                                if (actionType == (NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF)) {
                                    sendPhilipsExtendedPoll(philipsSocket, client, invokeId);
                                } else {
                                    sendPhilipsPollResult(philipsSocket, client, invokeId);
                                }

                                log("[Philips] -> Poll Result: HR=" + slHeartRate.getValue() +
                                    " SpO2=" + slSpo2.getValue() + " RR=" + slPhRespRate.getValue() +
                                    " ABP=" + slAbpSys.getValue() + "/" + slAbpDia.getValue() +
                                    " CVP=" + slCvp.getValue() +
                                    " PAP=" + slPapSys.getValue() + "/" + slPapDia.getValue());
                            } else if (cmdType == CMD_GET) {
                                log("[Philips] <- Get Request");
                                sendPhilipsGetResult(philipsSocket, client, invokeId);
                            } else {
                                sendPhilipsEmptyResult(philipsSocket, client, invokeId, cmdType);
                            }
                        }
                    } else if (first == FN_SPDU_SI) {
                        phAssociated = false;
                        log("[Philips] <- Release Request");
                        ByteBuffer resp = ByteBuffer.allocate(4);
                        resp.order(ByteOrder.BIG_ENDIAN);
                        resp.put((byte) DN_SPDU_SI);
                        resp.put((byte) 0x00);
                        resp.putShort((short) 0);
                        resp.flip();
                        sendUdp(philipsSocket, client, resp);
                        SwingUtilities.invokeLater(() -> philipsConnStatus.setText("Disconnected"));
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            if (philipsRunning.get()) log("[Philips] Error: " + e.getMessage());
        }
    }

    // --- Philips Connect Indication ---

    private void sendPhilipsConnectIndication(DatagramSocket socket, int listenPort) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) CN_SPDU_SI);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putInt(0x80000000);

        try {
            InetAddress localAddr = InetAddress.getLocalHost();
            byte[] addrBytes = localAddr.getAddress();
            buf.putShort((short) 0x0001); buf.putShort((short) 4); buf.put(addrBytes);
            buf.putShort((short) 0x0002); buf.putShort((short) 2); buf.putShort((short) listenPort);
        } catch (UnknownHostException e) {
            buf.putShort((short) 0x0001); buf.putShort((short) 4); buf.putInt(0);
            buf.putShort((short) 0x0002); buf.putShort((short) 2); buf.putShort((short) listenPort);
        }

        buf.putShort((short) 0x0003); buf.putShort((short) 4); buf.putInt(0x40000000);
        buf.putShort((short) 0x0004); buf.putShort((short) 4); buf.putInt(0x40000000);

        int totalLen = buf.position() - lenPos - 2;
        buf.putShort(lenPos, (short) totalLen);

        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        try {
            socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), BROADCAST_PORT));
        } catch (IOException ignored) {}
    }

    // --- Philips Association Response ---

    private void sendPhilipsAssocResponse(DatagramSocket socket, InetSocketAddress addr) throws IOException {
        ByteBuffer userInfo = ByteBuffer.allocate(256);
        userInfo.order(ByteOrder.BIG_ENDIAN);
        userInfo.putInt(0x80000000);
        userInfo.putInt(0x40000000);
        userInfo.putInt(0x00000000);
        userInfo.putInt(0x80000000);
        userInfo.putInt(0x20000000);

        // PollProfileSupport
        userInfo.putInt(100000);
        userInfo.putInt(4096);
        userInfo.putInt(4096);
        userInfo.putInt(0x00080000);
        userInfo.putInt(0x60000000);
        userInfo.putInt(0x00000000);

        // MdibObjectSupport
        int numObjClasses = 6;
        userInfo.putShort((short) numObjClasses);
        userInfo.putShort((short) (numObjClasses * 8));
        int[][] objClasses = {
            {NOM_MOC_VMS_MDS, 1}, {NOM_MOC_VMO_METRIC_NU, 64},
            {NOM_MOC_VMO_METRIC_SA_RT, 16}, {NOM_MOC_VMO_AL_MON, 2},
            {NOM_MOC_PT_DEMOG, 1}, {NOM_MOC_VMO_METRIC_ENUM, 16}
        };
        for (int[] oc : objClasses) {
            userInfo.putShort((short) oc[0]);
            userInfo.putShort((short) 0);
            userInfo.putInt(oc[1]);
        }
        userInfo.flip();
        int userInfoLen = userInfo.remaining();

        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) AC_SPDU_SI);
        buf.put((byte) 0xC2);
        int totalLenPos = buf.position();
        buf.putShort((short) 0);
        int bodyStart = buf.position();

        buf.putShort((short) 0xA1); buf.putShort((short) 0x06);
        buf.put((byte) 0x01); buf.put((byte) 0x00);
        buf.putShort((short) 0x0001); buf.putShort((short) 0x0001);

        buf.putShort((short) 0xBE);
        buf.putShort((short) (userInfoLen + 4));
        buf.putShort((short) 0x0001);
        buf.putShort((short) userInfoLen);
        buf.put(userInfo);

        int totalLen = buf.position() - bodyStart;
        buf.putShort(totalLenPos, (short) totalLen);

        buf.flip();
        sendUdp(socket, addr, buf);
    }

    // --- Philips MDS Create Event ---

    private void sendPhilipsMdsEvent(DatagramSocket socket, InetSocketAddress addr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) ROIV_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) phInvokeId++);
        buf.putShort((short) CMD_CONFIRMED_EVENT);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putInt(0);
        buf.putShort((short) NOM_NOTI_MDS_CREAT);
        int eventLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 3);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_SYS_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) NOM_MOC_VMS_MDS);

        writePhSystemModel(buf);

        buf.putShort((short) NOM_ATTR_TIME_ABS);
        buf.putShort((short) 8);
        writePhAbsoluteTime(buf);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
        buf.putShort(eventLenPos, (short)(attrEnd - eventLenPos - 2));

        int end = buf.position();
        buf.putShort(lenPos, (short)(end - lenPos - 2));
        buf.putShort(roLenPos, (short)(end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short)(end - cmdLenPos - 2));

        buf.flip();
        sendUdp(socket, addr, buf);
    }

    // --- Philips Extended Poll ---

    private void sendPhilipsExtendedPoll(DatagramSocket socket, InetSocketAddress addr, int invokeId) throws IOException {
        ByteBuffer numSeg = buildPhNumericsSegment(invokeId);
        sendUdp(socket, addr, numSeg);
        log("[Philips]   -> ROLRS: Numerics segment");

        if (chkPhWaveforms.isSelected()) {
            ByteBuffer waveSeg = buildPhWaveformSegment(invokeId);
            sendUdp(socket, addr, waveSeg);
            log("[Philips]   -> ROLRS: Waveform segment");
        }

        ByteBuffer finalSeg = buildPhFinalSegment(invokeId);
        sendUdp(socket, addr, finalSeg);
        log("[Philips]   -> RORS: Final segment (alarms, patient, enums)");
    }

    private ByteBuffer buildPhNumericsSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) ROLRS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) clientInvokeId);
        buf.putShort((short) 1);

        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putShort((short)(NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePhPollHeader(buf);

        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();
        buf.putShort((short) 0);

        int[][] numerics = getPhNumerics();
        buf.putShort((short) numerics.length);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (int[] n : numerics) {
            writePhNumericObs(buf, n);
        }

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short)(obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short)(obsEnd - pollInfoStart));
        fixPhLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildPhWaveformSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) ROLRS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) clientInvokeId);
        buf.putShort((short) 2);

        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putShort((short)(NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePhPollHeader(buf);

        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 4);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        writePhWaveformObs(buf, 0x50, NOM_ECG_ELEC_POTL_II_SA, generatePhEcgSamples(), 500, NOM_DIM_MILLI_VOLT);
        writePhWaveformObs(buf, 0x51, NOM_PLETH_PULS_OXIM_SA, generatePhPlethSamples(), 125, NOM_DIM_DIMLESS);
        writePhWaveformObs(buf, 0x52, NOM_PRESS_BLD_ART_ABP_SA, generatePhAbpSamples(), 125, NOM_DIM_MMHG);
        writePhWaveformObs(buf, 0x53, NOM_RESP_SA, generatePhRespSamples(), 62, NOM_DIM_DIMLESS);

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short)(obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short)(obsEnd - pollInfoStart));
        fixPhLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    private ByteBuffer buildPhFinalSegment(int clientInvokeId) {
        ByteBuffer buf = ByteBuffer.allocate(2048);
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
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putShort((short)(NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePhPollHeader(buf);

        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 3);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        writePhAlarmMonitorObs(buf, 0x60);
        writePhPatientDemogObs(buf, 0x70);
        writePhEnumObs(buf, 0x80);

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short)(obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short)(obsEnd - pollInfoStart));
        fixPhLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        return buf;
    }

    // --- Philips single poll (non-extended) ---

    private void sendPhilipsPollResult(DatagramSocket socket, InetSocketAddress addr, int invokeId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) RORS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) invokeId);
        buf.putShort((short) CMD_CONFIRMED_ACTION);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putShort((short)(NOM_ACT_POLL_MDIB_DATA_EXT & 0xFFFF));
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        writePhPollHeader(buf);

        buf.putShort((short) 1);
        int pollInfoLenPos = buf.position();
        buf.putShort((short) 0);
        int pollInfoStart = buf.position();
        buf.putShort((short) 0);

        int[][] numerics = getPhNumerics();
        buf.putShort((short) numerics.length);
        int obsListLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (int[] n : numerics) {
            writePhNumericObs(buf, n);
        }

        int obsEnd = buf.position();
        buf.putShort(obsListLenPos, (short)(obsEnd - obsStart));
        buf.putShort(pollInfoLenPos, (short)(obsEnd - pollInfoStart));
        fixPhLengths(buf, lenPos, roLenPos, cmdLenPos, actionLenPos);

        buf.flip();
        sendUdp(socket, addr, buf);
    }

    // --- Philips Get Result ---

    private void sendPhilipsGetResult(DatagramSocket socket, InetSocketAddress addr, int invokeId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) DATA_EXPORT_SPDU);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) RORS_APDU);
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) invokeId);
        buf.putShort((short) CMD_GET);
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) NOM_MOC_VMS_MDS);
        buf.putShort((short) 0); buf.putShort((short) 0);

        buf.putShort((short) 3);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_SYS_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_OBJ);
        buf.putShort((short) NOM_MOC_VMS_MDS);

        writePhSystemModel(buf);

        buf.putShort((short) NOM_ATTR_TIME_ABS);
        buf.putShort((short) 8);
        writePhAbsoluteTime(buf);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));

        int end = buf.position();
        buf.putShort(lenPos, (short)(end - lenPos - 2));
        buf.putShort(roLenPos, (short)(end - roLenPos - 2));
        buf.putShort(cmdLenPos, (short)(end - cmdLenPos - 2));

        buf.flip();
        sendUdp(socket, addr, buf);
    }

    private void sendPhilipsEmptyResult(DatagramSocket socket, InetSocketAddress addr, int invokeId, int cmdType) throws IOException {
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
        sendUdp(socket, addr, buf);
    }

    // --- Philips Numerics ---

    private int[][] getPhNumerics() {
        int hr = slHeartRate.getValue();
        int spo2 = slSpo2.getValue();
        int rr = slPhRespRate.getValue();
        int abpS = slAbpSys.getValue();
        int abpD = slAbpDia.getValue();
        int abpM = (abpS + 2 * abpD) / 3;
        int nbpS = slNbpSys.getValue();
        int nbpD = slNbpDia.getValue();
        int nbpM = (nbpS + 2 * nbpD) / 3;
        int etco2 = slEtCo2.getValue();
        int temp = slTemp.getValue();
        int cvp = slCvp.getValue();
        int papS = slPapSys.getValue();
        int papD = slPapDia.getValue();
        int papM = (papS + 2 * papD) / 3;

        return new int[][] {
            {NOM_ECG_CARD_BEAT_RATE,     hr,    NOM_DIM_BEAT_PER_MIN, 0x01, 0},
            {NOM_PULS_OXIM_SAT_O2,       spo2,  NOM_DIM_PERCENT,      0x02, 0},
            {NOM_PULS_OXIM_PULS_RATE,    hr+1,  NOM_DIM_BEAT_PER_MIN, 0x03, 0},
            {NOM_RESP_RATE,              rr,    NOM_DIM_RESP_PER_MIN, 0x04, 0},
            {NOM_PRESS_BLD_ART_ABP_SYS,  abpS,  NOM_DIM_MMHG,         0x10, 0},
            {NOM_PRESS_BLD_ART_ABP_DIA,  abpD,  NOM_DIM_MMHG,         0x11, 0},
            {NOM_PRESS_BLD_ART_ABP_MEAN, abpM,  NOM_DIM_MMHG,         0x12, 0},
            {NOM_PRESS_BLD_NONINV_SYS,   nbpS,  NOM_DIM_MMHG,         0x20, 0},
            {NOM_PRESS_BLD_NONINV_DIA,   nbpD,  NOM_DIM_MMHG,         0x21, 0},
            {NOM_PRESS_BLD_NONINV_MEAN,  nbpM,  NOM_DIM_MMHG,         0x22, 0},
            {NOM_CO2_ET,                 etco2, NOM_DIM_MMHG,         0x30, 0},
            {NOM_TEMP_BLD,               temp,  NOM_DIM_DEGC,         0x40, 1},
            {NOM_PRESS_BLD_VEN_CENT,     cvp,   NOM_DIM_MMHG,         0x41, 0},
            {NOM_PRESS_BLD_ART_PULM_SYS, papS,  NOM_DIM_MMHG,         0x42, 0},
            {NOM_PRESS_BLD_ART_PULM_DIA, papD,  NOM_DIM_MMHG,         0x43, 0},
            {NOM_PRESS_BLD_ART_PULM_MEAN,papM,  NOM_DIM_MMHG,         0x44, 0},
        };
    }

    private void writePhNumericObs(ByteBuffer buf, int[] n) {
        int physioId = n[0], value = n[1], unitCode = n[2], handle = n[3], isTemp = n[4];

        buf.putShort((short) handle);

        buf.putShort((short) 4);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_NU_VAL_OBS);
        buf.putShort((short) 12);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);
        buf.putShort((short) 0x0000);
        buf.putShort((short) NOM_PART_DIM);
        buf.putShort((short) unitCode);
        buf.putInt(encodePhFloat(value, isTemp != 0));

        buf.putShort((short) NOM_ATTR_ID_LABEL);
        buf.putShort((short) 4);
        buf.putInt(physioId);

        String label = getPhLabel(physioId);
        byte[] labelBytes = label.getBytes();
        int labelPadded = (labelBytes.length + 1) & ~1;
        buf.putShort((short) NOM_ATTR_ID_LABEL_STRING);
        buf.putShort((short)(2 + labelPadded));
        buf.putShort((short) labelBytes.length);
        buf.put(labelBytes);
        if (labelBytes.length % 2 != 0) buf.put((byte) 0);

        buf.putShort((short) NOM_ATTR_METRIC_SPECN);
        buf.putShort((short) 8);
        buf.putInt(8000);
        buf.putShort((short) METRIC_CAT_MEAS);
        buf.putShort((short)(METRIC_ACCESS_AVAIL | METRIC_ACCESS_RD_ONLY));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
    }

    private String getPhLabel(int physioId) {
        switch (physioId) {
            case NOM_ECG_CARD_BEAT_RATE: return "HR";
            case NOM_PULS_OXIM_SAT_O2: return "SpO2";
            case NOM_PULS_OXIM_PULS_RATE: return "Pulse";
            case NOM_RESP_RATE: return "RR";
            case NOM_PRESS_BLD_ART_ABP_SYS: return "ABP Sys";
            case NOM_PRESS_BLD_ART_ABP_DIA: return "ABP Dia";
            case NOM_PRESS_BLD_ART_ABP_MEAN: return "ABP Mean";
            case NOM_PRESS_BLD_NONINV_SYS: return "NBP Sys";
            case NOM_PRESS_BLD_NONINV_DIA: return "NBP Dia";
            case NOM_PRESS_BLD_NONINV_MEAN: return "NBP Mean";
            case NOM_CO2_ET: return "etCO2";
            case NOM_TEMP_BLD: return "Temp";
            case NOM_PRESS_BLD_VEN_CENT: return "CVP";
            case NOM_PRESS_BLD_ART_PULM_SYS: return "PAP Sys";
            case NOM_PRESS_BLD_ART_PULM_DIA: return "PAP Dia";
            case NOM_PRESS_BLD_ART_PULM_MEAN: return "PAP Mean";
            default: return "Unknown";
        }
    }

    // --- Philips Waveform Observations ---

    private void writePhWaveformObs(ByteBuffer buf, int handle, int physioId,
                                     short[] samples, int sampleRate, int unitCode) {
        buf.putShort((short) handle);

        buf.putShort((short) 4);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        int sampleDataLen = samples.length * 2;
        buf.putShort((short) NOM_ATTR_SA_VAL_OBS);
        buf.putShort((short)(8 + sampleDataLen));
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);
        buf.putShort((short) 0x0000);
        buf.putShort((short) sampleDataLen);
        for (short s : samples) buf.putShort(s);

        buf.putShort((short) NOM_ATTR_SA_SPECN);
        buf.putShort((short) 12);
        buf.putShort((short) samples.length);
        buf.putShort((short) 16);
        buf.putInt(sampleRate);
        buf.putShort((short) NOM_PART_DIM);
        buf.putShort((short) unitCode);

        buf.putShort((short) NOM_ATTR_SA_FIXED_VAL_SPECN);
        buf.putShort((short) 12);
        buf.putShort((short) 1); buf.putShort((short) 8);
        buf.putShort((short) 0); buf.putShort((short) 4095);
        buf.putShort((short) physioId); buf.putShort((short) 0);

        buf.putShort((short) NOM_ATTR_ID_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) physioId);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
    }

    // --- Philips Alarm Monitor ---

    private void writePhAlarmMonitorObs(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        int hr = slHeartRate.getValue();
        int spo2 = slSpo2.getValue();
        int abpSys = slAbpSys.getValue();

        boolean hrHigh = hr > 120 || chkPhHrAlarm.isSelected();
        boolean hrLow  = hr < 50;
        boolean spo2Lo = spo2 < 90 || chkPhSpo2Low.isSelected();
        boolean abpHi  = abpSys > 160 || chkPhAbpHigh.isSelected();

        int physAlarmCount = 0;
        if (hrHigh) physAlarmCount++;
        if (hrLow) physAlarmCount++;
        if (spo2Lo) physAlarmCount++;
        if (abpHi) physAlarmCount++;

        buf.putShort((short) 3);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_DEV_AL_COND);
        buf.putShort((short) 2);
        int alertState = physAlarmCount > 0 ? 0x0002 : 0;
        buf.putShort((short) alertState);

        buf.putShort((short) NOM_ATTR_AL_MON_T_AL_LIST);
        int tAlLenPos = buf.position();
        buf.putShort((short) 0);
        int tAlStart = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        int tAlEnd = buf.position();
        buf.putShort(tAlLenPos, (short)(tAlEnd - tAlStart));

        buf.putShort((short) NOM_ATTR_AL_MON_P_AL_LIST);
        int pAlLenPos = buf.position();
        buf.putShort((short) 0);
        int pAlStart = buf.position();
        buf.putShort((short) physAlarmCount);
        buf.putShort((short)(physAlarmCount * 10));

        if (hrHigh) writePhAlarmEntry(buf, NOM_EVT_HI_HR, 3, NOM_ECG_CARD_BEAT_RATE);
        if (hrLow)  writePhAlarmEntry(buf, NOM_EVT_LO_HR, 3, NOM_ECG_CARD_BEAT_RATE);
        if (spo2Lo) writePhAlarmEntry(buf, NOM_EVT_LO_SAT_O2, 3, NOM_PULS_OXIM_SAT_O2);
        if (abpHi)  writePhAlarmEntry(buf, NOM_EVT_HI_ABP_SYS, 2, NOM_PRESS_BLD_ART_ABP_SYS);

        int pAlEnd = buf.position();
        buf.putShort(pAlLenPos, (short)(pAlEnd - pAlStart));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
    }

    private void writePhAlarmEntry(ByteBuffer buf, int alarmCode, int priority, int sourceId) {
        buf.putShort((short) alarmCode);
        buf.putShort((short) priority);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) sourceId);
        buf.putShort((short) 0x0001);
    }

    // --- Philips Patient Demographics ---

    private void writePhPatientDemogObs(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        String fullName = tfPatientName.getText().trim();
        String[] parts = fullName.split("\\s+", 2);
        String givenName = parts[0];
        String familyName = parts.length > 1 ? parts[1] : "";
        String patId = tfPatientId.getText().trim();

        int dobYear = 1980, dobMonth = 1, dobDay = 15;
        try {
            String dob = tfPatientDob.getText().trim();
            String[] dp = dob.split("-");
            if (dp.length == 3) {
                dobYear = Integer.parseInt(dp[0]);
                dobMonth = Integer.parseInt(dp[1]);
                dobDay = Integer.parseInt(dp[2]);
            }
        } catch (Exception ignored) {}

        int sex = SEX_UNKNOWN;
        String sexStr = (String) cbPatientSex.getSelectedItem();
        if ("M".equals(sexStr)) sex = SEX_MALE;
        else if ("F".equals(sexStr)) sex = SEX_FEMALE;

        double height = 175;
        double weight = 75;
        try { height = Double.parseDouble(tfPatientHeight.getText().trim()); } catch (Exception ignored) {}
        try { weight = Double.parseDouble(tfPatientWeight.getText().trim()); } catch (Exception ignored) {}
        double bsa = 0.007184 * Math.pow(height, 0.725) * Math.pow(weight, 0.425);

        buf.putShort((short) 8);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        writePhStringAttr(buf, NOM_ATTR_PT_NAME_GIVEN, givenName);
        writePhStringAttr(buf, NOM_ATTR_PT_NAME_FAMILY, familyName);
        writePhStringAttr(buf, NOM_ATTR_PT_ID, patId);

        buf.putShort((short) NOM_ATTR_PT_DOB);
        buf.putShort((short) 8);
        writePhBcdTime(buf, dobYear, dobMonth, dobDay, 0, 0, 0, 0);

        buf.putShort((short) NOM_ATTR_PT_SEX);
        buf.putShort((short) 2);
        buf.putShort((short) sex);

        buf.putShort((short) NOM_ATTR_PT_HEIGHT);
        buf.putShort((short) 4);
        buf.putInt(encodePhFloat((int)height, false));

        buf.putShort((short) NOM_ATTR_PT_WEIGHT);
        buf.putShort((short) 4);
        buf.putInt(encodePhFloat((int)weight, false));

        buf.putShort((short) NOM_ATTR_PT_BSA);
        buf.putShort((short) 4);
        buf.putInt(encodePhFloatDouble(bsa));

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
    }

    // --- Philips Enumeration ---

    private void writePhEnumObs(ByteBuffer buf, int handle) {
        buf.putShort((short) handle);

        buf.putShort((short) 2);
        int attrListLenPos = buf.position();
        buf.putShort((short) 0);
        int attrStart = buf.position();

        buf.putShort((short) NOM_ATTR_ENUM_OBS_VAL);
        buf.putShort((short) 8);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) 0x5020);
        buf.putShort((short) 0x0000);
        buf.putShort((short) NOM_VENT_MODE_SIMV);

        buf.putShort((short) NOM_ATTR_ID_TYPE);
        buf.putShort((short) 4);
        buf.putShort((short) NOM_PART_SCADA);
        buf.putShort((short) 0x5020);

        int attrEnd = buf.position();
        buf.putShort(attrListLenPos, (short)(attrEnd - attrStart));
    }

    // --- Philips Waveform Generation ---

    private short[] generatePhEcgSamples() {
        int numSamples = 500;
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / Math.max(slHeartRate.getValue(), 30);
        double samplePeriod = 1.0 / 500.0;

        for (int i = 0; i < numSamples; i++) {
            double t = phEcgPhase + i * samplePeriod;
            double beatFrac = (t % beatInterval) / beatInterval;
            double val = 0;

            if (beatFrac >= 0.10 && beatFrac < 0.20) {
                val = 0.15 * Math.sin(Math.PI * (beatFrac - 0.10) / 0.10);
            } else if (beatFrac >= 0.22 && beatFrac < 0.25) {
                val = -0.1 * Math.sin(Math.PI * (beatFrac - 0.22) / 0.03);
            } else if (beatFrac >= 0.25 && beatFrac < 0.30) {
                val = 1.0 * Math.sin(Math.PI * (beatFrac - 0.25) / 0.05);
            } else if (beatFrac >= 0.30 && beatFrac < 0.33) {
                val = -0.2 * Math.sin(Math.PI * (beatFrac - 0.30) / 0.03);
            } else if (beatFrac >= 0.40 && beatFrac < 0.55) {
                val = 0.3 * Math.sin(Math.PI * (beatFrac - 0.40) / 0.15);
            }
            val += phRng.nextGaussian() * 0.01;
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int)(val * 2000)));
        }
        phEcgPhase += numSamples / 500.0;
        return samples;
    }

    private short[] generatePhPlethSamples() {
        int numSamples = 125;
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / Math.max(slHeartRate.getValue(), 30);
        double samplePeriod = 1.0 / 125.0;

        for (int i = 0; i < numSamples; i++) {
            double t = phPlethPhase + i * samplePeriod;
            double beatFrac = (t % beatInterval) / beatInterval;
            double val;
            if (beatFrac < 0.15) val = beatFrac / 0.15;
            else if (beatFrac < 0.25) val = 1.0 - 0.3 * ((beatFrac - 0.15) / 0.10);
            else if (beatFrac < 0.35) val = 0.7 - 0.15 * Math.sin(Math.PI * (beatFrac - 0.25) / 0.10);
            else val = 0.55 * (1.0 - (beatFrac - 0.35) / 0.65);
            val += phRng.nextGaussian() * 0.005;
            samples[i] = (short) Math.max(0, Math.min(4095, (int)(val * 3000)));
        }
        phPlethPhase += numSamples / 125.0;
        return samples;
    }

    private short[] generatePhAbpSamples() {
        int numSamples = 125;
        short[] samples = new short[numSamples];
        double beatInterval = 60.0 / Math.max(slHeartRate.getValue(), 30);
        double samplePeriod = 1.0 / 125.0;
        double abpS = slAbpSys.getValue();
        double abpD = slAbpDia.getValue();
        double abpM = (abpS + 2 * abpD) / 3.0;

        for (int i = 0; i < numSamples; i++) {
            double t = phAbpWavePhase + i * samplePeriod;
            double beatFrac = (t % beatInterval) / beatInterval;
            double val;
            if (beatFrac < 0.10) val = abpD + (abpS - abpD) * (beatFrac / 0.10);
            else if (beatFrac < 0.20) { double d = (beatFrac - 0.10) / 0.10; val = abpS - (abpS - abpM) * 0.4 * d; }
            else if (beatFrac < 0.30) {
                double d = (beatFrac - 0.20) / 0.10;
                double base = abpS - (abpS - abpM) * 0.4;
                val = base - 5 + 10 * Math.sin(Math.PI * d);
            } else {
                double d = (beatFrac - 0.30) / 0.70;
                double notchVal = abpS - (abpS - abpM) * 0.4 + 5;
                val = notchVal - (notchVal - abpD) * d;
            }
            val += phRng.nextGaussian() * 0.5;
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int)(val * 100)));
        }
        phAbpWavePhase += numSamples / 125.0;
        return samples;
    }

    private short[] generatePhRespSamples() {
        int numSamples = 62;
        short[] samples = new short[numSamples];
        double breathInterval = 60.0 / Math.max(slPhRespRate.getValue(), 4);
        double samplePeriod = 1.0 / 62.0;

        for (int i = 0; i < numSamples; i++) {
            double t = phRespWavePhase + i * samplePeriod;
            double phase = (t % breathInterval) / breathInterval;
            double val;
            if (phase < 0.45) val = Math.sin(Math.PI * phase / 0.45 * 0.5);
            else val = Math.cos(Math.PI * (phase - 0.45) / 0.55 * 0.5);
            val += phRng.nextGaussian() * 0.02;
            samples[i] = (short) Math.max(-32768, Math.min(32767, (int)(val * 2000)));
        }
        phRespWavePhase += numSamples / 62.0;
        return samples;
    }

    // --- Philips Helpers ---

    private void writePhPollHeader(ByteBuffer buf) {
        buf.putShort((short) phPollNumber);
        buf.putInt((int)((System.currentTimeMillis() & 0xFFFFFFFFL) * 8));
        writePhAbsoluteTime(buf);
    }

    private void writePhAbsoluteTime(ByteBuffer buf) {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        buf.put(toBcd(year / 100));
        buf.put(toBcd(year % 100));
        buf.put(toBcd(cal.get(Calendar.MONTH) + 1));
        buf.put(toBcd(cal.get(Calendar.DAY_OF_MONTH)));
        buf.put(toBcd(cal.get(Calendar.HOUR_OF_DAY)));
        buf.put(toBcd(cal.get(Calendar.MINUTE)));
        buf.put(toBcd(cal.get(Calendar.SECOND)));
        buf.put(toBcd(cal.get(Calendar.MILLISECOND) / 10));
    }

    private void writePhBcdTime(ByteBuffer buf, int year, int month, int day,
                                 int hour, int minute, int second, int hundredths) {
        buf.put(toBcd(year / 100));
        buf.put(toBcd(year % 100));
        buf.put(toBcd(month));
        buf.put(toBcd(day));
        buf.put(toBcd(hour));
        buf.put(toBcd(minute));
        buf.put(toBcd(second));
        buf.put(toBcd(hundredths));
    }

    private byte toBcd(int value) {
        value = Math.max(0, Math.min(99, value));
        return (byte)(((value / 10) << 4) | (value % 10));
    }

    private void writePhSystemModel(ByteBuffer buf) {
        byte[] mfr = "Philips".getBytes();
        byte[] mdl = "IntelliVue MX800".getBytes();
        int mfrPadded = (mfr.length + 1) & ~1;
        int mdlPadded = (mdl.length + 1) & ~1;
        int totalLen = 2 + mfrPadded + 2 + mdlPadded;

        buf.putShort((short) NOM_ATTR_ID_MODEL);
        buf.putShort((short) totalLen);
        buf.putShort((short) mfr.length);
        buf.put(mfr);
        if (mfr.length % 2 != 0) buf.put((byte) 0);
        buf.putShort((short) mdl.length);
        buf.put(mdl);
        if (mdl.length % 2 != 0) buf.put((byte) 0);
    }

    private void writePhStringAttr(ByteBuffer buf, int attrId, String value) {
        byte[] strBytes = value.getBytes();
        int padded = (strBytes.length + 1) & ~1;
        buf.putShort((short) attrId);
        buf.putShort((short)(2 + padded));
        buf.putShort((short) strBytes.length);
        buf.put(strBytes);
        if (strBytes.length % 2 != 0) buf.put((byte) 0);
    }

    private int encodePhFloat(int value, boolean isTempX10) {
        if (isTempX10) {
            return (0xFF << 24) | (value & 0x00FFFFFF);
        }
        return (value & 0x00FFFFFF);
    }

    private int encodePhFloatDouble(double value) {
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

    private void fixPhLengths(ByteBuffer buf, int lenPos, int roLenPos, int cmdLenPos, int actionLenPos) {
        int end = buf.position();
        buf.putShort(actionLenPos, (short)(end - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short)(end - cmdLenPos - 2));
        buf.putShort(roLenPos, (short)(end - roLenPos - 2));
        buf.putShort(lenPos, (short)(end - lenPos - 2));
    }

    private void sendUdp(DatagramSocket socket, InetSocketAddress addr, ByteBuffer buf) throws IOException {
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        socket.send(new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort()));
    }

    // =====================================================================
    // Shared Helpers
    // =====================================================================

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

    private void writeAsciiHexChecksum(OutputStream out, int sum) throws IOException {
        int lsb = sum & 0xFF;
        out.write(asciiHexHi(lsb));
        out.write(asciiHexLo(lsb));
    }

    private static String hexByte(int v) {
        return String.format("%02X", v & 0xFF);
    }

    private static String padTo(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String draegerCmdName(int cmd) {
        switch (cmd) {
            case CMD_ICC: return "ICC";
            case CMD_NOP: return "NOP";
            case CMD_STOP: return "STOP";
            case CMD_REQ_DEVICE_ID: return "ReqDeviceId";
            case CMD_REQ_DATETIME: return "ReqDateTime";
            case CMD_REQ_DATA_CP1: return "ReqDataCP1";
            case CMD_REQ_DATA_CP2: return "ReqDataCP2";
            case CMD_REQ_LOW_ALRM_CP1: return "ReqLowAlarmLimitsCP1";
            case CMD_REQ_HI_ALRM_CP1: return "ReqHighAlarmLimitsCP1";
            case CMD_REQ_ALARMS_CP1: return "ReqAlarmsCP1";
            case CMD_REQ_ALARMS_CP2: return "ReqAlarmsCP2";
            case CMD_REQ_SETTINGS: return "ReqDeviceSettings";
            case CMD_REQ_TEXT_MSG: return "ReqTextMessages";
            case CMD_CONFIGURE_RESP: return "ConfigureDataResponse";
            case CMD_REQ_RT_CONFIG: return "ReqRealtimeConfig";
            case CMD_CONFIG_RT: return "ConfigureRealtime";
            default: return "Cmd(0x" + hexByte(cmd) + ")";
        }
    }

    // =====================================================================
    // Main
    // =====================================================================

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SimulatorMonitor monitor = new SimulatorMonitor();
            monitor.setVisible(true);
        });
    }
}
