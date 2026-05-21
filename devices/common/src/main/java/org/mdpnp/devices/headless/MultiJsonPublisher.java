package org.mdpnp.devices.headless;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fan-out publisher for multiple JSON sinks.
 *
 * Important retry semantics:
 * QueuedJsonPublisher retries the whole downstream publisher when publish()
 * throws. If one sink has already succeeded and another sink fails, throwing
 * here would make the outer queue replay the event into the successful sink,
 * creating duplicate JSONL/stdout events. Therefore partial failures are logged
 * but not rethrown. We only throw when every configured sink failed.
 */
public final class MultiJsonPublisher implements JsonPublisher {
    private final List<JsonPublisher> publishers = new ArrayList<JsonPublisher>();

    public MultiJsonPublisher add(JsonPublisher publisher) {
        if (publisher != null) { publishers.add(publisher); }
        return this;
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        if (publishers.isEmpty()) { return; }

        IOException first = null;
        int failures = 0;
        for (JsonPublisher p : publishers) {
            try {
                p.publish(event);
            } catch (IOException e) {
                failures++;
                if (first == null) { first = e; }
                System.err.println("JSON sink failed; event will not be replayed to already-successful sinks: " + e.getMessage());
            }
        }

        if (failures == publishers.size() && first != null) {
            throw first;
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (JsonPublisher p : publishers) {
            try { p.close(); }
            catch (IOException e) { if (first == null) { first = e; } }
        }
        if (first != null) { throw first; }
    }
}
