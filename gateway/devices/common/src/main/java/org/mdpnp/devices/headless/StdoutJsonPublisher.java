package org.mdpnp.devices.headless;

import java.io.IOException;
import java.util.Map;

public final class StdoutJsonPublisher implements JsonPublisher {
    @Override
    public synchronized void publish(Map<String, Object> event) throws IOException {
        System.out.println(JsonUtil.toJson(event));
    }
}
