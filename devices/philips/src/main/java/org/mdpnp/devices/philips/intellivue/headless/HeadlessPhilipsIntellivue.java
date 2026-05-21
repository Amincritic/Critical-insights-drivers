package org.mdpnp.devices.philips.intellivue.headless;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mdpnp.devices.headless.JsonPublisher;
import org.mdpnp.devices.philips.intellivue.Intellivue;
import org.mdpnp.devices.philips.intellivue.action.ExtendedPollDataResult;
import org.mdpnp.devices.philips.intellivue.action.ObservationPoll;
import org.mdpnp.devices.philips.intellivue.action.SingleContextPoll;
import org.mdpnp.devices.philips.intellivue.action.SinglePollDataResult;
import org.mdpnp.devices.philips.intellivue.attribute.Attribute;
import org.mdpnp.devices.philips.intellivue.data.AttributeId;
import org.mdpnp.devices.philips.intellivue.data.CompoundNumericObservedValue;
import org.mdpnp.devices.philips.intellivue.data.NumericObservedValue;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayCompoundObservedValue;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayObservedValue;

public class HeadlessPhilipsIntellivue extends Intellivue {
    private final String gatewayId;
    private final String bedId;
    private final String deviceId;
    private final String protocolName;
    private final JsonPublisher publisher;

    public HeadlessPhilipsIntellivue(String gatewayId, String bedId, String deviceId, JsonPublisher publisher) {
        this(gatewayId, bedId, deviceId, "intellivue_udp", publisher);
    }

    public HeadlessPhilipsIntellivue(String gatewayId, String bedId, String deviceId, String protocolName, JsonPublisher publisher) {
        super();
        this.gatewayId = gatewayId;
        this.bedId = bedId;
        this.deviceId = deviceId;
        this.protocolName = protocolName;
        this.publisher = publisher;
    }

    @Override
    protected void handle(SinglePollDataResult result) {
        publishPoll("single_poll", result.getPollNumber(), result.getPollInfoList());
    }

    @Override
    protected void handle(ExtendedPollDataResult result) {
        publishPoll("extended_poll", result.getPollNumber(), result.getPollInfoList());
    }

    private void publishPoll(String source, int pollNumber, java.util.List<SingleContextPoll> contexts) {
        if (contexts == null) { return; }
        for (SingleContextPoll context : contexts) {
            if (context == null || context.getPollInfo() == null) { continue; }
            for (ObservationPoll observation : context.getPollInfo()) {
                publishObservation(source, pollNumber, context, observation);
            }
        }
    }

    private void publishObservation(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation) {
        if (observation == null || observation.getAttributes() == null) { return; }

        Attribute<NumericObservedValue> numeric = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_NU_VAL_OBS, NumericObservedValue.class);
        if (numeric != null) { publishNumeric(source, pollNumber, context, observation, numeric.getValue()); }

        Attribute<CompoundNumericObservedValue> compound = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_NU_CMPD_VAL_OBS, CompoundNumericObservedValue.class);
        if (compound != null && compound.getValue() != null) {
            for (NumericObservedValue n : compound.getValue().getList()) {
                publishNumeric(source, pollNumber, context, observation, n);
            }
        }

        Attribute<SampleArrayObservedValue> sample = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_VAL_OBS, SampleArrayObservedValue.class);
        if (sample != null) { publishSampleArray(source, pollNumber, context, observation, sample.getValue()); }

        Attribute<SampleArrayCompoundObservedValue> compoundSamples = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_CMPD_VAL_OBS, SampleArrayCompoundObservedValue.class);
        if (compoundSamples != null && compoundSamples.getValue() != null) {
            for (SampleArrayObservedValue s : compoundSamples.getValue().getList()) {
                publishSampleArray(source, pollNumber, context, observation, s);
            }
        }
    }

    private void publishNumeric(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, NumericObservedValue n) {
        if (n == null) { return; }
        Map<String, Object> event = base("vital");
        event.put("source", source);
        event.put("pollNumber", pollNumber);
        event.put("mdsContext", context == null ? null : context.getMdsContext());
        event.put("handle", observation == null ? null : String.valueOf(observation.getHandle()));
        event.put("metric", PhilipsMetricMap.metric(n.getPhysioId()));
        event.put("metricCode", n.getPhysioId() == null ? null : n.getPhysioId().toString());
        event.put("unit", PhilipsMetricMap.unit(n.getUnitCode()));
        event.put("unitCode", n.getUnitCode() == null ? null : n.getUnitCode().toString());
        event.put("value", n.getValue() == null ? null : n.getValue().getDouble());
        event.put("measurementState", String.valueOf(n.getMsmtState()));
        publish(event);
    }

    private void publishSampleArray(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, SampleArrayObservedValue s) {
        if (s == null) { return; }
        Map<String, Object> event = base("waveform");
        event.put("source", source);
        event.put("pollNumber", pollNumber);
        event.put("mdsContext", context == null ? null : context.getMdsContext());
        event.put("handle", observation == null ? null : String.valueOf(observation.getHandle()));
        event.put("metric", PhilipsMetricMap.metric(s.getPhysioId()));
        event.put("metricCode", s.getPhysioId() == null ? null : s.getPhysioId().toString());
        // SampleArrayObservedValue does not carry a unit field in this trimmed OpenICE model.
        // Keep the schema explicit and let downstream treat null as "not provided by device frame".
        event.put("unit", null);
        event.put("unitCode", null);
        event.put("length", s.getLength());
        event.put("values", trim(s.getValue(), s.getLength()));
        event.put("measurementState", String.valueOf(s.getState()));
        publish(event);
    }

    private short[] trim(short[] values, int length) {
        if (values == null) { return new short[0]; }
        int n = Math.max(0, Math.min(values.length, length));
        short[] out = new short[n];
        System.arraycopy(values, 0, out, 0, n);
        return out;
    }

    private Map<String, Object> base(String eventType) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("gatewayId", gatewayId);
        event.put("bedId", bedId);
        event.put("deviceId", deviceId);
        event.put("deviceType", "patient_monitor");
        event.put("vendor", "philips");
        event.put("protocol", protocolName);
        event.put("eventType", eventType);
        event.put("timestamp", Instant.now().toString());
        return event;
    }

    private void publish(Map<String, Object> event) {
        try {
            publisher.publish(event);
        } catch (IOException e) {
            System.err.println("Failed to publish Philips event: " + e.getMessage());
        }
    }
}
