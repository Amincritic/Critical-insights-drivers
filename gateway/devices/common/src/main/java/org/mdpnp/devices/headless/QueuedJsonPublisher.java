package org.mdpnp.devices.headless;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mdpnp.devices.headless.events.CanonicalEventValidator;

public final class QueuedJsonPublisher implements JsonPublisher, Runnable {
    private static final int DEFAULT_MAX_PUBLISH_ATTEMPTS = 5;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 500L;
    private static final long DEFAULT_SHUTDOWN_DRAIN_TIMEOUT_MS = 30000L;

    private final BlockingQueue<Map<String, Object>> queue;
    private final JsonPublisher downstream;
    private final int maxPublishAttempts;
    private final long retryBackoffMs;
    private final long shutdownDrainTimeoutMs;
    private final Path deadLetterJsonl;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private Thread worker;

    public QueuedJsonPublisher(JsonPublisher downstream, int capacity) {
        this(downstream, capacity, DEFAULT_MAX_PUBLISH_ATTEMPTS, DEFAULT_RETRY_BACKOFF_MS,
                DEFAULT_SHUTDOWN_DRAIN_TIMEOUT_MS, null);
    }

    public QueuedJsonPublisher(JsonPublisher downstream, int capacity, int maxPublishAttempts,
                               long retryBackoffMs, long shutdownDrainTimeoutMs, Path deadLetterJsonl) {
        if (capacity <= 0) { throw new IllegalArgumentException("capacity must be > 0"); }
        if (maxPublishAttempts <= 0) { throw new IllegalArgumentException("maxPublishAttempts must be > 0"); }
        if (retryBackoffMs < 0L) { throw new IllegalArgumentException("retryBackoffMs must be >= 0"); }
        if (shutdownDrainTimeoutMs <= 0L) { throw new IllegalArgumentException("shutdownDrainTimeoutMs must be > 0; Java Thread.join(0) waits forever"); }
        this.downstream = downstream;
        this.queue = new LinkedBlockingQueue<Map<String, Object>>(capacity);
        this.maxPublishAttempts = maxPublishAttempts;
        this.retryBackoffMs = retryBackoffMs;
        this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
        this.deadLetterJsonl = deadLetterJsonl;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this, "json-publisher");
            worker.setDaemon(false);
            worker.start();
        }
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        if (!accepting.get()) {
            throw new IOException("publisher is closing and no longer accepts events");
        }
        CanonicalEventValidator.annotate(event);
        try {
            if (!queue.offer(event, 5, TimeUnit.SECONDS)) {
                throw new IOException("event queue full");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while enqueueing event", e);
        }
    }

    @Override
    public void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Map<String, Object> event = queue.poll(1, TimeUnit.SECONDS);
                if (event != null) { publishWithRetry(event); }
            } catch (InterruptedException e) {
                // close() interrupts the worker so it wakes quickly. Keep draining until queue is empty.
                if (running.get()) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void publishWithRetry(Map<String, Object> event) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxPublishAttempts; attempt++) {
            try {
                downstream.publish(event);
                return;
            } catch (Exception e) {
                last = e;
                System.err.println("Publish attempt " + attempt + "/" + maxPublishAttempts + " failed: " + e.toString());
                if (attempt < maxPublishAttempts) { sleepQuietly(backoffDelayMs(retryBackoffMs, attempt)); }
            }
        }
        writeDeadLetter(event, last);
    }

    private void writeDeadLetter(Map<String, Object> event, Exception failure) {
        if (deadLetterJsonl == null) {
            System.err.println("Dropping event after failed publish attempts; no dead-letter JSONL configured");
            return;
        }
        try {
            Path parent = deadLetterJsonl.getParent();
            if (parent != null) { Files.createDirectories(parent); }
            Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
            wrapper.put("deadLetterTimestamp", Instant.now().toString());
            wrapper.put("failure", failure == null ? null : failure.toString());
            wrapper.put("event", event);
            try (BufferedWriter w = Files.newBufferedWriter(deadLetterJsonl, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                w.write(JsonUtil.toJson(wrapper));
                w.newLine();
            }
        } catch (Exception e) {
            System.err.println("Failed to write dead-letter event: " + e.toString());
        }
    }

    private static long backoffDelayMs(long baseMs, int attempt) {
        if (baseMs <= 0L || attempt <= 0) { return 0L; }
        long max = Long.MAX_VALUE / attempt;
        return baseMs > max ? Long.MAX_VALUE : baseMs * attempt;
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0L) { return; }
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() throws IOException {
        accepting.set(false);
        running.set(false);
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            try {
                w.join(shutdownDrainTimeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while draining publisher queue", e);
            }
            if (w.isAlive()) {
                throw new IOException("publisher queue did not drain within " + shutdownDrainTimeoutMs + " ms; remaining events=" + queue.size());
            }
        }
        downstream.close();
    }
}
