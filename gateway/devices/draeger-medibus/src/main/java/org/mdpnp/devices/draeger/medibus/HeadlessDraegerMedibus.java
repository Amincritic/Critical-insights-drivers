package org.mdpnp.devices.draeger.medibus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mdpnp.devices.headless.JsonPublisher;
import org.mdpnp.devices.headless.events.CanonicalEvents;
import org.mdpnp.devices.draeger.medibus.types.RealtimeData;

public class HeadlessDraegerMedibus extends RTMedibus {
    private final String gatewayId;
    private final String bedId;
    private final String deviceId;
    private final DraegerMedibusProfile profile;
    private final JsonPublisher publisher;
    private final Map<Integer, WaveformBuffer> waveformBuffers = new HashMap<Integer, WaveformBuffer>();
    private static final int WAVEFORM_BATCH_SIZE = 16;
    private static final long WAVEFORM_BATCH_MAX_AGE_MS = 250L;
    private boolean autoConfigureDefaultRealtime;
    private boolean defaultRealtimeConfigured;

    public HeadlessDraegerMedibus(InputStream in, OutputStream out, String gatewayId, String bedId, String deviceId, JsonPublisher publisher) throws IOException {
        this(in, out, gatewayId, bedId, deviceId, DraegerMedibusProfile.V500, publisher);
    }

    public HeadlessDraegerMedibus(InputStream in, OutputStream out, String gatewayId, String bedId, String deviceId,
            DraegerMedibusProfile profile, JsonPublisher publisher) throws IOException {
        super(in, out);
        this.gatewayId = gatewayId;
        this.bedId = bedId;
        this.deviceId = deviceId;
        this.profile = profile == null ? DraegerMedibusProfile.V500 : profile;
        this.publisher = publisher;
        publishConnectivity("Connected", "Network", "Draeger MEDIBUS stream open", null);
    }

    public void enableDefaultRealtimeWaveforms() throws IOException {
        autoConfigureDefaultRealtime = true;
        defaultRealtimeConfigured = false;
        sendCommand(org.mdpnp.devices.draeger.medibus.types.Command.ReqRealtimeConfig);
    }

    @Override
    protected void receiveMeasuredData(int codepage, Data[] data) {
        if (data == null) { return; }
        for (Data d : data) {
            if (d == null) { continue; }
            Map<String, Object> event = base("vital");
            event.put("source", "measured_data_cp" + codepage);
            event.put("metric", normalizeMeasuredMetric(codepage, d.code));
            event.put("metricCode", Medibus.toString(d.code));
            addMeasuredUnitFields(event, codepage, d.code);
            event.put("rawValue", d.data);
            event.put("value", normalizeMeasuredValue(codepage, d.code, d.data));
            CanonicalEvents.numeric(event, deviceId, gatewayId, bedId,
                    String.valueOf(event.get("metric")), String.valueOf(event.get("metricCode")),
                    0, stringOrNull(event.get("unit")), (Number) event.get("value"));
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
            CanonicalEvents.numeric(event, deviceId, gatewayId, bedId,
                    String.valueOf(event.get("metric")), String.valueOf(event.get("metricCode")),
                    0, stringOrNull(event.get("unit")), (Number) event.get("value"));
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
            CanonicalEvents.alert(event, "PatientAlert", deviceId, gatewayId, bedId,
                    stringOrNull(event.get("alarmCode")), stringOrNull(event.get("message")), stringOrNull(event.get("priority")));
            publish(event);
        }
    }

    @Override
    protected void receiveDeviceSetting(Data[] data) {
        if (data == null) { return; }
        for (Data d : data) {
            if (d == null) { continue; }
            Map<String, Object> event = base("device_setting");
            event.put("source", "device_setting");
            event.put("setting", DraegerMetricMap.normalizeMetric(d.code));
            event.put("settingCode", Medibus.toString(d.code));
            event.put("rawValue", d.data);
            event.put("value", parseNumber(d.data));
            CanonicalEvents.common(event, "DeviceSetting", deviceId, gatewayId, bedId);
            event.put("identifier", stringOrNull(event.get("settingCode")));
            event.put("text", d.data == null ? null : d.data.trim());
            publish(event);
        }
    }

    @Override
    protected void receiveTextMessage(Data[] data) {
        if (data == null) { return; }
        for (Data d : data) {
            if (d == null) { continue; }
            Map<String, Object> event = base("text_message");
            event.put("source", "text_message");
            event.put("messageCode", Medibus.toString(d.code));
            event.put("message", d.data);
            CanonicalEvents.common(event, "TextMessage", deviceId, gatewayId, bedId);
            event.put("identifier", stringOrNull(event.get("messageCode")));
            event.put("text", d.data);
            publish(event);
        }
    }

    @Override
    protected void receiveTrendDataStatus(TrendStatus[] statuses) {
        if (statuses == null) { return; }
        Map<String, Object> event = base("trend_status");
        List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        for (TrendStatus status : statuses) {
            if (status == null) { continue; }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("codepage", Medibus.toString(status.codepage));
            item.put("metric", DraegerMetricMap.normalizeMetric(status.dataCode));
            item.put("metricCode", Medibus.toString(status.dataCode));
            item.put("sampleCount", Integer.valueOf(status.count));
            item.put("beginTimestampRaw", Long.valueOf(status.beginTimestamp));
            parameters.add(item);
        }
        event.put("parameters", parameters);
        CanonicalEvents.common(event, "TrendStatus", deviceId, gatewayId, bedId);
        publish(event);
        requestTrendSamples(statuses);
    }

    @Override
    protected void receiveTrendData(TrendSample[] samples) {
        if (samples == null) { return; }
        for (TrendSample sample : samples) {
            if (sample == null) { continue; }
            Map<String, Object> event = base("trend_sample");
            event.put("codepage", Medibus.toString(sample.codepage));
            event.put("metric", DraegerMetricMap.normalizeMetric(sample.dataCode));
            event.put("metricCode", Medibus.toString(sample.dataCode));
            addUnitFields(event, sample.dataCode);
            event.put("rawValue", sample.value);
            event.put("value", parseNumber(sample.value));
            event.put("sampleTimestampRaw", Long.valueOf(sample.timestamp));
            CanonicalEvents.numeric(event, deviceId, gatewayId, bedId,
                    String.valueOf(event.get("metric")), String.valueOf(event.get("metricCode")),
                    0, stringOrNull(event.get("unit")), (Number) event.get("value"));
            publish(event);
        }
    }

    @Override
    protected void receiveDateTime(Date date) {
        Map<String, Object> event = base("device_time");
        CanonicalEvents.common(event, "DeviceTime", deviceId, gatewayId, bedId);
        event.put("device_time", date == null ? null : date.toInstant().toString());
        publish(event);
    }

    @Override
    protected void receiveDeviceIdentification(String idNumber, String name, String revision) {
        Map<String, Object> event = base("device_identity");
        event.put("idNumber", idNumber);
        event.put("name", name);
        event.put("revision", revision);
        CanonicalEvents.deviceIdentity(event, deviceId, gatewayId, bedId, "Draeger", name, idNumber, revision, null);
        publish(event);
    }

    @Override
    public void receiveDataValue(RTDataConfig config, int multiplier, int streamIndex, Object realtimeData, double data) {
        WaveformBuffer buffer = waveformBuffers.get(Integer.valueOf(streamIndex));
        if (buffer == null) {
            buffer = new WaveformBuffer(streamIndex, realtimeData, multiplier, frequencyHz(config));
            waveformBuffers.put(Integer.valueOf(streamIndex), buffer);
        }
        buffer.frequencyHz = frequencyHz(config);
        buffer.add(data);
        if (buffer.shouldFlush()) {
            publishWaveformBuffer(buffer);
        }
    }

    @Override
    protected void receiveRealtimeConfig(RTDataConfig[] config) {
        Map<String, Object> event = base("realtime_config");
        event.put("profile", profile.id());
        List<Map<String, Object>> streams = new ArrayList<Map<String, Object>>();
        if (config != null) {
            for (RTDataConfig c : config) {
                if (c == null) { continue; }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("metric", DraegerMetricMap.normalizeMetric(c.realtimeData));
                item.put("metricCode", Medibus.toString(c.realtimeData));
                item.put("intervalMs", Integer.valueOf(c.interval));
                item.put("min", Integer.valueOf(c.min));
                item.put("max", Integer.valueOf(c.max));
                item.put("maxbin", Integer.valueOf(c.maxbin));
                item.put("ordinal", Integer.valueOf(c.ordinal));
                streams.add(item);
            }
        }
        event.put("streams", streams);
        CanonicalEvents.common(event, "RealtimeConfig", deviceId, gatewayId, bedId);
        publish(event);
        configureDefaultRealtimeFromConfig(config);
    }

    private void configureDefaultRealtimeFromConfig(RTDataConfig[] config) {
        if (!autoConfigureDefaultRealtime || defaultRealtimeConfigured || config == null) { return; }
        List<RTTransmit> transmits = new ArrayList<RTTransmit>();
        addRealtimeIfSupported(transmits, config, RealtimeData.AirwayPressure);
        addRealtimeIfSupported(transmits, config, RealtimeData.FlowInspExp);
        addRealtimeIfSupported(transmits, config, RealtimeData.RespiratoryVolumeSinceInspBegin);
        if (transmits.isEmpty()) { return; }
        try {
            sendRTTransmissionCommand(transmits.toArray(new RTTransmit[transmits.size()]));
            defaultRealtimeConfigured = true;
        } catch (IOException e) {
            System.err.println("Draeger realtime configuration failed: " + e.getMessage());
        }
    }

    private void addRealtimeIfSupported(List<RTTransmit> transmits, RTDataConfig[] config, RealtimeData data) {
        for (RTDataConfig c : config) {
            if (c != null && data.equals(c.realtimeData)) {
                transmits.add(new RTTransmit(data, 1, c));
                return;
            }
        }
    }

    private void requestTrendSamples(TrendStatus[] statuses) {
        if (statuses == null) { return; }
        for (TrendStatus status : statuses) {
            if (status == null || status.count <= 0) { continue; }
            try {
                requestTrendData(status, Math.min(status.count, 16));
            } catch (IOException e) {
                System.err.println("Draeger trend request failed: " + e.getMessage());
            }
        }
    }

    private void publishWaveformBuffer(WaveformBuffer buffer) {
        double[] values = buffer.drain();
        if (values.length == 0) { return; }
        Map<String, Object> event = base("waveform");
        event.put("source", "realtime_waveform");
        event.put("streamIndex", Integer.valueOf(buffer.streamIndex));
        event.put("metric", DraegerMetricMap.normalizeMetric(buffer.realtimeData));
        event.put("metricCode", Medibus.toString(buffer.realtimeData));
        addUnitFields(event, buffer.realtimeData);
        event.put("length", Integer.valueOf(values.length));
        event.put("values", values);
        event.put("multiplier", Integer.valueOf(buffer.multiplier));
        event.put("frequency", buffer.frequencyHz == null ? Integer.valueOf(100) : buffer.frequencyHz);
        CanonicalEvents.sampleArray(event, deviceId, gatewayId, bedId,
                stringOrNull(event.get("metric")), stringOrNull(event.get("metricCode")),
                buffer.streamIndex, stringOrNull(event.get("unit")), (Integer) event.get("frequency"), values);
        publish(event);
    }

    private Integer frequencyHz(RTDataConfig config) {
        if (config == null || config.interval <= 0) { return Integer.valueOf(100); }
        return Integer.valueOf(Math.max(1, Math.round(1000.0f / config.interval)));
    }

    private void addUnitFields(Map<String, Object> event, Object code) {
        String unit = DraegerMetricMap.normalizeUnit(code);
        event.put("unit", unit);
        event.put("unitCode", code == null ? null : Medibus.toString(code));
    }

    private void addMeasuredUnitFields(Map<String, Object> event, int codepage, Object code) {
        event.put("unit", normalizeMeasuredUnit(codepage, code));
        event.put("unitCode", code == null ? null : Medibus.toString(code));
    }

    private String normalizeMeasuredMetric(int codepage, Object code) {
        String metric = DraegerMetricMap.normalizeMetric(code);
        if ("TidalVolumeFrac".equals(metric)) { return "TidalVolume"; }
        if ("RespiratoryMinuteVolumeFrac".equals(metric)) { return "RespiratoryMinuteVolume"; }
        if ("SpontaneousRespiratoryRate".equals(metric)) { return "RespiratoryRate"; }
        if (codepage == 2 && "d6".equalsIgnoreCase(metric)) { return "RespiratoryRate"; }
        return metric;
    }

    private String normalizeMeasuredUnit(int codepage, Object code) {
        String metric = normalizeMeasuredMetric(codepage, code);
        if ("TidalVolume".equals(metric)) { return "mL"; }
        if ("RespiratoryMinuteVolume".equals(metric)) { return "L/min"; }
        if ("RespiratoryRate".equals(metric)) { return "1/min"; }
        if ("InspO2".equals(metric) || metric.indexOf("O2") >= 0) { return "%"; }
        if ("Compliance".equals(metric)) { return "mL/mbar"; }
        if ("AirwayTemperature".equals(metric)) { return "degC"; }
        if (metric.indexOf("Pressure") >= 0 || metric.indexOf("PEEP") >= 0) { return "cmH2O"; }
        return DraegerMetricMap.normalizeUnit(code);
    }

    private Number normalizeMeasuredValue(int codepage, Object code, String rawValue) {
        Number parsed = parseNumber(rawValue);
        if (parsed == null) { return null; }
        String metric = normalizeMeasuredMetric(codepage, code);
        if ("TidalVolume".equals(metric) && rawValue != null && rawValue.indexOf('.') >= 0) {
            return Double.valueOf(parsed.doubleValue() * 1000.0d);
        }
        if ("RespiratoryMinuteVolume".equals(metric) && rawValue != null && rawValue.indexOf('.') < 0) {
            return Double.valueOf(parsed.doubleValue() / 10.0d);
        }
        return parsed;
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

    private void publishConnectivity(String state, String type, String info, String comPort) {
        Map<String, Object> event = base("device_connectivity");
        CanonicalEvents.deviceConnectivity(event, deviceId, gatewayId, bedId, state, type, info, new String[0], comPort);
        publish(event);
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

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class WaveformBuffer {
        private final int streamIndex;
        private final Object realtimeData;
        private final int multiplier;
        private Integer frequencyHz;
        private final List<Double> values = new ArrayList<Double>();
        private long startedAtMs = System.currentTimeMillis();

        WaveformBuffer(int streamIndex, Object realtimeData, int multiplier, Integer frequencyHz) {
            this.streamIndex = streamIndex;
            this.realtimeData = realtimeData;
            this.multiplier = multiplier;
            this.frequencyHz = frequencyHz;
        }

        void add(double value) {
            if (values.isEmpty()) { startedAtMs = System.currentTimeMillis(); }
            values.add(Double.valueOf(value));
        }

        boolean shouldFlush() {
            return values.size() >= WAVEFORM_BATCH_SIZE || (!values.isEmpty() && System.currentTimeMillis() - startedAtMs >= WAVEFORM_BATCH_MAX_AGE_MS);
        }

        double[] drain() {
            double[] out = new double[values.size()];
            for (int i = 0; i < values.size(); i++) { out[i] = values.get(i).doubleValue(); }
            values.clear();
            startedAtMs = System.currentTimeMillis();
            return out;
        }
    }
}
