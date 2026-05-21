package org.mdpnp.devices.headless;

import java.io.IOException;
import java.util.Map;

public interface JsonPublisher extends AutoCloseable {
    void publish(Map<String, Object> event) throws IOException;
    @Override default void close() throws IOException { }
}
