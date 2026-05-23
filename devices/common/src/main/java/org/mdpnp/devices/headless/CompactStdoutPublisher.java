package org.mdpnp.devices.headless;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link JsonPublisher} that prints a compact one-line summary of vitals to stdout.
 *
 * Instead of raw JSON, it collects vitals and prints:
 * <pre>
 * 00:20:06 philips_mx800_01 | HR=72 bpm | SpO2=97% | ABP=120/80(93) mmHg | RR=16/min | etCO2=38 mmHg | Temp=37.0°C
 * </pre>
 *
 * Updates are printed once per second (aggregates all events within each second).
 */
public final class CompactStdoutPublisher implements JsonPublisher {

    private final Map<String, Double> values = new LinkedHashMap<>();
    private String deviceId = "";
    private String vendor = "";
    private long lastPrintMs = 0;
    private static final long PRINT_INTERVAL_MS = 1000;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public synchronized void publish(Map<String, Object> event) throws IOException {
        // Skip non-vital events
        String eventType = str(event.get("eventType"));
        if (!"vital".equals(eventType) && !"numeric".equals(eventType)) return;

        // Track device
        String did = str(event.get("deviceId"));
        if (did != null && !did.isEmpty()) deviceId = did;
        String v = str(event.get("vendor"));
        if (v != null && !v.isEmpty()) vendor = v;

        // Store value by metric
        String metric = str(event.get("metric"));
        Object val = event.get("value");
        if (metric != null && val instanceof Number) {
            String key = mapMetric(metric);
            if (key != null) {
                values.put(key, ((Number) val).doubleValue());
            }
        }

        // Print at most once per second
        long now = System.currentTimeMillis();
        if (now - lastPrintMs >= PRINT_INTERVAL_MS && !values.isEmpty()) {
            lastPrintMs = now;
            printLine();
        }
    }

    private void printLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(TIME_FMT.format(Instant.now()));
        sb.append(" ").append(deviceId);

        Double hr = values.get("HR");
        if (hr != null) sb.append(" | HR=").append(fmt0(hr)).append(" bpm");

        Double spo2 = values.get("SpO2");
        if (spo2 != null) sb.append(" | SpO2=").append(fmt0(spo2)).append("%");

        Double abpS = values.get("ABP_SYS");
        Double abpD = values.get("ABP_DIA");
        Double abpM = values.get("ABP_MEAN");
        if (abpS != null && abpD != null) {
            sb.append(" | ABP=").append(fmt0(abpS)).append("/").append(fmt0(abpD));
            if (abpM != null) sb.append("(").append(fmt0(abpM)).append(")");
            sb.append(" mmHg");
        }

        Double rr = values.get("RR");
        if (rr != null) sb.append(" | RR=").append(fmt0(rr)).append("/min");

        Double etco2 = values.get("etCO2");
        if (etco2 != null) sb.append(" | etCO2=").append(fmt0(etco2)).append(" mmHg");

        Double temp = values.get("Temp");
        if (temp != null) sb.append(" | Temp=").append(fmt1(temp)).append("°C");

        // Ventilator params
        Double paw = values.get("Paw");
        Double peepVal = values.get("PEEP");
        if (paw != null || peepVal != null) {
            sb.append(" | Paw=").append(paw != null ? fmt0(paw) : "--");
            sb.append("/PEEP=").append(peepVal != null ? fmt0(peepVal) : "--");
            sb.append(" cmH2O");
        }

        Double vt = values.get("VT");
        if (vt != null) sb.append(" | VT=").append(fmt0(vt)).append(" mL");

        Double fio2 = values.get("FiO2");
        if (fio2 != null) sb.append(" | FiO2=").append(fmt0(fio2)).append("%");

        Double nbpS = values.get("NBP_SYS");
        Double nbpD = values.get("NBP_DIA");
        Double nbpM = values.get("NBP_MEAN");
        if (nbpS != null && nbpD != null) {
            sb.append(" | NBP=").append(fmt0(nbpS)).append("/").append(fmt0(nbpD));
            if (nbpM != null) sb.append("(").append(fmt0(nbpM)).append(")");
            sb.append(" mmHg");
        }

        Double cvp = values.get("CVP");
        if (cvp != null) sb.append(" | CVP=").append(fmt0(cvp)).append(" mmHg");

        System.out.println(sb.toString());
    }

    /**
     * Map raw metric names (from both Philips and Draeger) to display keys.
     */
    private static String mapMetric(String metric) {
        if (metric == null) return null;

        // Philips metrics
        if (metric.contains("CARD_BEAT_RATE") || metric.contains("ECG_CARD")) return "HR";
        if (metric.contains("PULS_RATE") && !metric.contains("OXIM_SAT")) return "HR";
        if (metric.contains("SAT_O2") && metric.contains("PULS_OXIM")) return "SpO2";
        if (metric.contains("ABP_SYS") || metric.contains("ART_ABP_SYS")) return "ABP_SYS";
        if (metric.contains("ABP_DIA") || metric.contains("ART_ABP_DIA")) return "ABP_DIA";
        if (metric.contains("ABP_MEAN") || metric.contains("ART_ABP_MEAN")) return "ABP_MEAN";
        if (metric.contains("NONINV_SYS")) return "NBP_SYS";
        if (metric.contains("NONINV_DIA")) return "NBP_DIA";
        if (metric.contains("NONINV_MEAN")) return "NBP_MEAN";
        if (metric.contains("RESP_RATE") || metric.equals("NOM_RESP_RATE")) return "RR";
        if (metric.contains("CO2_ET") || metric.contains("AWAY_CO2")) return "etCO2";
        if (metric.contains("TEMP_BLD") || (metric.contains("TEMP") && !metric.contains("AW"))) return "Temp";
        if (metric.contains("VEN_CENT")) return "CVP";

        // Draeger metrics
        if (metric.contains("PeakBreathingPressure")) return "Paw";
        if (metric.contains("PEEP")) return "PEEP";
        if (metric.contains("TidalVolume") && !metric.contains("Frac")) return "VT";
        if (metric.contains("InspO2") || metric.contains("FiO2")) return "FiO2";
        if (metric.contains("AirwayTemperature")) return "Temp";
        if (metric.contains("SpontaneousRespiratoryRate") || metric.contains("RespiratoryRate")) return "RR";
        if (metric.contains("Compliance")) return null; // skip for compact
        if (metric.contains("Resistance")) return null;

        return null; // unknown metric — skip
    }

    private static String fmt0(double v) { return String.valueOf(Math.round(v)); }
    private static String fmt1(double v) { return String.format("%.1f", v); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
