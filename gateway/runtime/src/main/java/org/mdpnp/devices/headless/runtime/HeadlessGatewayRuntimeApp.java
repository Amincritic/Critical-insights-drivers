package org.mdpnp.devices.headless.runtime;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.mdpnp.devices.draeger.medibus.HeadlessDraegerMedibus;
import org.mdpnp.devices.draeger.medibus.DraegerMedibusProfile;
import org.mdpnp.devices.draeger.medibus.types.Command;
import org.mdpnp.devices.headless.FileJsonPublisher;
import org.mdpnp.devices.headless.HttpJsonPublisher;
import org.mdpnp.devices.headless.CompactStdoutPublisher;
import org.mdpnp.devices.headless.MultiJsonPublisher;
import org.mdpnp.devices.headless.OutputFormatPublisher;
import org.mdpnp.devices.headless.WebDashboardPublisher;
import org.mdpnp.devices.headless.QueuedJsonPublisher;
import org.mdpnp.devices.headless.StdoutJsonPublisher;
import org.mdpnp.devices.net.NetworkLoop;
import org.mdpnp.devices.philips.intellivue.Intellivue;
import org.mdpnp.devices.philips.intellivue.RS232Adapter;
import org.mdpnp.devices.philips.intellivue.headless.HeadlessPhilipsIntellivue;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialProviderFactory;
import org.mdpnp.devices.serial.SerialSocket;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;

/**
 * Minimal combined launcher for a Jetson-style bedside gateway.
 *
 * It starts one Philips IntelliVue adapter (LAN/UDP or MIB/RS232) and/or one Draeger MEDIBUS adapter.
 * Both adapters publish normalized JSON into the same QueuedJsonPublisher.
 */
public final class HeadlessGatewayRuntimeApp {
    private HeadlessGatewayRuntimeApp() { }

    private static final long HEARTBEAT_INTERVAL_MS = 60_000L;
    private static final ConcurrentHashMap<String, AtomicLong> lastDataReceived = new ConcurrentHashMap<String, AtomicLong>();
    private static final ConcurrentHashMap<String, String> deviceState = new ConcurrentHashMap<String, String>();

    static void recordDataReceived(String deviceId) {
        AtomicLong ts = lastDataReceived.get(deviceId);
        if (ts == null) {
            lastDataReceived.putIfAbsent(deviceId, new AtomicLong(System.currentTimeMillis()));
        } else {
            ts.set(System.currentTimeMillis());
        }
    }

    static void setDeviceState(String deviceId, String state) {
        String prev = deviceState.put(deviceId, state);
        if (prev == null || !prev.equals(state)) {
            System.err.println("DEVICE_STATE " + deviceId + ": " + state);
        }
    }

    private static void logHeartbeat(List<Thread> deviceThreads, QueuedJsonPublisher queue) {
        StringBuilder sb = new StringBuilder("HEARTBEAT | threads: ");
        for (Thread t : deviceThreads) {
            sb.append(t.getName()).append("=").append(t.isAlive() ? "alive" : "DEAD").append(" ");
        }
        sb.append("| devices: ");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AtomicLong> e : lastDataReceived.entrySet()) {
            long ageSec = (now - e.getValue().get()) / 1000L;
            String state = deviceState.getOrDefault(e.getKey(), "unknown");
            sb.append(e.getKey()).append("=").append(state);
            sb.append("(last_data=").append(ageSec).append("s_ago) ");
        }
        System.err.println(sb.toString().trim());
    }

    public static void main(String[] args) throws Exception {
        final Args a = Args.parse(args);

        if (a.philipsHost != null && a.philipsSerial != null) {
            throw new IllegalArgumentException("Choose only one Philips transport: --philips-host for LAN/UDP or --philips-serial for MIB/RS232");
        }
        if (a.draegerSerial != null && a.draegerTcpHost != null) {
            throw new IllegalArgumentException("Choose only one Draeger transport: --draeger-serial or --draeger-tcp-host/--draeger-tcp-port");
        }
        if (a.philipsHost == null && a.philipsSerial == null && a.draegerSerial == null && a.draegerTcpHost == null && a.webPort <= 0) {
            throw new IllegalArgumentException("Enable at least one device or web-only replay mode: --philips-host, --philips-serial, --draeger-serial, --draeger-tcp-host/--draeger-tcp-port, or --web-port");
        }

        MultiJsonPublisher sinks = buildSinks(a.stdout, a.jsonl, a.httpUrl, a.httpTimeoutMs, a.httpHeaders, a.allowInsecureHttp, a.webPort, a.stdoutMode, a.outputFormat);
        final QueuedJsonPublisher queue = buildQueue(sinks, a);
        final AtomicBoolean running = new AtomicBoolean(true);
        final List<Thread> deviceThreads = new ArrayList<Thread>();
        queue.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                running.set(false);
                for (Thread t : deviceThreads) { t.interrupt(); }
                for (Thread t : deviceThreads) {
                    try { t.join(Math.min(5000L, a.shutdownDrainTimeoutMs)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                try { queue.close(); } catch (Exception e) { System.err.println("Queue close failed during shutdown: " + e.toString()); }
            }
        }));

        if (a.philipsHost != null) { deviceThreads.add(startPhilipsUdp(a, queue, running)); }
        if (a.philipsSerial != null) { deviceThreads.add(startPhilipsSerial(a, queue, running)); }
        if (a.draegerSerial != null || a.draegerTcpHost != null) { deviceThreads.add(startDraeger(a, queue, running)); }

        if (deviceThreads.isEmpty()) {
            System.err.println("Gateway web-only replay mode started. gatewayId=" + a.gatewayId + " bedId=" + a.bedId);
        } else {
            System.err.println("Multi-device headless gateway started. gatewayId=" + a.gatewayId + " bedId=" + a.bedId);
        }
        while (running.get()) {
            Thread.sleep(HEARTBEAT_INTERVAL_MS);
            if (running.get()) { logHeartbeat(deviceThreads, queue); }
        }
    }

    private static MultiJsonPublisher buildSinks(Boolean stdout, String jsonl, String httpUrl, int httpTimeoutMs, Map<String, String> headers, boolean allowInsecureHttp, int webPort, String stdoutMode, String outputFormat) throws Exception {
        MultiJsonPublisher sinks = new MultiJsonPublisher();
        boolean enableStdout = stdout != null ? stdout.booleanValue() : (jsonl == null && httpUrl == null && webPort <= 0);
        if (enableStdout) {
            if ("compact".equalsIgnoreCase(stdoutMode)) {
                sinks.add(new CompactStdoutPublisher());
            } else {
                sinks.add(format(new StdoutJsonPublisher(), outputFormat));
            }
        }
        if (jsonl != null) { sinks.add(format(new FileJsonPublisher(Paths.get(jsonl)), outputFormat)); }
        if (httpUrl != null) { sinks.add(format(new HttpJsonPublisher(httpUrl, httpTimeoutMs, headers, allowInsecureHttp), outputFormat)); }
        if (webPort > 0) {
            sinks.add(format(new WebDashboardPublisher(webPort, webPort + 1), outputFormat));
            System.err.println("Web dashboard: http://localhost:" + webPort);
        }
        return sinks;
    }

    private static org.mdpnp.devices.headless.JsonPublisher format(org.mdpnp.devices.headless.JsonPublisher publisher, String outputFormat) {
        return new OutputFormatPublisher(publisher, outputFormat);
    }

    private static QueuedJsonPublisher buildQueue(MultiJsonPublisher sinks, Args a) {
        Path deadLetter = a.deadLetterJsonl == null ? null : Paths.get(a.deadLetterJsonl);
        return new QueuedJsonPublisher(sinks, a.queueCapacity, a.publishAttempts, a.publishRetryBackoffMs, a.shutdownDrainTimeoutMs, deadLetter);
    }

    private static Thread startPhilipsUdp(final Args a, final QueuedJsonPublisher queue, final AtomicBoolean running) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    DatagramChannel channel = null;
                    try {
                        final HeadlessPhilipsIntellivue adapter = new HeadlessPhilipsIntellivue(a.gatewayId, a.bedId, a.philipsDeviceId, queue);
                        channel = DatagramChannel.open();
                        channel.configureBlocking(false);
                        channel.socket().setReuseAddress(true);
                        channel.socket().bind(new InetSocketAddress(a.philipsLocalPort));
                        channel.connect(new InetSocketAddress(a.philipsHost, a.philipsPort));

                        final NetworkLoop networkLoop = new NetworkLoop();
                        networkLoop.register(adapter, channel);
                        System.err.println("Started Philips adapter host=" + a.philipsHost + ":" + a.philipsPort + " deviceId=" + a.philipsDeviceId);
                        setDeviceState(a.philipsDeviceId, "connected");
                        recordDataReceived(a.philipsDeviceId);
                        networkLoop.runLoop();
                    } catch (Exception e) {
                        setDeviceState(a.philipsDeviceId, "disconnected");
                        System.err.println("Philips network loop stopped: " + e.getMessage() + "; reconnecting in " + a.reconnectMs + " ms");
                    } finally {
                        try { if (channel != null) { channel.close(); } } catch (Exception ignored) { }
                    }
                    if (running.get()) { sleepQuietly(a.reconnectMs); }
                }
            }
        }, "philips-intellivue-supervisor");
        t.setDaemon(false);
        t.start();
        return t;
    }

    private static Thread startPhilipsSerial(final Args a, final QueuedJsonPublisher queue, final AtomicBoolean running) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    RS232Adapter serialBridge = null;
                    DatagramChannel channel = null;
                    try {
                        final NetworkLoop networkLoop = new NetworkLoop();
                        final ThreadGroup threadGroup = new ThreadGroup("philips-mib-rs232");
                        int[] ports = getAvailablePorts(2);
                        final InetSocketAddress serialSide = new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[0]);
                        final InetSocketAddress parserSide = new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[1]);

                        serialBridge = new RS232Adapter(a.philipsSerial, serialSide, parserSide, threadGroup, networkLoop, a.philipsBaud);
                        final HeadlessPhilipsIntellivue adapter = new HeadlessPhilipsIntellivue(a.gatewayId, a.bedId, a.philipsDeviceId, "philips_mib_rs232", queue);

                        channel = DatagramChannel.open();
                        channel.configureBlocking(false);
                        channel.socket().setReuseAddress(true);
                        channel.bind(parserSide);
                        channel.connect(serialSide);
                        networkLoop.register(adapter, channel);

                        System.err.println("Started Philips MIB/RS232 adapter serial=" + a.philipsSerial + " deviceId=" + a.philipsDeviceId);
                        System.err.println("Philips MIB/RS232 serial settings: " + a.philipsBaud + " baud, 8N1, no flow control");
                        setDeviceState(a.philipsDeviceId, "connected");
                        recordDataReceived(a.philipsDeviceId);
                        networkLoop.runLoop();
                    } catch (Exception e) {
                        setDeviceState(a.philipsDeviceId, "disconnected");
                        System.err.println("Philips MIB/RS232 loop stopped: " + e.getMessage() + "; reconnecting in " + a.reconnectMs + " ms");
                    } finally {
                        try { if (serialBridge != null) { serialBridge.shutdown(); } } catch (Exception ignored) { }
                        try { if (channel != null) { channel.close(); } } catch (Exception ignored) { }
                    }
                    if (running.get()) { sleepQuietly(a.reconnectMs); }
                }
            }
        }, "philips-mib-rs232-supervisor");
        t.setDaemon(false);
        t.start();
        return t;
    }

    private static int[] getAvailablePorts(int count) throws Exception {
        int[] ports = new int[count];
        for (int i = 0; i < count; i++) {
            ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            ports[i] = ss.getLocalPort();
            ss.close();
        }
        return ports;
    }

    private static Thread startDraeger(final Args a, final QueuedJsonPublisher queue, final AtomicBoolean running) {
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    SerialSocket serialSocket = null;
                    Socket tcpSocket = null;
                    InputStream in = null;
                    OutputStream out = null;
                    Thread poller = null;
                    try {
                        if (a.draegerTcpHost != null) {
                            tcpSocket = new Socket();
                            tcpSocket.connect(new InetSocketAddress(a.draegerTcpHost, a.draegerTcpPort), (int) a.connectTimeoutMs);
                            in = tcpSocket.getInputStream();
                            out = tcpSocket.getOutputStream();
                        } else {
                            SerialProvider sp = SerialProviderFactory.getDefaultProvider();
                            sp.setDefaultSerialSettings(a.draegerBaud, DataBits.Eight, a.draegerParity, StopBits.One, FlowControl.None);
                            serialSocket = sp.connect(a.draegerSerial, a.connectTimeoutMs);
                            serialSocket.setSerialParams(a.draegerBaud, DataBits.Eight, a.draegerParity, StopBits.One, FlowControl.None);
                            in = serialSocket.getInputStream();
                            out = serialSocket.getOutputStream();
                        }
                        final HeadlessDraegerMedibus medibus = new HeadlessDraegerMedibus(in, out, a.gatewayId, a.bedId, a.draegerDeviceId, a.draegerProfile, queue);
                        poller = startDraegerPoller(medibus, a.draegerPollMs, a.draegerLowFrequencyPollMs, a.draegerProfile);
                        if (a.draegerTcpHost != null) {
                            System.err.println("Started Draeger adapter tcp=" + a.draegerTcpHost + ":" + a.draegerTcpPort + " deviceId=" + a.draegerDeviceId + " profile=" + a.draegerProfile.id());
                        } else {
                            System.err.println("Started Draeger adapter serial=" + a.draegerSerial + " baud=" + a.draegerBaud + " deviceId=" + a.draegerDeviceId + " profile=" + a.draegerProfile.id());
                        }
                        setDeviceState(a.draegerDeviceId, "connected");
                        recordDataReceived(a.draegerDeviceId);
                        while (medibus.receive()) {
                            recordDataReceived(a.draegerDeviceId);
                        }
                        setDeviceState(a.draegerDeviceId, "disconnected");
                        System.err.println("Draeger reader stopped; reconnecting");
                    } catch (Exception e) {
                        setDeviceState(a.draegerDeviceId, "disconnected");
                        System.err.println("Draeger adapter error: " + e.getMessage() + "; reconnecting in " + a.reconnectMs + " ms");
                    } finally {
                        if (poller != null) { poller.interrupt(); }
                        try { if (in != null) { in.close(); } } catch (Exception ignored) { }
                        try { if (out != null) { out.close(); } } catch (Exception ignored) { }
                        try { if (tcpSocket != null) { tcpSocket.close(); } } catch (Exception ignored) { }
                        try { if (serialSocket != null) { serialSocket.close(); } } catch (Exception ignored) { }
                    }
                    if (running.get()) { sleepQuietly(a.reconnectMs); }
                }
            }
        }, "draeger-medibus-supervisor");
        reader.setDaemon(false);
        reader.start();
        return reader;
    }

    private static Thread startDraegerPoller(final HeadlessDraegerMedibus medibus, final long pollMs,
            final long lowFrequencyPollMs, final DraegerMedibusProfile profile) {
        Thread poller = new Thread(new Runnable() {
            @Override public void run() {
                // Per MEDIBUS protocol spec: send ICC to initialize communication
                try {
                    medibus.sendCommand(Command.InitializeComm);
                    System.err.println("Draeger: ICC sent, communication initialized");
                    if (profile.realtime()) {
                        medibus.enableDefaultRealtimeWaveforms();
                        System.err.println("Draeger: realtime configuration requested");
                    }
                } catch (Exception e) {
                    System.err.println("Draeger: initialization failed: " + e.getMessage());
                }

                long lastLowFrequencyPoll = 0L;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        long now = System.currentTimeMillis();
                        medibus.sendCommand(Command.ReqDeviceId);
                        medibus.sendCommand(Command.ReqDateTime);
                        medibus.sendCommand(Command.ReqMeasuredDataCP1);
                        if (profile.measuredCodePage2()) { medibus.sendCommand(Command.ReqMeasuredDataCP2); }
                        medibus.sendCommand(Command.ReqAlarmsCP1);
                        if (profile.alarmCodePage2()) { medibus.sendCommand(Command.ReqAlarmsCP2); }
                        if (profile.alarmCodePage3()) { medibus.sendCommand(Command.ReqAlarmsCP3); }
                        if (now - lastLowFrequencyPoll >= lowFrequencyPollMs) {
                            if (profile.alarmLimits()) {
                                medibus.sendCommand(Command.ReqLowAlarmLimitsCP1);
                                medibus.sendCommand(Command.ReqHighAlarmLimitsCP1);
                                if (profile.measuredCodePage2()) {
                                    medibus.sendCommand(Command.ReqLowAlarmLimitsCP2);
                                    medibus.sendCommand(Command.ReqHighAlarmLimitsCP2);
                                }
                            }
                            if (profile.settings()) { medibus.sendCommand(Command.ReqDeviceSetting); }
                            if (profile.textMessages()) { medibus.sendCommand(Command.ReqTextMessages); }
                            if (profile.realtime()) { medibus.sendCommand(Command.ReqRealtimeConfig); }
                            if (profile.trend()) { medibus.sendCommand(Command.ReqTrendDataStatus); }
                            lastLowFrequencyPoll = now;
                        }
                        Thread.sleep(pollMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("Draeger poller error: " + e.getMessage());
                        sleepQuietly(1000L);
                    }
                }
            }
        }, "draeger-poller");
        poller.setDaemon(true);
        poller.start();
        return poller;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static final class Args {
        String gatewayId = "jetson_nicu_01";
        String bedId = "bed_01";
        String jsonl;
        String deadLetterJsonl;
        String httpUrl;
        Map<String, String> httpHeaders = new LinkedHashMap<String, String>();
        Boolean stdout;
        int httpTimeoutMs = 3000;
        boolean allowInsecureHttp = false;
        int queueCapacity = 10000;
        int publishAttempts = 5;
        long publishRetryBackoffMs = 500L;
        long shutdownDrainTimeoutMs = 30000L;
        long reconnectMs = 5000L;
        long connectTimeoutMs = 10000L;

        String philipsHost;
        String philipsSerial;
        int philipsPort = Intellivue.DEFAULT_UNICAST_PORT;
        int philipsLocalPort = 0; // ephemeral port — avoids conflict when simulator uses 24105
        int philipsBaud = RS232Adapter.DEFAULT_BAUD;
        String philipsDeviceId = "philips_monitor_01";

        String draegerSerial;
        String draegerTcpHost;
        int draegerTcpPort = 0;
        String draegerDeviceId = "draeger_vent_01";
        long draegerPollMs = 1000L;
        long draegerLowFrequencyPollMs = 10000L;
        int draegerBaud = 19200;
        Parity draegerParity = Parity.Even;
        DraegerMedibusProfile draegerProfile = DraegerMedibusProfile.V500;
        int webPort = 0;
        String stdoutMode = "json";
        String outputFormat = OutputFormatPublisher.CANONICAL;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i=0; i<args.length; i++) {
                String k = args[i];
                String v = (i+1)<args.length ? args[i+1] : null;
                if ("--gateway-id".equals(k)) { a.gatewayId = required(k, v); i++; }
                else if ("--bed-id".equals(k)) { a.bedId = required(k, v); i++; }
                else if ("--jsonl".equals(k)) { a.jsonl = required(k, v); i++; }
                else if ("--dead-letter-jsonl".equals(k)) { a.deadLetterJsonl = required(k, v); i++; }
                else if ("--http-url".equals(k)) { a.httpUrl = required(k, v); i++; }
                else if ("--http-header".equals(k)) { addHeader(a.httpHeaders, required(k, v)); i++; }
                else if ("--http-timeout-ms".equals(k)) { a.httpTimeoutMs = Integer.parseInt(required(k, v)); i++; }
                else if ("--allow-insecure-http".equals(k)) { a.allowInsecureHttp = Boolean.parseBoolean(required(k, v)); i++; }
                else if ("--output-format".equals(k)) { a.outputFormat = OutputFormatPublisher.normalize(required(k, v)); i++; }
                else if ("--queue-capacity".equals(k)) { a.queueCapacity = Integer.parseInt(required(k, v)); i++; }
                else if ("--publish-attempts".equals(k)) { a.publishAttempts = Integer.parseInt(required(k, v)); i++; }
                else if ("--publish-retry-backoff-ms".equals(k)) { a.publishRetryBackoffMs = Long.parseLong(required(k, v)); i++; }
                else if ("--shutdown-drain-timeout-ms".equals(k)) { a.shutdownDrainTimeoutMs = Long.parseLong(required(k, v)); i++; }
                else if ("--reconnect-ms".equals(k)) { a.reconnectMs = Long.parseLong(required(k, v)); i++; }
                else if ("--connect-timeout-ms".equals(k)) { a.connectTimeoutMs = Long.parseLong(required(k, v)); i++; }
                else if ("--stdout".equals(k)) {
                    String sv = required(k, v);
                    if ("compact".equalsIgnoreCase(sv)) { a.stdout = true; a.stdoutMode = "compact"; }
                    else { a.stdout = Boolean.valueOf(sv); }
                    i++;
                }
                else if ("--philips-host".equals(k)) { a.philipsHost = required(k, v); i++; }
                else if ("--philips-serial".equals(k)) { a.philipsSerial = required(k, v); i++; }
                else if ("--philips-port".equals(k)) { a.philipsPort = Integer.parseInt(required(k, v)); i++; }
                else if ("--philips-local-port".equals(k)) { a.philipsLocalPort = Integer.parseInt(required(k, v)); i++; }
                else if ("--philips-baud".equals(k) || "--philips-serial-baud".equals(k)) { a.philipsBaud = Integer.parseInt(required(k, v)); i++; }
                else if ("--philips-device-id".equals(k)) { a.philipsDeviceId = required(k, v); i++; }
                else if ("--draeger-serial".equals(k)) { a.draegerSerial = required(k, v); i++; }
                else if ("--draeger-tcp-host".equals(k)) { a.draegerTcpHost = required(k, v); i++; }
                else if ("--draeger-tcp-port".equals(k)) { a.draegerTcpPort = Integer.parseInt(required(k, v)); i++; }
                else if ("--draeger-device-id".equals(k)) { a.draegerDeviceId = required(k, v); i++; }
                else if ("--draeger-poll-ms".equals(k)) { a.draegerPollMs = Long.parseLong(required(k, v)); i++; }
                else if ("--draeger-low-frequency-poll-ms".equals(k)) { a.draegerLowFrequencyPollMs = Long.parseLong(required(k, v)); i++; }
                else if ("--draeger-baud".equals(k)) { a.draegerBaud = Integer.parseInt(required(k, v)); i++; }
                else if ("--draeger-parity".equals(k)) { a.draegerParity = parseParity(required(k, v)); i++; }
                else if ("--draeger-profile".equals(k)) { a.draegerProfile = DraegerMedibusProfile.parse(required(k, v)); i++; }
                else if ("--web-port".equals(k)) { a.webPort = Integer.parseInt(required(k, v)); i++; }
                else if ("--help".equals(k)) { usageAndExit(); }
                else { throw new IllegalArgumentException("Unknown argument: " + k); }
            }
            if (a.philipsHost != null && a.philipsSerial != null) {
                throw new IllegalArgumentException("Choose only one Philips transport: --philips-host or --philips-serial");
            }
            if (a.draegerSerial != null && a.draegerTcpHost != null) {
                throw new IllegalArgumentException("Choose only one Draeger transport: --draeger-serial or --draeger-tcp-host/--draeger-tcp-port");
            }
            if (a.draegerTcpHost != null && a.draegerTcpPort <= 0) {
                throw new IllegalArgumentException("--draeger-tcp-port must be > 0 when --draeger-tcp-host is set");
            }
            if (a.philipsBaud != 115200 && a.philipsBaud != 19200) { throw new IllegalArgumentException("--philips-baud must be 115200 or 19200"); }
            if (a.draegerBaud <= 0) { throw new IllegalArgumentException("--draeger-baud must be > 0"); }
            if (a.draegerPollMs <= 0 || a.draegerLowFrequencyPollMs <= 0) { throw new IllegalArgumentException("Draeger poll intervals must be > 0"); }
            validate(a.queueCapacity, a.publishAttempts, a.publishRetryBackoffMs, a.shutdownDrainTimeoutMs, a.reconnectMs);
            return a;
        }

        static void addHeader(Map<String, String> headers, String raw) {
            int idx = raw.indexOf(':');
            if (idx <= 0) { throw new IllegalArgumentException("--http-header must be in 'Name: value' format"); }
            headers.put(raw.substring(0, idx).trim(), raw.substring(idx + 1).trim());
        }

        static void validate(int queueCapacity, int publishAttempts, long retryBackoffMs, long drainMs, long reconnectMs) {
            if (queueCapacity <= 0) { throw new IllegalArgumentException("--queue-capacity must be > 0"); }
            if (publishAttempts <= 0) { throw new IllegalArgumentException("--publish-attempts must be > 0"); }
            if (retryBackoffMs < 0 || reconnectMs < 0) { throw new IllegalArgumentException("retry/reconnect timeouts must be >= 0"); }
            if (drainMs <= 0) { throw new IllegalArgumentException("--shutdown-drain-timeout-ms must be > 0"); }
        }

        static Parity parseParity(String v) {
            if ("even".equalsIgnoreCase(v)) return Parity.Even;
            if ("odd".equalsIgnoreCase(v)) return Parity.Odd;
            if ("none".equalsIgnoreCase(v)) return Parity.None;
            throw new IllegalArgumentException("Parity must be even, odd, or none (default: even)");
        }
        static String required(String k, String v) {
            if (v == null || v.startsWith("--")) { throw new IllegalArgumentException("Missing value for " + k); }
            return v;
        }

        static void usageAndExit() {
            System.out.println("Usage: gateway-runtime [--philips-host <ip> | --philips-serial /dev/philips_monitor_01 --philips-baud 115200|19200] [--draeger-serial /dev/draeger_vent_01 | --draeger-tcp-host <host> --draeger-tcp-port <port>] [--draeger-profile v500|intensive-care|evita|savina|fabius] [--jsonl file] [--output-format canonical] [--dead-letter-jsonl file] [--http-url <url>] [--allow-insecure-http true|false] [--http-header 'Authorization: Bearer TOKEN'] [--stdout true|compact|false] [--web-port <port>]");
            System.exit(0);
        }
    }
}
