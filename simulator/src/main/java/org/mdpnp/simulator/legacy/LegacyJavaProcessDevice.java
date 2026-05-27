package org.mdpnp.simulator.legacy;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.DeviceStatus;
import org.mdpnp.simulator.core.SimulatedDevice;

public final class LegacyJavaProcessDevice implements SimulatedDevice {
    private final DeviceDescriptor descriptor;
    private final String mainClass;
    private final List<String> args;
    private final File workingDirectory;
    private final String classpath;
    private final Consumer<String> logSink;
    private final AtomicReference<DeviceStatus> status = new AtomicReference<DeviceStatus>(DeviceStatus.STOPPED);
    private volatile Process process;
    private volatile String lastMessage = "";

    public LegacyJavaProcessDevice(DeviceDescriptor descriptor, String mainClass, List<String> args, File workingDirectory) {
        this(descriptor, mainClass, args, workingDirectory, null);
    }

    public LegacyJavaProcessDevice(DeviceDescriptor descriptor, String mainClass, List<String> args, File workingDirectory, Consumer<String> logSink) {
        this.descriptor = descriptor;
        this.mainClass = mainClass;
        this.args = Collections.unmodifiableList(new ArrayList<String>(args));
        this.workingDirectory = workingDirectory;
        this.classpath = System.getProperty("java.class.path", ".");
        this.logSink = logSink;
    }

    @Override
    public DeviceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        Process current = process;
        if (current != null && status.get() == DeviceStatus.RUNNING && !current.isAlive()) {
            status.set(DeviceStatus.FAILED);
            lastMessage = "Process exited with code " + current.exitValue();
        }
        return status.get();
    }

    @Override
    public String lastMessage() {
        status();
        return lastMessage;
    }

    @Override
    public synchronized void start() throws Exception {
        if (status() == DeviceStatus.RUNNING || status() == DeviceStatus.STARTING) {
            return;
        }
        status.set(DeviceStatus.STARTING);

        List<String> command = new ArrayList<String>();
        command.add(findJava());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }
        pb.redirectErrorStream(true);
        process = pb.start();
        status.set(DeviceStatus.RUNNING);
        lastMessage = "Started " + mainClass + " " + args;
        startLogReader(process);
    }

    @Override
    public synchronized void stop() {
        Process current = process;
        if (current == null) {
            status.set(DeviceStatus.STOPPED);
            return;
        }
        status.set(DeviceStatus.STOPPING);
        current.destroy();
        try {
            if (!current.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
                current.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process = null;
            status.set(DeviceStatus.STOPPED);
            lastMessage = "Stopped";
        }
    }

    private void startLogReader(final Process started) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastMessage = line;
                        String message = "simulator[" + descriptor.id() + "] " + line;
                        if (logSink != null) {
                            logSink.accept(line);
                        } else {
                            System.err.println(message);
                        }
                    }
                } catch (Exception e) {
                    lastMessage = e.getMessage();
                }
            }
        }, "sim-log-" + descriptor.id());
        t.setDaemon(true);
        t.start();
    }

    private static String findJava() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return javaHome + File.separator + "bin" + File.separator + "java";
        }
        return "java";
    }

    public static LegacyJavaProcessDevice draeger(DeviceDescriptor descriptor, Map<String, String> settings, File workingDirectory) {
        return draeger(descriptor, settings, workingDirectory, null);
    }

    public static LegacyJavaProcessDevice draeger(DeviceDescriptor descriptor, Map<String, String> settings, File workingDirectory, Consumer<String> logSink) {
        String transportType = setting(settings, descriptor.transport(), "type", "tcp");
        String model = setting(settings, descriptor.transport(), "model", "evita");
        List<String> args = new ArrayList<String>();
        if ("serial".equalsIgnoreCase(transportType) || "rs232".equalsIgnoreCase(transportType)) {
            args.add("--serial");
            args.add(requiredSetting(settings, descriptor.transport(), "port", descriptor.id()));
            addOptionalArg(args, settings, "control-file", "--control-file");
        } else {
            String port = setting(settings, descriptor.transport(), "port", "9100");
            args.add("--tcp");
            args.add(port);
        }
        args.add("--model");
        args.add(model);
        return new LegacyJavaProcessDevice(descriptor, "org.mdpnp.simulator.launcher.MedibusSimulatorV2Launcher", args, workingDirectory, logSink);
    }

    public static LegacyJavaProcessDevice philips(DeviceDescriptor descriptor, Map<String, String> settings, File workingDirectory) {
        return philips(descriptor, settings, workingDirectory, null);
    }

    public static LegacyJavaProcessDevice philips(DeviceDescriptor descriptor, Map<String, String> settings, File workingDirectory, Consumer<String> logSink) {
        String transportType = setting(settings, descriptor.transport(), "type", "udp");
        List<String> args = new ArrayList<String>();
        if ("serial".equalsIgnoreCase(transportType) || "rs232".equalsIgnoreCase(transportType) || "mib-rs232".equalsIgnoreCase(transportType)) {
            args.add("--serial");
            args.add(requiredSetting(settings, descriptor.transport(), "port", descriptor.id()));
            addOptionalArg(args, settings, "control-file", "--control-file");
            return new LegacyJavaProcessDevice(descriptor, "org.mdpnp.simulator.launcher.IntellivueSerialSimulatorLauncher", args, workingDirectory, logSink);
        } else {
            String port = setting(settings, descriptor.transport(), "port", "24105");
            args.add("--port");
            args.add(port);
            addOptionalArg(args, settings, "patient", "--patient");
            addOptionalArg(args, settings, "patient-id", "--patient-id");
            addOptionalArg(args, settings, "waves", "--waves");
            return new LegacyJavaProcessDevice(descriptor, "org.mdpnp.simulator.launcher.IntellivueSimulatorV2Launcher", args, workingDirectory, logSink);
        }
    }

    private static void addOptionalArg(List<String> args, Map<String, String> settings, String key, String argName) {
        String value = settings.get(key);
        if (value != null && !value.trim().isEmpty()) {
            args.add(argName);
            args.add(value.trim());
        }
    }

    private static String setting(Map<String, String> settings, Map<String, String> transport, String key, String fallback) {
        if (transport.containsKey(key)) {
            return transport.get(key);
        }
        if (settings.containsKey(key)) {
            return settings.get(key);
        }
        return fallback;
    }

    private static String requiredSetting(Map<String, String> settings, Map<String, String> transport, String key, String id) {
        String value = setting(settings, transport, key, null);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing serial port for device." + id + ".transport." + key);
        }
        return value.trim();
    }
}
