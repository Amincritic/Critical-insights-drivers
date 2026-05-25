package org.mdpnp.simulator.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mdpnp.simulator.core.DeviceDescriptor;

final class RuntimeDeviceConfig {
    private final DeviceDescriptor descriptor;
    private final Map<String, String> settings;

    RuntimeDeviceConfig(DeviceDescriptor descriptor, Map<String, String> settings) {
        this.descriptor = descriptor;
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<String, String>(settings));
    }

    DeviceDescriptor descriptor() {
        return descriptor;
    }

    Map<String, String> settings() {
        return settings;
    }
}
