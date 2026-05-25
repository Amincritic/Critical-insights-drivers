package org.mdpnp.simulator.replay;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.DeviceStatus;
import org.mdpnp.simulator.core.SimulatedDevice;

public final class CanonicalReplayDevice implements SimulatedDevice {
    private final DeviceDescriptor descriptor;
    private final File sourceFile;
    private final URL targetUrl;
    private final long intervalMs;
    private final boolean loop;
    private final AtomicReference<DeviceStatus> status = new AtomicReference<DeviceStatus>(DeviceStatus.STOPPED);
    private volatile Thread worker;
    private volatile boolean running;
    private volatile String lastMessage = "";

    CanonicalReplayDevice(DeviceDescriptor descriptor, Map<String, String> settings) {
        this.descriptor = descriptor;
        try {
            this.sourceFile = new File(required(settings, "file")).getCanonicalFile();
            this.targetUrl = new URL(required(settings, "target-url"));
            this.intervalMs = Long.parseLong(settings.containsKey("interval-ms") ? settings.get("interval-ms") : "1000");
            this.loop = Boolean.parseBoolean(settings.containsKey("loop") ? settings.get("loop") : "false");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid canonical-replay config for " + descriptor.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public DeviceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        Thread current = worker;
        if (current != null && status.get() == DeviceStatus.RUNNING && !current.isAlive()) {
            status.set(DeviceStatus.STOPPED);
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
        if (!sourceFile.isFile()) {
            throw new IllegalArgumentException("Replay file does not exist: " + sourceFile);
        }
        final List<String> events = loadEvents(sourceFile);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Replay file has no JSON events: " + sourceFile);
        }
        running = true;
        status.set(DeviceStatus.STARTING);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                replay(events);
            }
        }, "canonical-replay-" + descriptor.id());
        worker.setDaemon(true);
        worker.start();
        status.set(DeviceStatus.RUNNING);
        lastMessage = "Replaying " + events.size() + " events to " + targetUrl;
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        status.set(DeviceStatus.STOPPED);
        lastMessage = "Stopped";
    }

    private void replay(List<String> events) {
        try {
            do {
                for (int i = 0; running && i < events.size(); i++) {
                    post(events.get(i));
                    lastMessage = "Published replay event " + (i + 1) + "/" + events.size();
                    sleep(intervalMs);
                }
            } while (running && loop);
            if (running) {
                lastMessage = "Replay complete";
            }
        } catch (Exception e) {
            lastMessage = e.toString();
            status.set(DeviceStatus.FAILED);
        } finally {
            running = false;
            if (status.get() == DeviceStatus.RUNNING) {
                status.set(DeviceStatus.STOPPED);
            }
        }
    }

    private void post(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int code = conn.getResponseCode();
        closeQuietly(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Replay POST failed with HTTP " + code);
        }
    }

    private static List<String> loadEvents(File file) throws Exception {
        List<String> events = new ArrayList<String>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    events.add(trimmed);
                }
            }
        }
        return events;
    }

    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value.trim();
    }

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
