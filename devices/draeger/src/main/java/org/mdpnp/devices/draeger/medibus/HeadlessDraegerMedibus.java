package org.mdpnp.devices.draeger.medibus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mdpnp.devices.headless.JsonPublisher;

public class HeadlessDraegerMedibus extends RTMedibus {
    private final String gatewayId;
    private final String bedId;
    private final String deviceId;
    private final JsonPublisher publisher;

    public HeadlessDraegerMedibus(InputStream in, OutputStream out, String gatewayId, String bedId, String deviceId, JsonPublisher publisher) throws IOException {
        super(in, out);
        this.gatewayId = gatewayId;
        this.bedId = bedId;
        this.deviceId = deviceId;
        this.publisher = publisher;
    }

    @Override
    protected void receiveMeasuredData(int codepage, Data[] data) {
        if (data == null) { return; }
        for (Data d : data) {
            if (d == null) { continue; }
            Map<String, Object> event = base("vital");
            event.put("source", "measured_data_cp" + codepage);
            event.put("metric", DraegerMetricMap.normalizeMetric(d.code));
            event.put("metricCode", Medibus.toString(d.code));
            addUnitFields(event, d.code);
            event.put("rawValue", d.data);
            event.put("value", parseNumber(d.data));
            publish(event);
        }
    }

    @Override
    protected void receiveLowAlarmLimits(int codepage, Data[] data) {
        publishLimit("low_alarm_limit", codepage, data);
    }

    @Override
    protected void receiveHighAlarmLimits(int codepage, Data[] data) {
        publishLimit("high_alarm_limit", codepage, data);
    }

    private void publishLimit(String eventType, int codepage, Data[] data) {
        if (data == null) { return; }
        for (Data d : data) {
            if (d == null) { continue; }
            Map<String, Object> event = base(eventType);
            event.put("source", eventType + "_cp" + codepage);
            event.put("metric", DraegerMetricMap.normalizeMetric(d.code));
            event.put("metricCode", Medibus.toString(d.code));
            addUnitFields(event, d.code);
            event.put("rawValue", d.data);
            event.put("value", parseNumber(d.data));
            publish(event);
        }
    }

    @Override
    protected void receiveAlarms(Alarm[] alarms) {
        if (alarms == null) { return; }
        for (Alarm a : alarms) {
            if (a == null) { continue; }
            Map<String, Object> event = base("alarm");
            event.put("priority", a.priority);
            event.put("alarmCode", Medibus.toString(a.alarmCode));
            event.put("message", a.alarmPhrase == null ? null : a.alarmPhrase.trim());
            publish(event);
        }
    }

    @Override
    protected void receiveDateTime(Date date) {
        Map<String, Object> event = base("device_time");
        event.put("deviceTime", date == null ? null : date.toInstant().toString());
        publish(event);
    }

    @Override
    protected void receiveDeviceIdentification(String idNumber, String name, String revision) {
        Map<String, Object> event = base("device_identity");
        event.put("idNumber", idNumber);
        event.put("name", name);
        event.put("revision", revision);
        publish(event);
    }

    @Override
    public void receiveDataValue(RTDataConfig config, int multiplier, int streamIndex, Object realtimeData, double data) {
        Map<String, Object> event = base("realtime_waveform_sample");
        event.put("streamIndex", streamIndex);
        event.put("metric", DraegerMetricMap.normalizeMetric(realtimeData));
        event.put("metricCode", Medibus.toString(realtimeData));
        addUnitFields(event, realtimeData);
        event.put("value", data);
        event.put("multiplier", multiplier);
        publish(event);
    }

    private void addUnitFields(Map<String, Object> event, Object code) {
        String unit = DraegerMetricMap.normalizeUnit(code);
        event.put("unit", unit);
        event.put("unitCode", code == null ? null : Medibus.toString(code));
    }

    private Map<String, Object> base(String eventType) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("gatewayId", gatewayId);
        event.put("bedId", bedId);
        event.put("deviceId", deviceId);
        event.put("deviceType", "ventilator");
        event.put("vendor", "draeger");
        event.put("protocol", "medibus");
        event.put("eventType", eventType);
        event.put("timestamp", Instant.now().toString());
        return event;
    }

    private Number parseNumber(String s) {
        if (s == null) { return null; }
        String t = s.trim();
        if (t.isEmpty() || "---".equals(t) || "--".equals(t)) { return null; }

        // Preserve rawValue for qualifiers, but parse the numeric portion when safe.
        // Examples: ">100" -> 100, "<5" -> 5, "+12" -> 12, "12 %" -> 12.
        t = t.replace(",", "");
        if (t.startsWith(">") || t.startsWith("<")) { t = t.substring(1).trim(); }
        int end = 0;
        while (end < t.length()) {
            char c = t.charAt(end);
            if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') { end++; }
            else { break; }
        }
        if (end == 0) { return null; }
        String numeric = t.substring(0, end);
        try {
            if (numeric.indexOf('.') >= 0) { return Double.valueOf(numeric); }
            return Integer.valueOf(numeric);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void publish(Map<String, Object> event) {
        try {
            publisher.publish(event);
        } catch (IOException e) {
            System.err.println("Failed to publish Draeger event: " + e.getMessage());
        }
    }
}
