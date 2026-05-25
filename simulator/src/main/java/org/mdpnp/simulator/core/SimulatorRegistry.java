package org.mdpnp.simulator.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SimulatorRegistry {
    private final Map<String, SimulatorDeviceFactory> factories = new LinkedHashMap<String, SimulatorDeviceFactory>();

    public void register(SimulatorDeviceFactory factory) {
        factories.put(factory.type(), factory);
    }

    public SimulatedDevice create(DeviceDescriptor descriptor, Map<String, String> settings) {
        SimulatorDeviceFactory factory = factories.get(descriptor.type());
        if (factory == null) {
            throw new IllegalArgumentException("No simulator factory registered for type: " + descriptor.type());
        }
        return factory.create(descriptor, settings);
    }

    public Collection<String> supportedTypes() {
        return Collections.unmodifiableSet(factories.keySet());
    }
}
