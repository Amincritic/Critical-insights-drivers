package org.mdpnp.simulator.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mdpnp.simulator.core.SimulatedDevice;
import org.mdpnp.simulator.core.SimulatorRegistry;

final class SimulatorManager implements AutoCloseable {
    private final Map<String, SimulatedDevice> devices = new LinkedHashMap<String, SimulatedDevice>();

    SimulatorManager(SimulatorRegistry registry, SimulatorConfig config) {
        for (RuntimeDeviceConfig deviceConfig : config.devices()) {
            SimulatedDevice device = registry.create(deviceConfig.descriptor(), deviceConfig.settings());
            devices.put(device.descriptor().id(), device);
        }
    }

    List<SimulatedDevice> devices() {
        return Collections.unmodifiableList(new ArrayList<SimulatedDevice>(devices.values()));
    }

    SimulatedDevice device(String id) {
        SimulatedDevice device = devices.get(id);
        if (device == null) {
            throw new IllegalArgumentException("Unknown device: " + id);
        }
        return device;
    }

    void startAll() throws Exception {
        for (SimulatedDevice device : devices.values()) {
            device.start();
        }
    }

    void stopAll() {
        List<SimulatedDevice> ordered = new ArrayList<SimulatedDevice>(devices.values());
        Collections.reverse(ordered);
        for (SimulatedDevice device : ordered) {
            device.stop();
        }
    }

    @Override
    public void close() {
        stopAll();
    }
}
