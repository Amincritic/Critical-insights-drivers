package org.mdpnp.simulator.replay;

import java.util.Map;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.SimulatedDevice;
import org.mdpnp.simulator.core.SimulatorDeviceFactory;

public final class CanonicalReplayDeviceFactory implements SimulatorDeviceFactory {
    @Override
    public String type() {
        return "canonical-replay";
    }

    @Override
    public SimulatedDevice create(DeviceDescriptor descriptor, Map<String, String> settings) {
        return new CanonicalReplayDevice(descriptor, settings);
    }
}
