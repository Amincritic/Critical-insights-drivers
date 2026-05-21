package org.mdpnp.devices.philips.intellivue.headless;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import org.mdpnp.devices.philips.intellivue.RS232Adapter;

/**
 * Headless Philips IntelliVue gateway for MIB/RS232.
 *
 * OpenICE's Philips parser expects UDP-style datagrams. The original OpenICE
 * RS232Adapter translates between Philips MIB/RS232 serial frames and a local
 * loopback UDP datagram pair. This app wires that serial bridge into the same
 * headless JSON publisher used by the LAN/UDP gateway.
 */
public final class HeadlessPhilipsSerialGatewayApp {
    private HeadlessPhilipsSerialGatewayApp() { }

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

        System.err.println("Philips MIB/RS232 headless gateway supervisor started: serial=" + a.serial + " deviceId=" + a.deviceId);
        System.err.println("Serial settings: 115200 baud, 8 data bits, no parity, 1 stop bit, no flow control");
        while (running.get()) {
            RS232Adapter serialBridge = null;
            DatagramChannel parserChannel = null;
            try {
                NetworkLoop networkLoop = new NetworkLoop();
                ThreadGroup threadGroup = new ThreadGroup("philips-mib-rs232");
                int[] ports = getAvailablePorts(2);
                InetSocketAddress serialSide = new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[0]);
                InetSocketAddress parserSide = new InetSocketAddress(InetAddress.getLoopbackAddress(), ports[1]);

                serialBridge = new RS232Adapter(a.serial, serialSide, parserSide, threadGroup, networkLoop);

                HeadlessPhilipsIntellivue adapter = new HeadlessPhilipsIntellivue(
                        a.gatewayId,
                        a.bedId,
                        a.deviceId,
                        "philips_mib_rs232",
                        queued);

                parserChannel = DatagramChannel.open();
                parserChannel.configureBlocking(false);
                parserChannel.socket().setReuseAddress(true);
                parserChannel.bind(parserSide);
                parserChannel.connect(serialSide);
                networkLoop.register(adapter, parserChannel);

                System.err.println("Philips MIB/RS232 adapter connected: serial=" + a.serial + " deviceId=" + a.deviceId);
                networkLoop.runLoop();
            } catch (Exception e) {
                if (running.get()) { System.err.println("Philips MIB/RS232 network loop stopped: " + e.getMessage() + "; retrying in " + a.reconnectMs + " ms"); }
            } finally {
                try { if (serialBridge != null) { serialBridge.shutdown(); } } catch (Exception ignored) { }
                try { if (parserChannel != null) { parserChannel.close(); } } catch (Exception ignored) { }
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

    private static int[] getAvailablePorts(int count) throws Exception {
        int[] ports = new int[count];
        for (int i = 0; i < count; i++) {
            ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            ports[i] = ss.getLocalPort();
            ss.close();
        }
        return ports;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static final class Args {
        String serial;
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
                if ("--serial".equals(k)) { a.serial = required(k, v); i++; }
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
            if (a.serial == null) { throw new IllegalArgumentException("Missing required argument: --serial <device>, for example /dev/ttyUSB0 or /dev/philips_monitor_01"); }
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
            System.out.println("Usage: HeadlessPhilipsSerialGatewayApp --serial <device> [--device-id id] [--jsonl file] [--dead-letter-jsonl file] [--http-url url] [--allow-insecure-http true|false] [--http-header 'Authorization: Bearer TOKEN'] [--stdout true|false]");
            System.out.println("Example: ./gradlew :devices:philips:runHeadlessPhilipsSerial --args=\"--serial /dev/philips_monitor_01 --gateway-id jetson_nicu_01 --bed-id bed_12 --device-id philips_monitor_01 --jsonl /tmp/philips-events.jsonl --dead-letter-jsonl /tmp/philips-dead-letter.jsonl\"");
            System.out.println("Serial settings are fixed by OpenICE RS232Adapter: 115200, 8N1, no flow control.");
            System.exit(0);
        }
    }
}
