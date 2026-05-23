package org.mdpnp.devices.draeger.medibus.headless;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.devices.draeger.medibus.HeadlessDraegerMedibus;
import org.mdpnp.devices.draeger.medibus.types.Command;
import org.mdpnp.devices.headless.FileJsonPublisher;
import org.mdpnp.devices.headless.HttpJsonPublisher;
import org.mdpnp.devices.headless.CompactStdoutPublisher;
import org.mdpnp.devices.headless.MultiJsonPublisher;
import org.mdpnp.devices.headless.WebDashboardPublisher;
import org.mdpnp.devices.headless.QueuedJsonPublisher;
import org.mdpnp.devices.headless.StdoutJsonPublisher;
import org.mdpnp.devices.serial.SerialProvider;
import org.mdpnp.devices.serial.SerialProviderFactory;
import org.mdpnp.devices.serial.SerialSocket;
import org.mdpnp.devices.serial.SerialSocket.DataBits;
import org.mdpnp.devices.serial.SerialSocket.FlowControl;
import org.mdpnp.devices.serial.SerialSocket.Parity;
import org.mdpnp.devices.serial.SerialSocket.StopBits;

public final class HeadlessDraegerGatewayApp {
    private HeadlessDraegerGatewayApp() { }

    public static void main(String[] args) throws Exception {
        final Args a = Args.parse(args);
        final MultiJsonPublisher sinks = buildSinks(a.stdout, a.jsonl, a.httpUrl, a.httpTimeoutMs, a.httpHeaders, a.allowInsecureHttp, a.webPort, a.stdoutMode);
        final QueuedJsonPublisher queued = buildQueue(sinks, a);
        final AtomicBoolean running = new AtomicBoolean(true);
        queued.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                running.set(false);
                try { queued.close(); } catch (Exception ignored) { }
            }
        }));

        while (running.get()) {
            Transport t = null;
            Thread poller = null;
            try {
                t = openTransport(a);
                final HeadlessDraegerMedibus medibus = new HeadlessDraegerMedibus(t.in, t.out, a.gatewayId, a.bedId, a.deviceId, queued);
                poller = startPoller(medibus, a.pollMs);
                System.err.println("Draeger headless gateway connected: deviceId=" + a.deviceId);
                while (running.get() && medibus.receive()) { }
                System.err.println("Draeger reader stopped; reconnecting");
            } catch (Exception e) {
                if (running.get()) { System.err.println("Draeger gateway error: " + e.getMessage()); }
            } finally {
                if (poller != null) { poller.interrupt(); }
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
            }
            if (running.get()) { sleepQuietly(a.reconnectMs); }
        }
    }

    private static MultiJsonPublisher buildSinks(Boolean stdout, String jsonl, String httpUrl, int httpTimeoutMs, Map<String, String> headers, boolean allowInsecureHttp, int webPort, String stdoutMode) throws Exception {
        MultiJsonPublisher sinks = new MultiJsonPublisher();
        boolean enableStdout = stdout != null ? stdout.booleanValue() : (jsonl == null && httpUrl == null && webPort <= 0);
        if (enableStdout) {
            if ("compact".equalsIgnoreCase(stdoutMode)) {
                sinks.add(new CompactStdoutPublisher());
            } else {
                sinks.add(new StdoutJsonPublisher());
            }
        }
        if (jsonl != null) { sinks.add(new FileJsonPublisher(Paths.get(jsonl))); }
        if (httpUrl != null) { sinks.add(new HttpJsonPublisher(httpUrl, httpTimeoutMs, headers, allowInsecureHttp)); }
        if (webPort > 0) {
            sinks.add(new WebDashboardPublisher(webPort, webPort + 1));
            System.err.println("Web dashboard: http://localhost:" + webPort);
        }
        return sinks;
    }

    private static QueuedJsonPublisher buildQueue(MultiJsonPublisher sinks, Args a) {
        Path deadLetter = a.deadLetterJsonl == null ? null : Paths.get(a.deadLetterJsonl);
        return new QueuedJsonPublisher(sinks, a.queueCapacity, a.publishAttempts, a.publishRetryBackoffMs, a.shutdownDrainTimeoutMs, deadLetter);
    }

    private static Thread startPoller(final HeadlessDraegerMedibus medibus, final long pollMs) {
        Thread poller = new Thread(new Runnable() {
            @Override public void run() {
                // Per MEDIBUS protocol spec: send ICC to initialize communication
                try {
                    medibus.sendCommand(Command.InitializeComm);
                    System.err.println("Draeger: ICC sent, communication initialized");
                } catch (Exception e) {
                    System.err.println("Draeger: ICC failed: " + e.getMessage());
                }

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        medibus.sendCommand(Command.ReqDeviceId);
                        medibus.sendCommand(Command.ReqDateTime);
                        medibus.sendCommand(Command.ReqMeasuredDataCP1);
                        medibus.sendCommand(Command.ReqMeasuredDataCP2);
                        medibus.sendCommand(Command.ReqAlarmsCP1);
                        medibus.sendCommand(Command.ReqAlarmsCP2);
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

    private static Transport openTransport(Args a) throws Exception {
        if (a.tcpHost != null) {
            Socket s = new Socket(a.tcpHost, a.tcpPort);
            try {
                return new Transport(s.getInputStream(), s.getOutputStream(), s, null);
            } catch (Exception e) {
                try { s.close(); } catch (Exception ignored) { }
                throw e;
            }
        }
        if (a.serial != null) {
            SerialProvider sp = SerialProviderFactory.getDefaultProvider();
            sp.setDefaultSerialSettings(a.serialBaud, DataBits.Eight, a.serialParity, StopBits.One, FlowControl.None);
            SerialSocket ss = sp.connect(a.serial, a.connectTimeoutMs);
            ss.setSerialParams(a.serialBaud, DataBits.Eight, a.serialParity, StopBits.One, FlowControl.None);
            String parityStr = a.serialParity == Parity.Even ? "8E1" : a.serialParity == Parity.Odd ? "8O1" : "8N1";
            System.err.println("Draeger serial settings: " + a.serialBaud + " baud, " + parityStr + ", no flow control");
            return new Transport(ss.getInputStream(), ss.getOutputStream(), null, ss);
        }
        throw new IllegalArgumentException("Either --serial or --tcp-host/--tcp-port is required");
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static final class Transport implements AutoCloseable {
        final InputStream in;
        final OutputStream out;
        final Socket socket;
        final SerialSocket serialSocket;
        Transport(InputStream in, OutputStream out, Socket socket, SerialSocket serialSocket) {
            this.in = in; this.out = out; this.socket = socket; this.serialSocket = serialSocket;
        }
        @Override public void close() throws Exception {
            try { if (in != null) { in.close(); } }
            finally {
                try { if (out != null) { out.close(); } }
                finally {
                    try { if (socket != null) { socket.close(); } }
                    finally { if (serialSocket != null) { serialSocket.close(); } }
                }
            }
        }
    }

    static final class Args {
        String serial;
        String tcpHost;
        int tcpPort = 0;
        String gatewayId = "jetson_nicu_01";
        String bedId = "bed_01";
        String deviceId = "draeger_vent_01";
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
        long pollMs = 1000L;
        long reconnectMs = 5000L;
        long connectTimeoutMs = 10000L;
        int serialBaud = 19200;
        Parity serialParity = Parity.Even;
        int webPort = 0;
        String stdoutMode = "json";

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i=0; i<args.length; i++) {
                String k=args[i]; String v=(i+1)<args.length?args[i+1]:null;
                if ("--serial".equals(k)) { a.serial = required(k,v); i++; }
                else if ("--serial-baud".equals(k)) { a.serialBaud = Integer.parseInt(required(k,v)); i++; }
                else if ("--serial-parity".equals(k)) { a.serialParity = parseParity(required(k,v)); i++; }
                else if ("--web-port".equals(k)) { a.webPort = Integer.parseInt(required(k,v)); i++; }
                else if ("--tcp-host".equals(k)) { a.tcpHost = required(k,v); i++; }
                else if ("--tcp-port".equals(k)) { a.tcpPort = Integer.parseInt(required(k,v)); i++; }
                else if ("--gateway-id".equals(k)) { a.gatewayId = required(k,v); i++; }
                else if ("--bed-id".equals(k)) { a.bedId = required(k,v); i++; }
                else if ("--device-id".equals(k)) { a.deviceId = required(k,v); i++; }
                else if ("--jsonl".equals(k)) { a.jsonl = required(k,v); i++; }
                else if ("--dead-letter-jsonl".equals(k)) { a.deadLetterJsonl = required(k,v); i++; }
                else if ("--http-url".equals(k)) { a.httpUrl = required(k,v); i++; }
                else if ("--http-header".equals(k)) { addHeader(a.httpHeaders, required(k,v)); i++; }
                else if ("--http-timeout-ms".equals(k)) { a.httpTimeoutMs = Integer.parseInt(required(k,v)); i++; }
                else if ("--allow-insecure-http".equals(k)) { a.allowInsecureHttp = Boolean.parseBoolean(required(k,v)); i++; }
                else if ("--queue-capacity".equals(k)) { a.queueCapacity = Integer.parseInt(required(k,v)); i++; }
                else if ("--publish-attempts".equals(k)) { a.publishAttempts = Integer.parseInt(required(k,v)); i++; }
                else if ("--publish-retry-backoff-ms".equals(k)) { a.publishRetryBackoffMs = Long.parseLong(required(k,v)); i++; }
                else if ("--shutdown-drain-timeout-ms".equals(k)) { a.shutdownDrainTimeoutMs = Long.parseLong(required(k,v)); i++; }
                else if ("--poll-ms".equals(k)) { a.pollMs = Long.parseLong(required(k,v)); i++; }
                else if ("--reconnect-ms".equals(k)) { a.reconnectMs = Long.parseLong(required(k,v)); i++; }
                else if ("--connect-timeout-ms".equals(k)) { a.connectTimeoutMs = Long.parseLong(required(k,v)); i++; }
                else if ("--stdout".equals(k)) {
                    String sv = required(k,v);
                    if ("compact".equalsIgnoreCase(sv)) { a.stdout = true; a.stdoutMode = "compact"; }
                    else { a.stdout = Boolean.valueOf(sv); }
                    i++;
                }
                else if ("--help".equals(k)) { usageAndExit(); }
                else { throw new IllegalArgumentException("Unknown argument: " + k); }
            }
            if (a.serial != null && a.tcpHost != null) { throw new IllegalArgumentException("Choose either --serial or --tcp-host/--tcp-port, not both"); }
            if (a.tcpHost != null && a.tcpPort <= 0) { throw new IllegalArgumentException("--tcp-port must be > 0 when --tcp-host is provided"); }
            if (a.serial == null && a.tcpHost == null) { throw new IllegalArgumentException("Either --serial or --tcp-host/--tcp-port is required"); }
            if (a.serialBaud <= 0) { throw new IllegalArgumentException("--serial-baud must be > 0"); }
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
            throw new IllegalArgumentException("--serial-parity must be even, odd, or none (default: even)");
        }
        static String required(String k, String v) {
            if (v == null || v.startsWith("--")) { throw new IllegalArgumentException("Missing value for " + k); }
            return v;
        }
        static void usageAndExit() {
            System.out.println("Usage: HeadlessDraegerGatewayApp (--serial /dev/draeger_vent_01 [--serial-baud 19200] | --tcp-host <ip> --tcp-port <port>) [--device-id id] [--jsonl file] [--dead-letter-jsonl file] [--http-url url] [--allow-insecure-http true|false] [--http-header 'Authorization: Bearer TOKEN'] [--stdout true|false]");
            System.exit(0);
        }
    }
}
