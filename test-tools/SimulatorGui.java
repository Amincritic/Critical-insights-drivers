import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Timer;
import java.util.concurrent.atomic.*;

/**
 * GUI for the Draeger MEDIBUS and Philips IntelliVue simulators.
 *
 * Provides real-time vital sign controls, alarm triggers, and protocol logs.
 * No external dependencies — uses Java Swing only.
 *
 * Usage:
 *   javac test-tools/SimulatorGui.java
 *   java -cp test-tools SimulatorGui
 */
public class SimulatorGui extends JFrame {

    // --- Shared state ---
    private final AtomicBoolean draegerRunning = new AtomicBoolean(false);
    private final AtomicBoolean philipsRunning = new AtomicBoolean(false);
    private Thread draegerThread;
    private Thread philipsThread;

    // --- Draeger vitals ---
    private final JSlider slTidalVol = makeSlider(200, 800, 450);
    private final JSlider slRespRate = makeSlider(5, 40, 16);
    private final JSlider slPeakPres = makeSlider(5, 50, 22);
    private final JSlider slPeep = makeSlider(0, 20, 5);
    private final JSlider slFio2 = makeSlider(21, 100, 40);
    private final JSlider slCompliance = makeSlider(10, 100, 45);
    private final JSlider slResistance = makeSlider(3, 40, 12);
    private final JSlider slAirwayTemp = makeSlider(28, 40, 34);

    // --- Philips vitals ---
    private final JSlider slHeartRate = makeSlider(30, 200, 72);
    private final JSlider slSpo2 = makeSlider(70, 100, 97);
    private final JSlider slAbpSys = makeSlider(60, 220, 120);
    private final JSlider slAbpDia = makeSlider(30, 130, 80);
    private final JSlider slEtCo2 = makeSlider(15, 65, 38);
    private final JSlider slTemp = makeSlider(340, 410, 370); // x10
    private final JSlider slPhRespRate = makeSlider(5, 40, 16);
    private final JSlider slNbpSys = makeSlider(60, 220, 118);
    private final JSlider slNbpDia = makeSlider(30, 130, 76);

    // --- Alarm toggles ---
    private final JCheckBox chkPawHigh = new JCheckBox("PAW HIGH");
    private final JCheckBox chkO2High = new JCheckBox("% O2 HIGH");
    private final JCheckBox chkMinVolLow = new JCheckBox("MIN VOL LOW");
    private final JCheckBox chkApnea = new JCheckBox("APNEA");
    private final JCheckBox chkPhHrAlarm = new JCheckBox("HR Alarm");
    private final JCheckBox chkPhSpo2Low = new JCheckBox("SpO2 Low");
    private final JCheckBox chkPhAbpHigh = new JCheckBox("ABP High");

    // --- Display labels ---
    private final Map<String, JLabel> draegerDisplays = new LinkedHashMap<>();
    private final Map<String, JLabel> philipsDisplays = new LinkedHashMap<>();

    // --- Log ---
    private final JTextArea logArea = new JTextArea();
    private final JTextField draegerPortField = new JTextField("9100", 5);
    private final JTextField philipsPortField = new JTextField("24105", 5);
    private final JComboBox<String> modelCombo = new JComboBox<>(
            new String[]{"evita", "evita2", "evita4", "v500", "savina", "fabius"});

    // --- Status ---
    private final JLabel draegerStatus = new JLabel("Stopped");
    private final JLabel philipsStatus = new JLabel("Stopped");
    private final JLabel draegerConnStatus = new JLabel("No connection");
    private final JLabel philipsConnStatus = new JLabel("No connection");

    // --- Buttons ---
    private final JButton btnDraegerStart = new JButton("Start");
    private final JButton btnDraegerStop = new JButton("Stop");
    private final JButton btnPhilipsStart = new JButton("Start");
    private final JButton btnPhilipsStop = new JButton("Stop");

    // --- Update timer ---
    private final Timer displayTimer = new Timer(true);

    public SimulatorGui() {
        super("OpenICE Device Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(5, 5));

        // Top panel: connection controls
        add(buildControlPanel(), BorderLayout.NORTH);

        // Center: tabbed pane with Draeger and Philips panels
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Draeger MEDIBUS", buildDraegerPanel());
        tabs.addTab("Philips IntelliVue", buildPhilipsPanel());
        tabs.addTab("Protocol Log", buildLogPanel());
        add(tabs, BorderLayout.CENTER);

        // Bottom: status bar
        add(buildStatusBar(), BorderLayout.SOUTH);

        // Button actions
        btnDraegerStart.addActionListener(e -> startDraeger());
        btnDraegerStop.addActionListener(e -> stopDraeger());
        btnPhilipsStart.addActionListener(e -> startPhilips());
        btnPhilipsStop.addActionListener(e -> stopPhilips());
        btnDraegerStop.setEnabled(false);
        btnPhilipsStop.setEnabled(false);

        // Periodic display update
        displayTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                SwingUtilities.invokeLater(() -> updateDisplays());
            }
        }, 100, 200);

        pack();
        setMinimumSize(new Dimension(900, 700));
        setLocationRelativeTo(null);
    }

    // --- Panel builders ---

    private JPanel buildControlPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Simulator Controls"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        // Draeger row
        c.gridy = 0;
        c.gridx = 0; p.add(new JLabel("Draeger TCP Port:"), c);
        c.gridx = 1; p.add(draegerPortField, c);
        c.gridx = 2; p.add(new JLabel("Model:"), c);
        c.gridx = 3; p.add(modelCombo, c);
        c.gridx = 4; p.add(btnDraegerStart, c);
        c.gridx = 5; p.add(btnDraegerStop, c);
        c.gridx = 6; p.add(draegerStatus, c);

        // Philips row
        c.gridy = 1;
        c.gridx = 0; p.add(new JLabel("Philips UDP Port:"), c);
        c.gridx = 1; p.add(philipsPortField, c);
        c.gridx = 2; c.gridwidth = 2; p.add(new JLabel(""), c); c.gridwidth = 1;
        c.gridx = 4; p.add(btnPhilipsStart, c);
        c.gridx = 5; p.add(btnPhilipsStop, c);
        c.gridx = 6; p.add(philipsStatus, c);

        return p;
    }

    private JPanel buildDraegerPanel() {
        JPanel main = new JPanel(new BorderLayout(5, 5));

        // Left: vital sign displays
        JPanel displays = new JPanel(new GridLayout(0, 2, 8, 4));
        displays.setBorder(BorderFactory.createTitledBorder("Live Values"));
        addDisplay(displays, draegerDisplays, "Tidal Volume", "450 mL");
        addDisplay(displays, draegerDisplays, "Resp Rate", "16 /min");
        addDisplay(displays, draegerDisplays, "Peak Pressure", "22 mbar");
        addDisplay(displays, draegerDisplays, "PEEP", "5 mbar");
        addDisplay(displays, draegerDisplays, "FiO2", "40 %");
        addDisplay(displays, draegerDisplays, "Compliance", "45 L/bar");
        addDisplay(displays, draegerDisplays, "Resistance", "12 mbar/L/s");
        addDisplay(displays, draegerDisplays, "Airway Temp", "34 °C");
        addDisplay(displays, draegerDisplays, "Minute Volume", "7.2 L/min");

        // Right: sliders
        JPanel sliders = new JPanel(new GridLayout(0, 1, 2, 2));
        sliders.setBorder(BorderFactory.createTitledBorder("Adjust Values"));
        addSliderRow(sliders, "Tidal Vol (mL)", slTidalVol);
        addSliderRow(sliders, "Resp Rate (/min)", slRespRate);
        addSliderRow(sliders, "Peak Pres (mbar)", slPeakPres);
        addSliderRow(sliders, "PEEP (mbar)", slPeep);
        addSliderRow(sliders, "FiO2 (%)", slFio2);
        addSliderRow(sliders, "Compliance", slCompliance);
        addSliderRow(sliders, "Resistance", slResistance);
        addSliderRow(sliders, "Airway Temp (°C)", slAirwayTemp);

        // Bottom: alarms
        JPanel alarms = new JPanel(new FlowLayout(FlowLayout.LEFT));
        alarms.setBorder(BorderFactory.createTitledBorder("Trigger Alarms"));
        alarms.add(chkPawHigh);
        alarms.add(chkO2High);
        alarms.add(chkMinVolLow);
        alarms.add(chkApnea);

        JPanel center = new JPanel(new GridLayout(1, 2, 5, 5));
        center.add(displays);
        center.add(sliders);

        main.add(center, BorderLayout.CENTER);
        main.add(alarms, BorderLayout.SOUTH);
        return main;
    }

    private JPanel buildPhilipsPanel() {
        JPanel main = new JPanel(new BorderLayout(5, 5));

        JPanel displays = new JPanel(new GridLayout(0, 2, 8, 4));
        displays.setBorder(BorderFactory.createTitledBorder("Live Values"));
        addDisplay(displays, philipsDisplays, "Heart Rate", "72 bpm");
        addDisplay(displays, philipsDisplays, "SpO2", "97 %");
        addDisplay(displays, philipsDisplays, "Resp Rate", "16 /min");
        addDisplay(displays, philipsDisplays, "ABP Sys", "120 mmHg");
        addDisplay(displays, philipsDisplays, "ABP Dia", "80 mmHg");
        addDisplay(displays, philipsDisplays, "ABP Mean", "93 mmHg");
        addDisplay(displays, philipsDisplays, "NBP Sys", "118 mmHg");
        addDisplay(displays, philipsDisplays, "NBP Dia", "76 mmHg");
        addDisplay(displays, philipsDisplays, "etCO2", "38 mmHg");
        addDisplay(displays, philipsDisplays, "Temperature", "37.0 °C");

        JPanel sliders = new JPanel(new GridLayout(0, 1, 2, 2));
        sliders.setBorder(BorderFactory.createTitledBorder("Adjust Values"));
        addSliderRow(sliders, "Heart Rate (bpm)", slHeartRate);
        addSliderRow(sliders, "SpO2 (%)", slSpo2);
        addSliderRow(sliders, "Resp Rate (/min)", slPhRespRate);
        addSliderRow(sliders, "ABP Sys (mmHg)", slAbpSys);
        addSliderRow(sliders, "ABP Dia (mmHg)", slAbpDia);
        addSliderRow(sliders, "NBP Sys (mmHg)", slNbpSys);
        addSliderRow(sliders, "NBP Dia (mmHg)", slNbpDia);
        addSliderRow(sliders, "etCO2 (mmHg)", slEtCo2);
        addSliderRow(sliders, "Temp (°C x10)", slTemp);

        JPanel alarms = new JPanel(new FlowLayout(FlowLayout.LEFT));
        alarms.setBorder(BorderFactory.createTitledBorder("Trigger Alarms"));
        alarms.add(chkPhHrAlarm);
        alarms.add(chkPhSpo2Low);
        alarms.add(chkPhAbpHigh);

        JPanel center = new JPanel(new GridLayout(1, 2, 5, 5));
        center.add(displays);
        center.add(sliders);

        main.add(center, BorderLayout.CENTER);
        main.add(alarms, BorderLayout.SOUTH);
        return main;
    }

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(logArea);
        p.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("Clear Log");
        clear.addActionListener(e -> logArea.setText(""));
        buttons.add(clear);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new GridLayout(1, 2));
        p.setBorder(BorderFactory.createEtchedBorder());

        JPanel dr = new JPanel(new FlowLayout(FlowLayout.LEFT));
        dr.add(new JLabel("Draeger:"));
        dr.add(draegerConnStatus);
        p.add(dr);

        JPanel ph = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ph.add(new JLabel("Philips:"));
        ph.add(philipsConnStatus);
        p.add(ph);

        return p;
    }

    // --- Display helpers ---

    private void addDisplay(JPanel panel, Map<String, JLabel> map, String name, String initial) {
        JLabel label = new JLabel(name + ":");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        JLabel value = new JLabel(initial);
        value.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        value.setForeground(new Color(0, 180, 0));
        map.put(name, value);
        panel.add(label);
        panel.add(value);
    }

    private void addSliderRow(JPanel panel, String label, JSlider slider) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        JLabel lbl = new JLabel(label);
        lbl.setPreferredSize(new Dimension(150, 20));
        JLabel val = new JLabel(String.valueOf(slider.getValue()));
        val.setPreferredSize(new Dimension(40, 20));
        slider.addChangeListener(e -> val.setText(String.valueOf(slider.getValue())));
        row.add(lbl, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(val, BorderLayout.EAST);
        panel.add(row);
    }

    private JSlider makeSlider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setPaintTicks(false);
        return s;
    }

    private void updateDisplays() {
        // Draeger
        double mv = slTidalVol.getValue() * slRespRate.getValue() / 1000.0;
        setDisplay(draegerDisplays, "Tidal Volume", slTidalVol.getValue() + " mL");
        setDisplay(draegerDisplays, "Resp Rate", slRespRate.getValue() + " /min");
        setDisplay(draegerDisplays, "Peak Pressure", slPeakPres.getValue() + " mbar");
        setDisplay(draegerDisplays, "PEEP", slPeep.getValue() + " mbar");
        setDisplay(draegerDisplays, "FiO2", slFio2.getValue() + " %");
        setDisplay(draegerDisplays, "Compliance", slCompliance.getValue() + " L/bar");
        setDisplay(draegerDisplays, "Resistance", slResistance.getValue() + " mbar/L/s");
        setDisplay(draegerDisplays, "Airway Temp", slAirwayTemp.getValue() + " °C");
        setDisplay(draegerDisplays, "Minute Volume", String.format("%.1f L/min", mv));

        // Philips
        int abpMean = (slAbpSys.getValue() + 2 * slAbpDia.getValue()) / 3;
        setDisplay(philipsDisplays, "Heart Rate", slHeartRate.getValue() + " bpm");
        setDisplay(philipsDisplays, "SpO2", slSpo2.getValue() + " %");
        setDisplay(philipsDisplays, "Resp Rate", slPhRespRate.getValue() + " /min");
        setDisplay(philipsDisplays, "ABP Sys", slAbpSys.getValue() + " mmHg");
        setDisplay(philipsDisplays, "ABP Dia", slAbpDia.getValue() + " mmHg");
        setDisplay(philipsDisplays, "ABP Mean", abpMean + " mmHg");
        setDisplay(philipsDisplays, "NBP Sys", slNbpSys.getValue() + " mmHg");
        setDisplay(philipsDisplays, "NBP Dia", slNbpDia.getValue() + " mmHg");
        setDisplay(philipsDisplays, "etCO2", slEtCo2.getValue() + " mmHg");
        setDisplay(philipsDisplays, "Temperature", String.format("%.1f °C", slTemp.getValue() / 10.0));
    }

    private void setDisplay(Map<String, JLabel> displays, String name, String value) {
        JLabel lbl = displays.get(name);
        if (lbl != null) lbl.setText(value);
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            // Auto-scroll and limit lines
            if (logArea.getDocument().getLength() > 50000) {
                try {
                    logArea.getDocument().remove(0, 10000);
                } catch (BadLocationException ignored) {}
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // --- Draeger simulator ---

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
        draegerStatus.setForeground(new Color(0, 150, 0));

        String model = (String) modelCombo.getSelectedItem();
        log("[Draeger] Starting on TCP port " + port + " model=" + model);

        draegerThread = new Thread(() -> runDraegerServer(port), "draeger-sim-gui");
        draegerThread.setDaemon(true);
        draegerThread.start();
    }

    private void stopDraeger() {
        draegerRunning.set(false);
        btnDraegerStart.setEnabled(true);
        btnDraegerStop.setEnabled(false);
        draegerStatus.setText("Stopped");
        draegerStatus.setForeground(Color.DARK_GRAY);
        draegerConnStatus.setText("No connection");
        log("[Draeger] Stopped");
    }

    private void runDraegerServer(int port) {
        try (ServerSocket server = new ServerSocket(port)) {
            server.setSoTimeout(1000);
            log("[Draeger] Listening on port " + port);
            while (draegerRunning.get()) {
                try {
                    Socket client = server.accept();
                    SwingUtilities.invokeLater(() ->
                        draegerConnStatus.setText("Connected: " + client.getRemoteSocketAddress()));
                    log("[Draeger] Gateway connected from " + client.getRemoteSocketAddress());
                    handleDraegerClient(client);
                    SwingUtilities.invokeLater(() -> draegerConnStatus.setText("Disconnected"));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            log("[Draeger] Error: " + e.getMessage());
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
                if (b == 0x11 || b == 0x13) continue; // DC1/DC3

                if (b == 0x1B) { // ESC
                    inFrame = true;
                    pos = 0;
                    buf[pos++] = (byte) b;
                } else if (b == 0x0D && inFrame) { // CR
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
        String cmdName = draegerCmdName(cmd);
        log("[Draeger] <- " + cmdName);

        switch (cmd) {
            case 0x51: // ICC
                sendDraegerControlResponse(cmd, out);
                log("[Draeger] -> ICC acknowledged");
                break;
            case 0x30: // NOP
            case 0x55: // STOP
                sendDraegerControlResponse(cmd, out);
                break;
            case 0x52: // Device ID
                sendDraegerDeviceId(out);
                break;
            case 0x28: // DateTime
                sendDraegerDateTime(out);
                break;
            case 0x24: // Data CP1
                sendDraegerDataCP1(out);
                break;
            case 0x2B: // Data CP2
                sendDraegerDataResponse(0x2B, new byte[0], out);
                break;
            case 0x27: case 0x2E: // Alarms
                sendDraegerAlarms(cmd, out);
                break;
            case 0x29: // Settings
                sendDraegerControlResponse(cmd, out);
                break;
            case 0x2A: // Text messages
                sendDraegerControlResponse(cmd, out);
                break;
            default:
                sendDraegerControlResponse(cmd, out);
                break;
        }
    }

    private void sendDraegerControlResponse(int cmd, OutputStream out) throws IOException {
        int cs = 0x01 + cmd;
        out.write(0x01); out.write(cmd);
        out.write(hexHi(cs)); out.write(hexLo(cs));
        out.write(0x0D); out.flush();
    }

    private void sendDraegerDataResponse(int cmd, byte[] data, OutputStream out) throws IOException {
        int cs = 0x01 + cmd;
        for (byte b : data) cs += b & 0xFF;
        out.write(0x01); out.write(cmd); out.write(data);
        out.write(hexHi(cs)); out.write(hexLo(cs));
        out.write(0x0D); out.flush();
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
        sendDraegerDataResponse(0x52, d.toByteArray(), out);
        log("[Draeger] -> DeviceID: " + id + " '" + name + "' " + rev);
    }

    private void sendDraegerDateTime(OutputStream out) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String[] m = {"JAN","FEB","MAR","APR","MAI","JUN","JUL","AUG","SEP","OKT","NOV","DEZ"};
        String dt = String.format("%02d:%02d:%02d%02d-%s-%02d",
                now.getHour(), now.getMinute(), now.getSecond(),
                now.getDayOfMonth(), m[now.getMonthValue()-1], now.getYear()%100);
        sendDraegerDataResponse(0x28, dt.getBytes(), out);
    }

    private void sendDraegerDataCP1(OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        addDrRecord(d, 0x07, slCompliance.getValue());
        addDrRecord(d, 0x08, slResistance.getValue());
        addDrRecord(d, 0x73, (int)(slPeakPres.getValue() * 0.55));
        addDrRecord(d, 0x78, slPeep.getValue());
        addDrRecord(d, 0x7D, slPeakPres.getValue());
        addDrRecord(d, 0x82, slTidalVol.getValue());
        addDrRecord(d, 0xB5, slRespRate.getValue());
        addDrRecord(d, 0xB9, (int)(slTidalVol.getValue() * slRespRate.getValue() / 100.0));
        addDrRecord(d, 0xC1, slAirwayTemp.getValue());
        addDrRecord(d, 0xF0, slFio2.getValue());
        sendDraegerDataResponse(0x24, d.toByteArray(), out);
        log("[Draeger] -> CP1: VT=" + slTidalVol.getValue() + " RR=" + slRespRate.getValue() +
            " Paw=" + slPeakPres.getValue() + " PEEP=" + slPeep.getValue() + " FiO2=" + slFio2.getValue());
    }

    private void sendDraegerAlarms(int cmd, OutputStream out) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        if (chkPawHigh.isSelected())   addAlarmRecord(d, 27, 0x10, "PAW HIGH    ");
        if (chkO2High.isSelected())    addAlarmRecord(d, 23, 0x37, "% O2 HIGH   ");
        if (chkMinVolLow.isSelected()) addAlarmRecord(d, 26, 0x19, "MIN VOL LOW ");
        if (chkApnea.isSelected())     addAlarmRecord(d, 27, 0x98, "APNEA EVITA ");
        sendDraegerDataResponse(cmd, d.toByteArray(), out);
        if (d.size() > 0) log("[Draeger] -> " + (d.size()/15) + " alarm(s) active");
    }

    private void addDrRecord(ByteArrayOutputStream d, int code, int value) throws IOException {
        d.write(hexHi(code)); d.write(hexLo(code));
        String v = String.valueOf(Math.abs(value));
        while (v.length() < 4) v = " " + v;
        if (v.length() > 4) v = v.substring(v.length()-4);
        d.write(v.getBytes());
    }

    private void addAlarmRecord(ByteArrayOutputStream d, int priority, int code, String phrase) throws IOException {
        d.write(0x30 + priority);
        d.write(hexHi(code)); d.write(hexLo(code));
        String p = padTo(phrase, 12);
        d.write(p.substring(0, 12).getBytes());
    }

    // --- Philips simulator ---

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
        philipsStatus.setForeground(new Color(0, 150, 0));
        log("[Philips] Starting on UDP port " + port);

        philipsThread = new Thread(() -> runPhilipsServer(port), "philips-sim-gui");
        philipsThread.setDaemon(true);
        philipsThread.start();
    }

    private void stopPhilips() {
        philipsRunning.set(false);
        btnPhilipsStart.setEnabled(true);
        btnPhilipsStop.setEnabled(false);
        philipsStatus.setText("Stopped");
        philipsStatus.setForeground(Color.DARK_GRAY);
        philipsConnStatus.setText("No connection");
        log("[Philips] Stopped");
    }

    private void runPhilipsServer(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(500);
            log("[Philips] Listening on UDP port " + port);
            byte[] recvBuf = new byte[4096];
            boolean associated = false;

            while (philipsRunning.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(pkt);
                    ByteBuffer in = ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength());
                    in.order(ByteOrder.BIG_ENDIAN);
                    InetSocketAddress client = new InetSocketAddress(pkt.getAddress(), pkt.getPort());

                    int first = in.get(0) & 0xFF;

                    if (first == 0x0D) { // Association Request
                        log("[Philips] <- Association Request from " + client);
                        SwingUtilities.invokeLater(() ->
                            philipsConnStatus.setText("Connected: " + client));

                        // Send association response
                        sendPhilipsAssocResponse(socket, client);
                        associated = true;
                        log("[Philips] -> Association Response (accepted)");

                        // Send MDS Create Event
                        sendPhilipsMdsEvent(socket, client);
                        log("[Philips] -> MDS Create Event");

                    } else if (first == 0xE1 && associated) { // Data Export
                        in.position(3); // skip session header
                        int roType = in.getShort() & 0xFFFF;
                        if (roType == 0x0001) { // ROIV
                            in.getShort(); // ro length
                            int invokeId = in.getShort() & 0xFFFF;
                            int cmdType = in.getShort() & 0xFFFF;

                            if (cmdType == 0x0007) { // Confirmed Action (poll)
                                log("[Philips] <- Poll Request");
                                sendPhilipsPollResult(socket, client, invokeId);
                                log("[Philips] -> Poll Result: HR=" + slHeartRate.getValue() +
                                    " SpO2=" + slSpo2.getValue() + " RR=" + slPhRespRate.getValue() +
                                    " ABP=" + slAbpSys.getValue() + "/" + slAbpDia.getValue());
                            } else {
                                sendPhilipsEmptyResult(socket, client, invokeId, cmdType);
                            }
                        }
                    } else if (first == 0x09) { // Release
                        associated = false;
                        log("[Philips] <- Release Request");
                        SwingUtilities.invokeLater(() -> philipsConnStatus.setText("Disconnected"));
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            log("[Philips] Error: " + e.getMessage());
        }
    }

    private void sendPhilipsAssocResponse(DatagramSocket socket, InetSocketAddress addr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x0E); // AC_SPDU_SI
        buf.put((byte) 0x00);
        // Minimal accept — enough for the driver to proceed
        for (int i = 0; i < 48; i++) buf.put((byte) 0);
        buf.flip();
        sendUdp(socket, addr, buf);
    }

    private void sendPhilipsMdsEvent(DatagramSocket socket, InetSocketAddress addr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(128);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) 0xE1); // DATA_EXPORT_SPDU
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 0x0001); // ROIV
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) 0); // invoke id
        buf.putShort((short) 0x0001); // confirmed event report
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        // Managed object: MDS
        buf.putShort((short) 0x0021);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        // Event
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
        sendUdp(socket, addr, buf);
    }

    private void sendPhilipsPollResult(DatagramSocket socket, InetSocketAddress addr, int invokeId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(512);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) 0xE1);
        int lenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 0x0002); // RORS
        int roLenPos = buf.position();
        buf.putShort((short) 0);
        buf.putShort((short) invokeId);
        buf.putShort((short) 0x0007); // Confirmed Action
        int cmdLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 0x0021); // MDS
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        buf.putShort((short) 0x0C17); // Extended poll
        int actionLenPos = buf.position();
        buf.putShort((short) 0);

        buf.putShort((short) 0); // poll number
        buf.putInt((int)(System.currentTimeMillis() * 8));
        buf.putLong(0xFFFFFFFFFFFFFFFFL); // abs time

        // Poll info list
        buf.putShort((short) 1); // 1 context
        int infoLenPos = buf.position();
        buf.putShort((short) 0);
        int infoStart = buf.position();

        buf.putShort((short) 0); // mds_context

        // Numerics
        int[][] numerics = {
            {0x4002, slHeartRate.getValue(), 0x0AA0, 0x01},   // HR
            {0x4BB8, slSpo2.getValue(), 0x0220, 0x02},        // SpO2
            {0x4BB0, slHeartRate.getValue()+1, 0x0AA0, 0x03}, // Pulse Rate
            {0x5000, slPhRespRate.getValue(), 0x0AE0, 0x04},  // Resp Rate
            {0x4A51, slAbpSys.getValue(), 0x0F20, 0x10},      // ABP Sys
            {0x4A52, slAbpDia.getValue(), 0x0F20, 0x11},      // ABP Dia
            {0x4A53, (slAbpSys.getValue()+2*slAbpDia.getValue())/3, 0x0F20, 0x12}, // ABP Mean
            {0x4A21, slNbpSys.getValue(), 0x0F20, 0x20},      // NBP Sys
            {0x4A22, slNbpDia.getValue(), 0x0F20, 0x21},      // NBP Dia
            {0x4A23, (slNbpSys.getValue()+2*slNbpDia.getValue())/3, 0x0F20, 0x22}, // NBP Mean
            {0x5108, slEtCo2.getValue(), 0x0F20, 0x30},       // etCO2
            {0x4BB4, slTemp.getValue(), 0x17A0, 0x40},         // Temp (x10)
        };

        buf.putShort((short) numerics.length);
        int obsLenPos = buf.position();
        buf.putShort((short) 0);
        int obsStart = buf.position();

        for (int[] n : numerics) {
            buf.putShort((short) n[3]); // handle
            buf.putShort((short) 1);    // 1 attribute
            buf.putShort((short) 12);   // attr list length
            buf.putShort((short) 0x0950); // NOM_ATTR_NU_VAL_OBS
            buf.putShort((short) 12);
            buf.putShort((short) 2);    // NOM_PART_SCADA
            buf.putShort((short) n[0]); // physio id
            buf.putShort((short) 0);    // msmt state
            buf.putShort((short) 4);    // NOM_PART_DIM
            buf.putShort((short) n[2]); // unit code
            buf.putInt(encodeFloat(n[1], n[0] == 0x4BB4)); // FLOAT value
        }

        int obsEnd = buf.position();
        buf.putShort(obsLenPos, (short)(obsEnd - obsStart));
        buf.putShort(infoLenPos, (short)(obsEnd - infoStart));
        buf.putShort(actionLenPos, (short)(obsEnd - actionLenPos - 2));
        buf.putShort(cmdLenPos, (short)(obsEnd - cmdLenPos - 2));
        buf.putShort(roLenPos, (short)(obsEnd - roLenPos - 2));
        buf.putShort(lenPos, (short)(obsEnd - lenPos - 2));

        buf.flip();
        sendUdp(socket, addr, buf);
    }

    private void sendPhilipsEmptyResult(DatagramSocket socket, InetSocketAddress addr, int invokeId, int cmdType) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0xE1);
        buf.putShort((short) 16);
        buf.putShort((short) 0x0002);
        buf.putShort((short) 12);
        buf.putShort((short) invokeId);
        buf.putShort((short) cmdType);
        buf.putShort((short) 6);
        buf.putShort((short) 0x0021);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.flip();
        sendUdp(socket, addr, buf);
    }

    private int encodeFloat(int value, boolean isTempX10) {
        if (isTempX10) {
            // Temperature stored as x10 in slider, send as x.x
            return (0xFF << 24) | (value & 0x00FFFFFF); // exp=-1, mantissa=value
        }
        return (value & 0x00FFFFFF); // exp=0, mantissa=value
    }

    private void sendUdp(DatagramSocket socket, InetSocketAddress addr, ByteBuffer buf) throws IOException {
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        socket.send(new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort()));
    }

    // --- Helpers ---

    private static int hexHi(int v) { int n = (v>>4)&0xF; return n<10 ? '0'+n : 'A'+n-10; }
    private static int hexLo(int v) { int n = v&0xF;      return n<10 ? '0'+n : 'A'+n-10; }
    private static String padTo(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String draegerCmdName(int cmd) {
        switch (cmd) {
            case 0x51: return "ICC"; case 0x30: return "NOP"; case 0x55: return "STOP";
            case 0x52: return "ReqDeviceId"; case 0x28: return "ReqDateTime";
            case 0x24: return "ReqDataCP1"; case 0x2B: return "ReqDataCP2";
            case 0x27: return "ReqAlarmsCP1"; case 0x2E: return "ReqAlarmsCP2";
            case 0x29: return "ReqSettings"; case 0x2A: return "ReqTextMsg";
            default: return "Cmd(0x" + String.format("%02X", cmd) + ")";
        }
    }

    // --- Main ---

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SimulatorGui gui = new SimulatorGui();
            gui.setVisible(true);
        });
    }
}
