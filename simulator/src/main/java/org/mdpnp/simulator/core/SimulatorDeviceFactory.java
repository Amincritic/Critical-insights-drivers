package org.mdpnp.simulator.core;

import java.util.Map;

public interface SimulatorDeviceFactory {
    String type();
    SimulatedDevice create(DeviceDescriptor descriptor, Map<String, String> settings);
}
