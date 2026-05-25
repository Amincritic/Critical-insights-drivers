package org.mdpnp.simulator.runtime;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.simulator.core.SimulatorRegistry;
import org.mdpnp.simulator.legacy.LegacyDeviceFactory;
import org.mdpnp.simulator.replay.CanonicalReplayDeviceFactory;

public final class SimulatorRuntimeApp {
    private SimulatorRuntimeApp() { }

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        File workingDirectory = parsed.workingDirectory.getCanonicalFile();
        SimulatorConfig config = SimulatorConfig.load(parsed.configFile);

        SimulatorRegistry registry = new SimulatorRegistry();
        registry.register(new LegacyDeviceFactory("draeger-medibus", workingDirectory));
        registry.register(new LegacyDeviceFactory("philips-intellivue", workingDirectory));
        registry.register(new CanonicalReplayDeviceFactory());

        final SimulatorManager manager = new SimulatorManager(registry, config);
        final SimulatorApiServer api = new SimulatorApiServer(parsed.apiPort, manager);
        final AtomicBoolean running = new AtomicBoolean(true);
        final CountDownLatch shutdown = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                if (running.compareAndSet(true, false)) {
                    try { api.close(); } catch (Exception ignored) { }
                    try { manager.close(); } catch (Exception ignored) { }
                    shutdown.countDown();
                }
            }
        }, "simulator-shutdown"));

        if (parsed.autoStart) {
            manager.startAll();
        }
        api.start();
        System.err.println("Simulator runtime API: http://localhost:" + parsed.apiPort);
        System.err.println("Loaded devices: " + manager.devices().size());
        shutdown.await();
    }

    private static final class Args {
        File configFile = new File("simulator/config/local.properties");
        File workingDirectory = new File(".");
        int apiPort = 9090;
        boolean autoStart = true;

        static Args parse(String[] args) {
            Args parsed = new Args();
            for (int i = 0; i < args.length; i++) {
                String k = args[i];
                String v = (i + 1) < args.length ? args[i + 1] : null;
                if ("--config".equals(k)) {
                    parsed.configFile = new File(required(k, v));
                    i++;
                } else if ("--working-dir".equals(k)) {
                    parsed.workingDirectory = new File(required(k, v));
                    i++;
                } else if ("--gateway-dir".equals(k)) {
                    // Backward compatible no-op. Simulators now live in this module.
                    i++;
                } else if ("--api-port".equals(k)) {
                    parsed.apiPort = Integer.parseInt(required(k, v));
                    i++;
                } else if ("--no-autostart".equals(k)) {
                    parsed.autoStart = false;
                } else if ("--help".equals(k)) {
                    usageAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + k);
                }
            }
            return parsed;
        }

        private static String required(String key, String value) {
            if (value == null || value.startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            return value;
        }

        private static void usageAndExit() {
            System.out.println("Usage: simulator [--config simulator/config/local.properties] [--working-dir .] [--api-port 9090] [--no-autostart]");
            System.exit(0);
        }
    }
}
