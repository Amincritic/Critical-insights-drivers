package org.mdpnp.devices.draeger.medibus;

public final class DraegerMetricMap {
    private DraegerMetricMap() { }

    public static String normalizeMetric(Object code) {
        if (code == null) { return "unknown"; }
        return Medibus.toString(code).replace(' ', '_');
    }

    public static String normalizeUnit(Object code) {
        if (code == null) { return null; }
        String metric = Medibus.toString(code).toLowerCase();
        if (metric.contains("temp")) { return "degC"; }
        if (metric.contains("pressure") || metric.contains("paw") || metric.contains("peep") || metric.contains("pplat") || metric.contains("pmean")) { return "cmH2O"; }
        if (metric.contains("volume") || metric.contains("vt") || metric.contains("tidal")) { return "mL"; }
        if (metric.contains("minute") || metric.contains("mv")) { return "L/min"; }
        if (metric.contains("fio2") || metric.contains("o2") || metric.contains("spo2") || metric.contains("percent")) { return "%"; }
        if (metric.contains("rate") || metric.contains("rr") || metric.contains("freq")) { return "1/min"; }
        return null;
    }
}
