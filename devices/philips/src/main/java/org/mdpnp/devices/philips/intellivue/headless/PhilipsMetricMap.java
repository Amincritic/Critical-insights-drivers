package org.mdpnp.devices.philips.intellivue.headless;

import org.mdpnp.devices.philips.intellivue.data.ObservedValue;
import org.mdpnp.devices.philips.intellivue.data.OIDType;
import org.mdpnp.devices.philips.intellivue.data.UnitCode;

public final class PhilipsMetricMap {
    private PhilipsMetricMap() { }

    public static String metric(OIDType physioId) {
        if (physioId == null) { return "unknown"; }
        ObservedValue ov = ObservedValue.valueOf(physioId.getType());
        return ov == null ? physioId.toString() : ov.name();
    }

    public static String unit(OIDType unitCode) {
        if (unitCode == null) { return null; }
        UnitCode uc = UnitCode.valueOf(unitCode.getType());
        return uc == null ? unitCode.toString() : uc.name();
    }
}
