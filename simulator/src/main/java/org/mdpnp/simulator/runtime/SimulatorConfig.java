package org.mdpnp.simulator.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.mdpnp.simulator.core.DeviceDescriptor;

final class SimulatorConfig {
    private final List<RuntimeDeviceConfig> devices;

    private SimulatorConfig(List<RuntimeDeviceConfig> devices) {
        this.devices = devices;
    }

    List<RuntimeDeviceConfig> devices() {
        return devices;
    }

    static SimulatorConfig load(File file) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }

        Set<String> ids = new LinkedHashSet<String>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("device.")) {
                String rest = key.substring("device.".length());
                int idx = rest.indexOf('.');
                if (idx > 0) {
                    ids.add(rest.substring(0, idx));
                }
            }
        }

        List<RuntimeDeviceConfig> devices = new ArrayList<RuntimeDeviceConfig>();
        for (String id : ids) {
            String prefix = "device." + id + ".";
            Map<String, String> transport = new LinkedHashMap<String, String>();
            Map<String, String> settings = new LinkedHashMap<String, String>();
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String name = key.substring(prefix.length());
                String value = props.getProperty(key).trim();
                if (name.startsWith("transport.")) {
                    transport.put(name.substring("transport.".length()), value);
                } else {
                    settings.put(name, value);
                }
            }
            DeviceDescriptor descriptor = new DeviceDescriptor(
                    id,
                    required(settings, "type", id),
                    settings.get("vendor"),
                    settings.get("model"),
                    transport);
            devices.add(new RuntimeDeviceConfig(descriptor, settings));
        }
        return new SimulatorConfig(devices);
    }

    private static String required(Map<String, String> settings, String key, String id) {
        String value = settings.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing device." + id + "." + key);
        }
        return value;
    }
}
