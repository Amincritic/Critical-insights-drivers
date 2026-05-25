package org.mdpnp.simulator.legacy;

import java.io.File;
import java.util.Map;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.SimulatedDevice;
import org.mdpnp.simulator.core.SimulatorDeviceFactory;

public final class LegacyDeviceFactory implements SimulatorDeviceFactory {
    private final String type;
    private final File workingDirectory;

    public LegacyDeviceFactory(String type, File workingDirectory) {
        this.type = type;
        this.workingDirectory = workingDirectory;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public SimulatedDevice create(DeviceDescriptor descriptor, Map<String, String> settings) {
        if ("draeger-medibus".equals(type)) {
            return LegacyJavaProcessDevice.draeger(descriptor, settings, workingDirectory);
        }
        if ("philips-intellivue".equals(type)) {
            return LegacyJavaProcessDevice.philips(descriptor, settings, workingDirectory);
        }
        throw new IllegalArgumentException("Unsupported legacy device type: " + type);
    }
}
