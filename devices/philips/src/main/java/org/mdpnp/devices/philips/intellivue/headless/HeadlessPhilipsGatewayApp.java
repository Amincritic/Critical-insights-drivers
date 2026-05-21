package org.mdpnp.devices.philips.intellivue.headless;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.devices.headless.FileJsonPublisher;
import org.mdpnp.devices.headless.HttpJsonPublisher;
import org.mdpnp.devices.headless.MultiJsonPublisher;
import org.mdpnp.devices.headless.QueuedJsonPublisher;
import org.mdpnp.devices.headless.StdoutJsonPublisher;
import org.mdpnp.devices.net.NetworkLoop;
import org.mdpnp.devices.philips.intellivue.Intellivue;

public final class HeadlessPhilipsGatewayApp {
    private HeadlessPhilipsGatewayApp() { }

    public static void main(String[] args) throws Exception {
        final Args a = Args.parse(args);
        final MultiJsonPublisher sinks = buildSinks(a.stdout, a.jsonl, a.httpUrl, a.httpTimeoutMs, a.httpHeaders, a.allowInsecureHttp);
        final QueuedJsonPublisher queued = buildQueue(sinks, a);
        final AtomicBoolean running = new AtomicBoolean(true);
        queued.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                running.set(false);
                try { queued.close(); } catch (Exception ignored) { }
            }
        }));

        System.err.println("Philips headless gateway supervisor started: host=" + a.host + ":" + a.port + " deviceId=" + a.deviceId);
        while (running.get()) {
            DatagramChannel channel = null;
            try {
                HeadlessPhilipsIntellivue adapter = new HeadlessPhilipsIntellivue(a.gatewayId, a.bedId, a.deviceId, queued);
                channel = DatagramChannel.open();
                channel.configureBlocking(false);
                channel.socket().setReuseAddress(true);
                channel.socket().bind(new InetSocketAddress(a.localPort));
                channel.connect(new InetSocketAddress(a.host, a.port));

                NetworkLoop networkLoop = new NetworkLoop();
                networkLoop.register(adapter, channel);
                System.err.println("Philips LAN/UDP adapter connected: host=" + a.host + ":" + a.port + " localPort=" + a.localPort);
                networkLoop.runLoop();
            } catch (Exception e) {
                if (running.get()) { System.err.println("Philips network loop stopped: " + e.getMessage() + "; retrying in " + a.reconnectMs + " ms"); }
            } finally {
                try { if (channel != null) { channel.close(); } } catch (Exception ignored) { }
            }
            if (running.get()) { sleepQuietly(a.reconnectMs); }
        }
    }

    private static MultiJsonPublisher buildSinks(Boolean stdout, String jsonl, String httpUrl, int httpTimeoutMs, Map<String, String> headers, boolean allowInsecureHttp) throws Exception {
        MultiJsonPublisher sinks = new MultiJsonPublisher();
        boolean enableStdout = stdout != null ? stdout.booleanValue() : (jsonl == null && httpUrl == null);
        if (enableStdout) { sinks.add(new StdoutJsonPublisher()); }
        if (jsonl != null) { sinks.add(new FileJsonPublisher(Paths.get(jsonl))); }
        if (httpUrl != null) { sinks.add(new HttpJsonPublisher(httpUrl, httpTimeoutMs, headers, allowInsecureHttp)); }
        return sinks;
    }

    private static QueuedJsonPublisher buildQueue(MultiJsonPublisher sinks, Args a) {
        Path deadLetter = a.deadLetterJsonl == null ? null : Paths.get(a.deadLetterJsonl);
        return new QueuedJsonPublisher(sinks, a.queueCapacity, a.publishAttempts, a.publishRetryBackoffMs, a.shutdownDrainTimeoutMs, deadLetter);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static final class Args {
        String host;
        int port = Intellivue.DEFAULT_UNICAST_PORT;
        int localPort = Intellivue.DEFAULT_UNICAST_PORT;
        String gatewayId = "jetson_nicu_01";
        String bedId = "bed_01";
        String deviceId = "philips_monitor_01";
        String jsonl;
        String deadLetterJsonl;
        String httpUrl;
        Map<String, String> httpHeaders = new LinkedHashMap<String, String>();
        int httpTimeoutMs = 3000;
        boolean allowInsecureHttp = false;
        int queueCapacity = 10000;
        int publishAttempts = 5;
        long publishRetryBackoffMs = 500L;
        long shutdownDrainTimeoutMs = 30000L;
        long reconnectMs = 5000L;
        Boolean stdout;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String k = args[i];
                String v = (i + 1) < args.length ? args[i + 1] : null;
                if ("--host".equals(k)) { a.host = required(k, v); i++; }
                else if ("--port".equals(k)) { a.port = Integer.parseInt(required(k, v)); i++; }
                else if ("--local-port".equals(k)) { a.localPort = Integer.parseInt(required(k, v)); i++; }
                else if ("--gateway-id".equals(k)) { a.gatewayId = required(k, v); i++; }
                else if ("--bed-id".equals(k)) { a.bedId = required(k, v); i++; }
                else if ("--device-id".equals(k)) { a.deviceId = required(k, v); i++; }
                else if ("--jsonl".equals(k)) { a.jsonl = required(k, v); i++; }
                else if ("--dead-letter-jsonl".equals(k)) { a.deadLetterJsonl = required(k, v); i++; }
                else if ("--http-url".equals(k)) { a.httpUrl = required(k, v); i++; }
                else if ("--http-header".equals(k)) { addHeader(a.httpHeaders, required(k, v)); i++; }
                else if ("--http-timeout-ms".equals(k)) { a.httpTimeoutMs = Integer.parseInt(required(k, v)); i++; }
                else if ("--allow-insecure-http".equals(k)) { a.allowInsecureHttp = Boolean.parseBoolean(required(k, v)); i++; }
                else if ("--queue-capacity".equals(k)) { a.queueCapacity = Integer.parseInt(required(k, v)); i++; }
                else if ("--publish-attempts".equals(k)) { a.publishAttempts = Integer.parseInt(required(k, v)); i++; }
                else if ("--publish-retry-backoff-ms".equals(k)) { a.publishRetryBackoffMs = Long.parseLong(required(k, v)); i++; }
                else if ("--shutdown-drain-timeout-ms".equals(k)) { a.shutdownDrainTimeoutMs = Long.parseLong(required(k, v)); i++; }
                else if ("--reconnect-ms".equals(k)) { a.reconnectMs = Long.parseLong(required(k, v)); i++; }
                else if ("--stdout".equals(k)) { a.stdout = Boolean.valueOf(required(k, v)); i++; }
                else if ("--help".equals(k)) { usageAndExit(); }
                else { throw new IllegalArgumentException("Unknown argument: " + k); }
            }
            if (a.host == null) { throw new IllegalArgumentException("Missing required argument: --host <monitor-ip>. Refusing to default to localhost for a bedside gateway."); }
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
        static String required(String k, String v) {
            if (v == null || v.startsWith("--")) { throw new IllegalArgumentException("Missing value for " + k); }
            return v;
        }
        static void usageAndExit() {
            System.out.println("Usage: HeadlessPhilipsGatewayApp --host <monitor-ip> [--port 24105] [--device-id id] [--jsonl file] [--dead-letter-jsonl file] [--http-url url] [--allow-insecure-http true|false] [--http-header 'Authorization: Bearer TOKEN'] [--stdout true|false]");
            System.exit(0);
        }
    }
}
