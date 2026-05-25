package org.mdpnp.devices.philips.intellivue.headless;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mdpnp.devices.headless.JsonPublisher;
import org.mdpnp.devices.headless.events.CanonicalEvents;
import org.mdpnp.devices.philips.intellivue.Intellivue;
import org.mdpnp.devices.philips.intellivue.association.AssociationAccept;
import org.mdpnp.devices.philips.intellivue.data.ObjectClass;
import org.mdpnp.devices.philips.intellivue.action.ExtendedPollDataResult;
import org.mdpnp.devices.philips.intellivue.action.ObservationPoll;
import org.mdpnp.devices.philips.intellivue.action.SingleContextPoll;
import org.mdpnp.devices.philips.intellivue.action.SinglePollDataResult;
import org.mdpnp.devices.philips.intellivue.attribute.Attribute;
import org.mdpnp.devices.philips.intellivue.attribute.AttributeFactory;
import org.mdpnp.devices.philips.intellivue.data.AbsoluteTime;
import org.mdpnp.devices.philips.intellivue.data.AttributeId;
import org.mdpnp.devices.philips.intellivue.data.CompoundNumericObservedValue;
import org.mdpnp.devices.philips.intellivue.data.AlMonInfo;
import org.mdpnp.devices.philips.intellivue.data.DevAlarmEntry;
import org.mdpnp.devices.philips.intellivue.data.DevAlarmList;
import org.mdpnp.devices.philips.intellivue.data.EnumValue;
import org.mdpnp.devices.philips.intellivue.data.Label;
import org.mdpnp.devices.philips.intellivue.data.NumericObservedValue;
import org.mdpnp.devices.philips.intellivue.data.OIDType;
import org.mdpnp.devices.philips.intellivue.data.PatientBSAFormula;
import org.mdpnp.devices.philips.intellivue.data.PatientDemographicState;
import org.mdpnp.devices.philips.intellivue.data.PatientMeasurement;
import org.mdpnp.devices.philips.intellivue.data.PatientPacedMode;
import org.mdpnp.devices.philips.intellivue.data.PatientSex;
import org.mdpnp.devices.philips.intellivue.data.PatientType;
import org.mdpnp.devices.philips.intellivue.data.RelativeTime;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayCalibrationSpecification;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayCompoundObservedValue;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayFixedValueSpecification;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayObservedValue;
import org.mdpnp.devices.philips.intellivue.data.SampleArrayPhysiologicalRange;
import org.mdpnp.devices.philips.intellivue.data.SampleArraySpecification;
import org.mdpnp.devices.philips.intellivue.data.ScaleAndRangeSpecification;
import org.mdpnp.devices.philips.intellivue.data.UnitCode;

public class HeadlessPhilipsIntellivue extends Intellivue {
    private static final long EXTENDED_POLL_ACTIVE_MS = 30000L;
    private static final long EXTENDED_POLL_RENEW_MS = 25000L;
    private static final long PATIENT_POLL_MS = 10000L;
    private static final long KEEP_ALIVE_MS = 8000L;
    private static final long STARTUP_POLL_DELAY_MS = 500L;
    private final String gatewayId;
    private final String bedId;
    private final String deviceId;
    private final String protocolName;
    private final JsonPublisher publisher;
    private final Map<String, WaveContext> waveContexts = new HashMap<String, WaveContext>();
    private final Map<String, PollContinuity> pollContinuity = new HashMap<String, PollContinuity>();
    private static final int LEGACY_PT_NAME_GIVEN = 0x0996;
    private static final int LEGACY_PT_NAME_FAMILY = 0x0997;
    private static final int LEGACY_PT_ID = 0x0984;
    private static final int LEGACY_PT_DOB = 0x0958;
    private static final int LEGACY_PT_SEX = 0x0993;
    private static final Label[] DEFAULT_WAVEFORMS = new Label[] {
        Label.NLS_NOM_ECG_ELEC_POTL_II,
        Label.NLS_NOM_PULS_OXIM_PLETH,
        Label.NLS_NOM_PRESS_BLD_ART_ABP,
        Label.NLS_NOM_RESP
    };

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
        publishConnectivity("Connecting", transportType(), "Philips IntelliVue adapter initialized", null);
    }

    @Override
    protected void handle(SocketAddress sockaddr, AssociationAccept message) {
        System.err.println("Philips association accepted — starting periodic polling");
        publishConnectivity("Connected", transportType(), "Philips IntelliVue association accepted", null);
        publishDeviceIdentity();
        // Start a polling thread after a short post-association settle period.
        Thread poller = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(STARTUP_POLL_DELAY_MS); } catch (InterruptedException e) { return; }
                System.err.println("Philips poller started");
                configureWaveformPriorityList();
                try {
                    requestExtendedPolls();
                } catch (IOException e) {
                    System.err.println("Philips initial extended poll request failed: " + e.getMessage());
                }
                long lastExtendedRenewal = System.currentTimeMillis();
                long lastPatientPoll = 0L;
                long lastKeepAlive = 0L;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        long now = System.currentTimeMillis();
                        if (now - lastExtendedRenewal >= EXTENDED_POLL_RENEW_MS) {
                            requestExtendedPolls();
                            lastExtendedRenewal = now;
                        }
                        if (now - lastPatientPoll >= PATIENT_POLL_MS) {
                            int patientInvoke = requestSinglePoll(ObjectClass.NOM_MOC_PT_DEMOG, AttributeId.NOM_ATTR_GRP_PT_DEMOG);
                            if (patientInvoke >= 0) { System.err.println("Philips patient poll sent, invoke=" + patientInvoke); }
                            lastPatientPoll = now;
                        }
                        if (isKeepAliveNeeded() && now - lastKeepAlive >= KEEP_ALIVE_MS) {
                            int keepAliveInvoke = requestKeepAlive();
                            if (keepAliveInvoke >= 0) { System.err.println("Philips keepalive poll sent, invoke=" + keepAliveInvoke); }
                            lastKeepAlive = now;
                        }
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("Philips poll error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        }, "philips-poller");
        poller.setDaemon(true);
        poller.start();
    }

    private void requestExtendedPolls() throws IOException {
        int numericInvoke = requestExtendedPoll(ObjectClass.NOM_MOC_VMO_METRIC_NU, EXTENDED_POLL_ACTIVE_MS, AttributeId.NOM_ATTR_GRP_METRIC_VAL_OBS);
        int waveformInvoke = requestExtendedPoll(ObjectClass.NOM_MOC_VMO_METRIC_SA_RT, EXTENDED_POLL_ACTIVE_MS, AttributeId.NOM_ATTR_GRP_METRIC_VAL_OBS);
        int alarmInvoke = requestExtendedPoll(ObjectClass.NOM_MOC_VMO_AL_MON, EXTENDED_POLL_ACTIVE_MS, AttributeId.NOM_ATTR_GRP_AL_MON);
        System.err.println("Philips extended polls requested, numericInvoke=" + numericInvoke + " waveformInvoke=" + waveformInvoke + " alarmInvoke=" + alarmInvoke);
    }

    private void configureWaveformPriorityList() {
        try {
            int invoke = requestSet(null, DEFAULT_WAVEFORMS);
            System.err.println("Philips waveform priority list requested, invoke=" + invoke);
        } catch (Exception e) {
            System.err.println("Philips waveform priority setup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    protected void handle(SinglePollDataResult result) {
        publishPoll("single_poll", result.getPollNumber(), result.getPollInfoList(), null);
    }

    @Override
    protected void handle(ExtendedPollDataResult result) {
        PollMeta meta = checkExtendedContinuity(result);
        publishPoll("extended_poll", result.getPollNumber(), result.getPollInfoList(), meta);
    }

    private PollMeta checkExtendedContinuity(ExtendedPollDataResult result) {
        PollMeta meta = new PollMeta();
        meta.sequenceNumber = result.getSequenceNumber();
        meta.relativeTimeMs = result.getRelativeTime() == null ? null : Long.valueOf(result.getRelativeTime().toMilliseconds());
        meta.polledObjectType = result.getPolledObjType() == null ? null : String.valueOf(result.getPolledObjType());
        meta.polledAttributeGroup = result.getPolledAttributeGroup() == null ? null : String.valueOf(result.getPolledAttributeGroup());
        String key = meta.polledObjectType + ":" + meta.polledAttributeGroup + ":" + result.getPollNumber();
        PollContinuity prev = pollContinuity.get(key);
        if (prev != null) {
            int expectedSequence = (prev.sequenceNumber + 1) & 0xFFFF;
            if (meta.sequenceNumber != expectedSequence) {
                meta.sequenceGap = true;
                meta.expectedSequenceNumber = Integer.valueOf(expectedSequence);
                System.err.println("Philips extended poll sequence gap: key=" + key + " expected=" + expectedSequence + " actual=" + meta.sequenceNumber);
            }
            if (meta.relativeTimeMs != null && prev.relativeTimeMs != null && meta.relativeTimeMs.longValue() <= prev.relativeTimeMs.longValue()) {
                meta.timeGap = true;
                System.err.println("Philips extended poll timestamp did not advance: key=" + key + " previousMs=" + prev.relativeTimeMs + " actualMs=" + meta.relativeTimeMs);
            }
        }
        pollContinuity.put(key, new PollContinuity(meta.sequenceNumber, meta.relativeTimeMs));
        if (pollContinuity.size() > 256) {
            pollContinuity.clear();
        }
        return meta;
    }

    private void publishPoll(String source, int pollNumber, java.util.List<SingleContextPoll> contexts, PollMeta meta) {
        if (contexts == null) { return; }
        for (SingleContextPoll context : contexts) {
            if (context == null || context.getPollInfo() == null) { continue; }
            for (ObservationPoll observation : context.getPollInfo()) {
                publishObservation(source, pollNumber, context, observation, meta);
            }
        }
    }

    private void publishObservation(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, PollMeta meta) {
        if (observation == null || observation.getAttributes() == null) { return; }
        WaveContext waveContext = updateWaveContext(context, observation);

        Attribute<NumericObservedValue> numeric = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_NU_VAL_OBS, NumericObservedValue.class);
        if (numeric != null) { publishNumeric(source, pollNumber, context, observation, numeric.getValue(), meta); }

        Attribute<CompoundNumericObservedValue> compound = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_NU_CMPD_VAL_OBS, CompoundNumericObservedValue.class);
        if (compound != null && compound.getValue() != null) {
            for (NumericObservedValue n : compound.getValue().getList()) {
                publishNumeric(source, pollNumber, context, observation, n, meta);
            }
        }

        Attribute<SampleArrayObservedValue> sample = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_VAL_OBS, SampleArrayObservedValue.class);
        if (sample != null) { publishSampleArray(source, pollNumber, context, observation, sample.getValue(), waveContext, meta); }

        Attribute<SampleArrayCompoundObservedValue> compoundSamples = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_CMPD_VAL_OBS, SampleArrayCompoundObservedValue.class);
        if (compoundSamples != null && compoundSamples.getValue() != null) {
            for (SampleArrayObservedValue s : compoundSamples.getValue().getList()) {
                publishSampleArray(source, pollNumber, context, observation, s, waveContext, meta);
            }
        }

        Attribute<DevAlarmList> patientAlarms = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_AL_MON_P_AL_LIST, DevAlarmList.class);
        if (patientAlarms != null) { publishAlarmList(source, pollNumber, context, observation, "patient", patientAlarms.getValue()); }

        Attribute<DevAlarmList> technicalAlarms = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_AL_MON_T_AL_LIST, DevAlarmList.class);
        if (technicalAlarms != null) { publishAlarmList(source, pollNumber, context, observation, "technical", technicalAlarms.getValue()); }

        publishPatientDemographics(source, pollNumber, context, observation);
    }

    private WaveContext updateWaveContext(SingleContextPoll context, ObservationPoll observation) {
        String key = waveContextKey(context, observation);
        WaveContext wc = waveContexts.get(key);
        if (wc == null) {
            wc = new WaveContext();
            waveContexts.put(key, wc);
        }

        Attribute<SampleArraySpecification> spec = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_SPECN, SampleArraySpecification.class);
        if (spec != null && spec.getValue() != null) { wc.spec = spec.getValue().clone(); }

        Attribute<RelativeTime> samplePeriod = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_TIME_PD_SAMP, RelativeTime.class);
        if (samplePeriod != null && samplePeriod.getValue() != null) { wc.samplePeriodUs = Long.valueOf(samplePeriod.getValue().toMicroseconds()); }

        Attribute<ScaleAndRangeSpecification> scale = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SCALE_SPECN_I16, ScaleAndRangeSpecification.class);
        if (scale != null && scale.getValue() != null) { wc.scale = scale.getValue().clone(); }

        Attribute<EnumValue<UnitCode>> unit = enumAttribute(observation, AttributeId.NOM_ATTR_UNIT_CODE, UnitCode.class);
        if (unit != null && unit.getValue() != null && unit.getValue().getEnum() != null) {
            wc.unitCode = unit.getValue().getEnum();
        }

        Attribute<SampleArrayPhysiologicalRange> physRange = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_RANGE_PHYS_I16, SampleArrayPhysiologicalRange.class);
        if (physRange != null && physRange.getValue() != null) { wc.physRange = physRange.getValue(); }

        Attribute<SampleArrayCalibrationSpecification> calibration = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_CALIB_I16, SampleArrayCalibrationSpecification.class);
        if (calibration != null && calibration.getValue() != null) { wc.calibration = calibration.getValue(); }

        Attribute<SampleArrayFixedValueSpecification> fixedValues = observation.getAttributes().getAttribute(AttributeId.NOM_ATTR_SA_FIXED_VAL_SPECN, SampleArrayFixedValueSpecification.class);
        if (fixedValues != null && fixedValues.getValue() != null) { wc.fixedValues = fixedValues.getValue(); }

        return wc;
    }

    private <T extends org.mdpnp.devices.philips.intellivue.data.EnumMessage<T>> Attribute<EnumValue<T>> enumAttribute(
            ObservationPoll observation, AttributeId id, Class<T> enumClass) {
        try {
            Attribute<EnumValue<T>> attr = AttributeFactory.getEnumAttribute(id.asOid(), enumClass);
            return observation.getAttributes().getAttribute(attr);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void publishNumeric(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, NumericObservedValue n, PollMeta meta) {
        if (n == null) { return; }
        Map<String, Object> event = base("vital");
        addPollMeta(event, meta);
        event.put("source", source);
        event.put("pollNumber", pollNumber);
        event.put("mdsContext", context == null ? null : context.getMdsContext());
        event.put("handle", observation == null ? null : String.valueOf(observation.getHandle()));
        event.put("metric", PhilipsMetricMap.metric(n.getPhysioId()));
        event.put("metricCode", n.getPhysioId() == null ? null : n.getPhysioId().toString());
        event.put("unit", PhilipsMetricMap.unit(n.getUnitCode()));
        event.put("unitCode", n.getUnitCode() == null ? null : n.getUnitCode().toString());
        Number value = n.getValue() == null ? null : Double.valueOf(n.getValue().getDouble());
        event.put("rawDecodedValue", value);
        event.put("value", canonicalValueForState(value, n.getMsmtState()));
        event.put("measurementState", String.valueOf(n.getMsmtState()));
        addMeasurementValidity(event, n.getMsmtState());
        CanonicalEvents.numeric(event, deviceId, gatewayId, bedId,
                stringOrNull(event.get("metric")), stringOrNull(event.get("metricCode")),
                0, stringOrNull(event.get("unit")), (Number) event.get("value"));
        publish(event);
    }

    private void publishSampleArray(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, SampleArrayObservedValue s, WaveContext waveContext, PollMeta meta) {
        if (s == null) { return; }
        Object values = decodeWaveSamples(s, waveContext);
        Integer frequency = waveContext == null ? null : waveContext.frequencyHz();
        Map<String, Object> event = base("waveform");
        addPollMeta(event, meta);
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
        event.put("rawDecodedValues", values);
        event.put("values", canonicalValuesForState(values, s.getState()));
        event.put("rawBytes", trim(s.getValue(), s.getLength()));
        event.put("raw_sample_bytes", event.get("rawBytes"));
        event.put("measurementState", String.valueOf(s.getState()));
        addMeasurementValidity(event, s.getState());
        addWaveContext(event, waveContext);
        addSampleFlags(event, event.get("values"), waveContext);
        CanonicalEvents.sampleArray(event, deviceId, gatewayId, bedId,
                stringOrNull(event.get("metric")), stringOrNull(event.get("metricCode")),
                0, stringOrNull(event.get("unit")), frequency, event.get("values"));
        publish(event);
    }

    private Number canonicalValueForState(Number value, org.mdpnp.devices.philips.intellivue.data.MeasurementState state) {
        if (state == null) { return value; }
        if (state.isInvalid() || state.isUnavailable() || state.isCalibrationOngoing() || state.isTestData() || state.isDemoData()) { return null; }
        return value;
    }

    private Object canonicalValuesForState(Object values, org.mdpnp.devices.philips.intellivue.data.MeasurementState state) {
        if (state == null) { return values; }
        if (state.isInvalid() || state.isUnavailable() || state.isCalibrationOngoing() || state.isTestData() || state.isDemoData()) { return null; }
        return values;
    }

    private void addMeasurementValidity(Map<String, Object> event, org.mdpnp.devices.philips.intellivue.data.MeasurementState state) {
        if (state == null) { return; }
        event.put("measurement_state_valid", Boolean.valueOf(state.isValid()));
        event.put("measurement_invalid", Boolean.valueOf(state.isInvalid()));
        event.put("measurement_questionable", Boolean.valueOf(state.isQuestionable()));
        event.put("measurement_unavailable", Boolean.valueOf(state.isUnavailable()));
        event.put("measurement_test_data", Boolean.valueOf(state.isTestData()));
        event.put("measurement_demo_data", Boolean.valueOf(state.isDemoData()));
        event.put("data_valid", Boolean.valueOf(state.isValid() && !state.isTestData() && !state.isDemoData()));
        if (state.isInvalid()) { event.put("data_quality", "invalid"); }
        else if (state.isUnavailable()) { event.put("data_quality", "unavailable"); }
        else if (state.isQuestionable()) { event.put("data_quality", "questionable"); }
        else if (state.isCalibrationOngoing()) { event.put("data_quality", "calibration"); }
        else if (state.isTestData()) { event.put("data_quality", "test"); }
        else if (state.isDemoData()) { event.put("data_quality", "demo"); }
        else { event.put("data_quality", "valid"); }
    }

    private void addPollMeta(Map<String, Object> event, PollMeta meta) {
        if (meta == null) { return; }
        event.put("pollSequenceNumber", Integer.valueOf(meta.sequenceNumber));
        event.put("poll_sequence_number", Integer.valueOf(meta.sequenceNumber));
        if (meta.expectedSequenceNumber != null) { event.put("expected_poll_sequence_number", meta.expectedSequenceNumber); }
        if (meta.relativeTimeMs != null) {
            event.put("pollRelativeTimeMs", meta.relativeTimeMs);
            event.put("poll_relative_time_ms", meta.relativeTimeMs);
        }
        event.put("poll_sequence_gap", Boolean.valueOf(meta.sequenceGap));
        event.put("poll_time_gap", Boolean.valueOf(meta.timeGap));
        event.put("polledObjectType", meta.polledObjectType);
        event.put("polledAttributeGroup", meta.polledAttributeGroup);
    }

    private Object decodeWaveSamples(SampleArrayObservedValue s, WaveContext waveContext) {
        short[] raw = trim(s.getValue(), s.getLength());
        SampleArraySpecification spec = waveContext == null ? null : waveContext.spec;
        int sampleSize = spec == null || spec.getSampleSize() <= 0 ? 8 : spec.getSampleSize();
        int bytesPerSample = Math.max(1, (sampleSize + 7) / 8);
        int count = raw.length / bytesPerSample;
        int[] scaled = new int[count];
        int mask = significantMask(spec, sampleSize);
        for (int i = 0; i < count; i++) {
            int v = 0;
            for (int j = 0; j < bytesPerSample; j++) {
                v = (v << 8) | (raw[i * bytesPerSample + j] & 0xFF);
            }
            scaled[i] = mask > 0 ? v & mask : v;
        }
        double[] physical = toPhysicalValues(scaled, waveContext);
        return physical == null ? scaled : physical;
    }

    private int significantMask(SampleArraySpecification spec, int sampleSize) {
        if (spec == null || !spec.isExtendedValueRange()) { return 0; }
        int significantBits = spec.getSignificantBits();
        if (significantBits <= 0 || significantBits >= 31 || significantBits >= sampleSize) { return 0; }
        return (1 << significantBits) - 1;
    }

    private double[] toPhysicalValues(int[] scaled, WaveContext waveContext) {
        if (waveContext == null || waveContext.scale == null) { return null; }
        int lowerScaled = waveContext.scale.getLowerScaledValue();
        int upperScaled = waveContext.scale.getUpperScaledValue();
        double lowerAbsolute = waveContext.scale.getLowerAbsoluteValue().doubleValue();
        double upperAbsolute = waveContext.scale.getUpperAbsoluteValue().doubleValue();
        if (upperScaled == lowerScaled || Double.isNaN(lowerAbsolute) || Double.isNaN(upperAbsolute)) { return null; }
        double[] out = new double[scaled.length];
        double slope = (upperAbsolute - lowerAbsolute) / (double) (upperScaled - lowerScaled);
        for (int i = 0; i < scaled.length; i++) {
            out[i] = lowerAbsolute + ((double) scaled[i] - lowerScaled) * slope;
        }
        return out;
    }

    private void addWaveContext(Map<String, Object> event, WaveContext wc) {
        if (wc == null) { return; }
        if (wc.unitCode != null) {
            event.put("unit", PhilipsMetricMap.unit(wc.unitCode.asOID()));
            event.put("unitCode", wc.unitCode.name());
            event.put("unit_id", wc.unitCode.name());
        }
        if (wc.spec != null) {
            event.put("sampleSizeBits", Integer.valueOf(wc.spec.getSampleSize()));
            event.put("sample_size_bits", Integer.valueOf(wc.spec.getSampleSize()));
            event.put("significantBits", Integer.valueOf(wc.spec.getSignificantBits()));
            event.put("significant_bits", Integer.valueOf(wc.spec.getSignificantBits()));
            event.put("arraySize", Integer.valueOf(wc.spec.getArraySize()));
            event.put("array_size", Integer.valueOf(wc.spec.getArraySize()));
            event.put("smoothCurve", Boolean.valueOf(wc.spec.isSmoothCurve()));
            event.put("delayedCurve", Boolean.valueOf(wc.spec.isDelayedCurve()));
            event.put("staticScale", Boolean.valueOf(wc.spec.isStaticScale()));
            event.put("extendedValueRange", Boolean.valueOf(wc.spec.isExtendedValueRange()));
        }
        if (wc.samplePeriodUs != null) {
            event.put("samplePeriodUs", wc.samplePeriodUs);
            event.put("sample_period_us", wc.samplePeriodUs);
            event.put("frequency", wc.frequencyHz());
        }
        if (wc.scale != null) {
            Map<String, Object> scale = new LinkedHashMap<String, Object>();
            scale.put("lowerAbsoluteValue", Double.valueOf(wc.scale.getLowerAbsoluteValue().doubleValue()));
            scale.put("upperAbsoluteValue", Double.valueOf(wc.scale.getUpperAbsoluteValue().doubleValue()));
            scale.put("lowerScaledValue", Integer.valueOf(wc.scale.getLowerScaledValue()));
            scale.put("upperScaledValue", Integer.valueOf(wc.scale.getUpperScaledValue()));
            event.put("scaleRange", scale);
            event.put("scale_range", scale);
        }
        if (wc.physRange != null) {
            Map<String, Object> range = new LinkedHashMap<String, Object>();
            range.put("lowerScaledValue", Integer.valueOf(wc.physRange.getLowerScaledValue()));
            range.put("upperScaledValue", Integer.valueOf(wc.physRange.getUpperScaledValue()));
            event.put("physiologicalRange", range);
            event.put("physiological_range", range);
        }
        if (wc.calibration != null) {
            Map<String, Object> calibration = new LinkedHashMap<String, Object>();
            calibration.put("lowerAbsoluteValue", Double.valueOf(wc.calibration.getLowerAbsoluteValue().doubleValue()));
            calibration.put("upperAbsoluteValue", Double.valueOf(wc.calibration.getUpperAbsoluteValue().doubleValue()));
            calibration.put("lowerScaledValue", Integer.valueOf(wc.calibration.getLowerScaledValue()));
            calibration.put("upperScaledValue", Integer.valueOf(wc.calibration.getUpperScaledValue()));
            calibration.put("increment", Integer.valueOf(wc.calibration.getIncrement()));
            calibration.put("calType", Integer.valueOf(wc.calibration.getCalType()));
            event.put("calibration", calibration);
        }
        if (wc.fixedValues != null) {
            Object fixed = fixedValues(wc.fixedValues);
            event.put("fixedValues", fixed);
            event.put("fixed_values", fixed);
        }
    }

    private void addSampleFlags(Map<String, Object> event, Object values, WaveContext wc) {
        if (wc == null || wc.fixedValues == null || !(values instanceof int[])) { return; }
        Map<String, Object> flags = new LinkedHashMap<String, Object>();
        int[] samples = (int[]) values;
        for (SampleArrayFixedValueSpecification.Entry entry : wc.fixedValues.getList()) {
            if (entry.getValId() == null) { continue; }
            if (!isSampleCondition(entry)) { continue; }
            int fixed = entry.getFixedValue();
            int[] indexes = matchingSampleIndexes(samples, fixed, isMask(entry));
            if (indexes.length > 0) { flags.put(entry.getValId().name(), indexes); }
        }
        if (!flags.isEmpty()) {
            event.put("sampleFlags", flags);
            event.put("sample_flags", flags);
        }
    }

    private boolean isSampleCondition(SampleArrayFixedValueSpecification.Entry entry) {
        switch (entry.getValId()) {
        case SA_FIX_INVALID_MASK:
        case SA_FIX_PACER_MASK:
        case SA_FIX_DEFIB_MARKER_MASK:
        case SA_FIX_SATURATION:
        case SA_FIX_QRS_MASK:
            return true;
        default:
            return false;
        }
    }

    private boolean isMask(SampleArrayFixedValueSpecification.Entry entry) {
        switch (entry.getValId()) {
        case SA_FIX_INVALID_MASK:
        case SA_FIX_PACER_MASK:
        case SA_FIX_DEFIB_MARKER_MASK:
        case SA_FIX_SATURATION:
        case SA_FIX_QRS_MASK:
            return true;
        default:
            return false;
        }
    }

    private int[] matchingSampleIndexes(int[] samples, int fixed, boolean mask) {
        int count = 0;
        for (int sample : samples) {
            if (matchesFixedValue(sample, fixed, mask)) { count++; }
        }
        int[] indexes = new int[count];
        int pos = 0;
        for (int i = 0; i < samples.length; i++) {
            if (matchesFixedValue(samples[i], fixed, mask)) { indexes[pos++] = i; }
        }
        return indexes;
    }

    private boolean matchesFixedValue(int sample, int fixed, boolean mask) {
        return mask ? (sample & fixed) != 0 : sample == fixed;
    }

    private Object fixedValues(SampleArrayFixedValueSpecification fixedValues) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        for (SampleArrayFixedValueSpecification.Entry entry : fixedValues.getList()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", entry.getValId() == null ? null : entry.getValId().name());
            item.put("value", Integer.valueOf(entry.getFixedValue()));
            item.put("isMask", Boolean.valueOf(isMask(entry)));
            out.add(item);
        }
        return out;
    }

    private String waveContextKey(SingleContextPoll context, ObservationPoll observation) {
        String mds = context == null ? "0" : String.valueOf(context.getMdsContext());
        String handle = observation == null || observation.getHandle() == null ? "0" : String.valueOf(observation.getHandle().getHandle());
        return mds + ":" + handle;
    }

    private static class WaveContext {
        private SampleArraySpecification spec;
        private Long samplePeriodUs;
        private ScaleAndRangeSpecification scale;
        private UnitCode unitCode;
        private SampleArrayPhysiologicalRange physRange;
        private SampleArrayCalibrationSpecification calibration;
        private SampleArrayFixedValueSpecification fixedValues;

        private Integer frequencyHz() {
            if (samplePeriodUs == null || samplePeriodUs.longValue() <= 0L) { return null; }
            long hz = Math.round(1000000.0 / samplePeriodUs.doubleValue());
            if (hz <= 0L || hz > Integer.MAX_VALUE) { return null; }
            return Integer.valueOf((int) hz);
        }
    }

    private static class PollMeta {
        private int sequenceNumber;
        private Integer expectedSequenceNumber;
        private Long relativeTimeMs;
        private String polledObjectType;
        private String polledAttributeGroup;
        private boolean sequenceGap;
        private boolean timeGap;
    }

    private static class PollContinuity {
        private final int sequenceNumber;
        private final Long relativeTimeMs;

        private PollContinuity(int sequenceNumber, Long relativeTimeMs) {
            this.sequenceNumber = sequenceNumber;
            this.relativeTimeMs = relativeTimeMs;
        }
    }

    private short[] trim(short[] values, int length) {
        if (values == null) { return new short[0]; }
        int n = Math.max(0, Math.min(values.length, length));
        short[] out = new short[n];
        System.arraycopy(values, 0, out, 0, n);
        return out;
    }

    private void publishAlarmList(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, String alarmCategory, DevAlarmList alarms) {
        if (alarms == null || alarms.getValue() == null) { return; }
        for (DevAlarmEntry alarm : alarms.getValue()) {
            publishAlarm(source, pollNumber, context, observation, alarmCategory, alarm);
        }
    }

    private void publishAlarm(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation, String alarmCategory, DevAlarmEntry alarm) {
        if (alarm == null) { return; }
        Map<String, Object> event = base("alarm");
        event.put("source", source);
        event.put("pollNumber", pollNumber);
        event.put("mdsContext", context == null ? null : context.getMdsContext());
        event.put("handle", observation == null ? null : String.valueOf(observation.getHandle()));
        event.put("alarmCategory", alarmCategory);
        event.put("alarmSource", alarm.getAlSource() == null ? null : PhilipsMetricMap.metric(alarm.getAlSource()));
        event.put("alarmSourceCode", alarm.getAlSource() == null ? null : alarm.getAlSource().toString());
        event.put("alarmCode", alarm.getAlCode() == null ? null : alarm.getAlCode().toString());
        event.put("alarmType", alarm.getAlType() == null ? null : alarm.getAlType().toString());
        event.put("alarmState", alarm.getAlState() == null ? null : alarm.getAlState().toString());
        event.put("priority", alarmPriority(alarm));
        event.put("message", alarmMessage(alarm));
        event.put("alertInfoId", alarm.getAlertInfoId() == null ? null : Integer.valueOf(alarm.getAlertInfoId().getOid()));
        event.put("state", alarmLifecycleState(alarm));
        event.put("alert_state", event.get("state"));
        addAlarmStateFlags(event, alarm);
        addAlarmInfo(event, alarm);
        CanonicalEvents.alert(event, "technical".equals(alarmCategory) ? "TechnicalAlert" : "PatientAlert", deviceId, gatewayId, bedId,
                stringOrNull(event.get("alarmCode")), stringOrNull(event.get("message")), stringOrNull(event.get("priority")));
        publish(event);
    }

    private String alarmPriority(DevAlarmEntry alarm) {
        if (alarm == null || alarm.getAlType() == null) { return null; }
        if (alarm.getAlType().isHighPriorityPatientAlarm() || alarm.getAlType().isHighPriorityTechnicalAlarm()) { return "high"; }
        if (alarm.getAlType().isMediumPriorityPatientAlarm() || alarm.getAlType().isMediumPriorityTechnicalAlarm()) { return "medium"; }
        if (alarm.getAlType().isLowPriorityPatientAlarm() || alarm.getAlType().isLowPriorityTechnicalAlarm()) { return "low"; }
        if (alarm.getAlType().isNoAlert()) { return "none"; }
        return "unknown";
    }

    private String alarmMessage(DevAlarmEntry alarm) {
        if (alarm == null) { return null; }
        AlMonInfo info = alarm.getAlMonInfo();
        if (info != null && info.getAlText() != null) { return info.getAlText().toString(); }
        return alarm.getAlCode() == null ? null : alarm.getAlCode().toString();
    }

    private String alarmLifecycleState(DevAlarmEntry alarm) {
        if (alarm == null) { return null; }
        if (alarm.getAlType() != null && alarm.getAlType().isNoAlert()) { return "inactive"; }
        if (alarm.getAlState() == null) { return "active"; }
        if (alarm.getAlState().isAlInhibited()) { return "inhibited"; }
        if (alarm.getAlState().isAlSuspended()) { return "suspended"; }
        if (alarm.getAlState().isAlLatched()) { return "latched"; }
        if (alarm.getAlState().isAlNewAlert()) { return "active"; }
        return "active";
    }

    private void addAlarmStateFlags(Map<String, Object> event, DevAlarmEntry alarm) {
        if (alarm == null || alarm.getAlState() == null) { return; }
        event.put("alarm_inhibited", Boolean.valueOf(alarm.getAlState().isAlInhibited()));
        event.put("alarm_suspended", Boolean.valueOf(alarm.getAlState().isAlSuspended()));
        event.put("alarm_latched", Boolean.valueOf(alarm.getAlState().isAlLatched()));
        event.put("alarm_silenced_reset", Boolean.valueOf(alarm.getAlState().isAlSilencedReset()));
        event.put("alarm_device_test_mode", Boolean.valueOf(alarm.getAlState().isAlDevInTestMode()));
        event.put("alarm_device_standby", Boolean.valueOf(alarm.getAlState().isAlDevInStandby()));
        event.put("alarm_device_demo_mode", Boolean.valueOf(alarm.getAlState().isAlDevInDemoMode()));
        event.put("alarm_new_alert", Boolean.valueOf(alarm.getAlState().isAlNewAlert()));
    }

    private void addAlarmInfo(Map<String, Object> event, DevAlarmEntry alarm) {
        if (alarm == null || alarm.getAlMonInfo() == null) { return; }
        AlMonInfo info = alarm.getAlMonInfo();
        event.put("alarm_instance", Integer.valueOf(info.getAlInstNo()));
        event.put("alarm_text_id", info.getAlText() == null ? null : Long.valueOf(info.getAlText().getTextId()));
        event.put("alarm_monitor_priority", Integer.valueOf(info.getPriority()));
        if (info.getFlags() != null) {
            event.put("alarm_flags", info.getFlags().toString());
            event.put("alarm_bedside_audible", Boolean.valueOf(info.getFlags().isBedsideAudible()));
            event.put("alarm_central_audible", Boolean.valueOf(info.getFlags().isCentralAudible()));
            event.put("alarm_visual_latching", Boolean.valueOf(info.getFlags().isVisualLatching()));
            event.put("alarm_audible_latching", Boolean.valueOf(info.getFlags().isAudibleLatching()));
            event.put("alarm_short_yellow_extension", Boolean.valueOf(info.getFlags().isShortYellowExtension()));
            event.put("alarm_derived", Boolean.valueOf(info.getFlags().isDerived()));
        }
    }

    private void publishPatientDemographics(String source, int pollNumber, SingleContextPoll context, ObservationPoll observation) {
        Map<String, Object> event = base("patient_demographics");
        event.put("source", source);
        event.put("pollNumber", pollNumber);
        event.put("mdsContext", context == null ? null : context.getMdsContext());
        event.put("handle", observation == null ? null : String.valueOf(observation.getHandle()));

        boolean hasValue = false;
        boolean hasLegacyPatientAttrs = hasRawAttribute(observation, LEGACY_PT_ID) || hasRawAttribute(observation, LEGACY_PT_NAME_FAMILY);
        hasValue |= putStringAttribute(event, observation, "givenName", AttributeId.NOM_ATTR_PT_NAME_GIVEN)
                || (hasLegacyPatientAttrs && putStringAttribute(event, observation, "givenName", LEGACY_PT_NAME_GIVEN));
        hasValue |= putStringAttribute(event, observation, "familyName", AttributeId.NOM_ATTR_PT_NAME_FAMILY)
                || (hasLegacyPatientAttrs && putStringAttribute(event, observation, "familyName", LEGACY_PT_NAME_FAMILY));
        hasValue |= putStringAttribute(event, observation, "patientId", AttributeId.NOM_ATTR_PT_ID)
                || (hasLegacyPatientAttrs && putStringAttribute(event, observation, "patientId", LEGACY_PT_ID));
        hasValue |= putAbsoluteTimeAttribute(event, observation, "dateOfBirth", AttributeId.NOM_ATTR_PT_DOB)
                || (hasLegacyPatientAttrs && putAbsoluteTimeAttribute(event, observation, "dateOfBirth", LEGACY_PT_DOB));
        hasValue |= putEnumAttribute(event, observation, "sex", AttributeId.NOM_ATTR_PT_SEX, PatientSex.class)
                || (hasLegacyPatientAttrs && putEnumAttribute(event, observation, "sex", LEGACY_PT_SEX, PatientSex.class));
        hasValue |= putEnumAttribute(event, observation, "patientType", AttributeId.NOM_ATTR_PT_TYPE, PatientType.class);
        hasValue |= putEnumAttribute(event, observation, "pacedMode", AttributeId.NOM_ATTR_PT_PACED_MODE, PatientPacedMode.class);
        hasValue |= putEnumAttribute(event, observation, "demographicState", AttributeId.NOM_ATTR_PT_DEMOG_ST, PatientDemographicState.class);
        hasValue |= putEnumAttribute(event, observation, "bsaFormula", AttributeId.NOM_ATTR_PT_BSA_FORMULA, PatientBSAFormula.class);
        hasValue |= putMeasurementAttribute(event, observation, "height", AttributeId.NOM_ATTR_PT_HEIGHT);
        hasValue |= putMeasurementAttribute(event, observation, "weight", AttributeId.NOM_ATTR_PT_WEIGHT);
        hasValue |= putMeasurementAttribute(event, observation, "bodySurfaceArea", AttributeId.NOM_ATTR_PT_BSA);
        hasValue |= putMeasurementAttribute(event, observation, "age", AttributeId.NOM_ATTR_PT_AGE);

        CanonicalEvents.patientDemographics(event, deviceId, gatewayId, bedId,
                stringOrNull(event.get("patientId")), stringOrNull(event.get("givenName")), stringOrNull(event.get("familyName")));
        copyIfPresent(event, "date_of_birth", "dateOfBirth");
        copyIfPresent(event, "patient_type", "patientType");

        if (hasValue) { publish(event); }
    }

    private boolean putStringAttribute(Map<String, Object> event, ObservationPoll observation, String key, AttributeId id) {
        return putStringAttribute(event, observation, key, id.asOid());
    }

    private boolean putStringAttribute(Map<String, Object> event, ObservationPoll observation, String key, int oid) {
        return putStringAttribute(event, observation, key, OIDType.lookup(oid));
    }

    private boolean putStringAttribute(Map<String, Object> event, ObservationPoll observation, String key, OIDType oid) {
        try {
            Attribute<org.mdpnp.devices.philips.intellivue.data.String> attr =
                    observation.getAttributes().getAttribute(oid, org.mdpnp.devices.philips.intellivue.data.String.class, null);
            if (attr == null || attr.getValue() == null || attr.getValue().getString() == null || attr.getValue().getString().isEmpty()) { return false; }
            event.put(key, attr.getValue().getString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean putAbsoluteTimeAttribute(Map<String, Object> event, ObservationPoll observation, String key, AttributeId id) {
        return putAbsoluteTimeAttribute(event, observation, key, id.asOid());
    }

    private boolean putAbsoluteTimeAttribute(Map<String, Object> event, ObservationPoll observation, String key, int oid) {
        return putAbsoluteTimeAttribute(event, observation, key, OIDType.lookup(oid));
    }

    private boolean putAbsoluteTimeAttribute(Map<String, Object> event, ObservationPoll observation, String key, OIDType oid) {
        try {
            Attribute<AbsoluteTime> attr = observation.getAttributes().getAttribute(oid, AbsoluteTime.class, null);
            if (attr == null || attr.getValue() == null || attr.getValue().getDate() == null) { return false; }
            event.put(key, attr.getValue().getDate().toInstant().toString());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean putEnumAttribute(Map<String, Object> event, ObservationPoll observation, String key, AttributeId id, Class type) {
        return putEnumAttribute(event, observation, key, id.asOid(), type);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean putEnumAttribute(Map<String, Object> event, ObservationPoll observation, String key, int oid, Class type) {
        return putEnumAttribute(event, observation, key, OIDType.lookup(oid), type);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean putEnumAttribute(Map<String, Object> event, ObservationPoll observation, String key, OIDType oid, Class type) {
        try {
            Attribute attr = type == null || !type.isEnum() ? AttributeFactory.getAttribute(oid) : AttributeFactory.getEnumAttribute(oid, type);
            if (attr == null || !observation.getAttributes().get(attr) || attr.getValue() == null) { return false; }
            Object value = attr.getValue();
            if (value instanceof EnumValue) {
                Object enumValue = ((EnumValue) value).getEnum();
                if (enumValue == null) { return false; }
                event.put(key, String.valueOf(enumValue));
            } else {
                event.put(key, String.valueOf(value));
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean putMeasurementAttribute(Map<String, Object> event, ObservationPoll observation, String key, AttributeId id) {
        Attribute<PatientMeasurement> attr = observation.getAttributes().getAttribute(id, PatientMeasurement.class);
        if (attr == null || attr.getValue() == null || attr.getValue().getValue() == null) { return false; }
        double value = attr.getValue().getValue().getDouble();
        if (java.lang.Double.isNaN(value) || java.lang.Double.isInfinite(value)) { return false; }
        event.put(key, value);
        event.put(key + "Unit", PhilipsMetricMap.unit(attr.getValue().getUnitCode()));
        event.put(key + "UnitCode", attr.getValue().getUnitCode() == null ? null : attr.getValue().getUnitCode().toString());
        return true;
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

    private void publishConnectivity(String state, String type, String info, String comPort) {
        Map<String, Object> event = base("device_connectivity");
        CanonicalEvents.deviceConnectivity(event, deviceId, gatewayId, bedId, state, type, info, new String[0], comPort);
        publish(event);
    }

    private void publishDeviceIdentity() {
        Map<String, Object> event = base("device_identity");
        event.put("manufacturer", "Philips");
        event.put("model", "IntelliVue");
        CanonicalEvents.deviceIdentity(event, deviceId, gatewayId, bedId, "Philips", "IntelliVue", null, null, null);
        publish(event);
    }

    private String transportType() {
        return protocolName != null && protocolName.indexOf("mib") >= 0 ? "Serial" : "Network";
    }

    private void publish(Map<String, Object> event) {
        try {
            publisher.publish(event);
        } catch (IOException e) {
            System.err.println("Failed to publish Philips event: " + e.getMessage());
        }
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void copyIfPresent(Map<String, Object> event, String canonicalKey, String legacyKey) {
        if (event.containsKey(legacyKey)) {
            event.put(canonicalKey, event.get(legacyKey));
        }
    }

    private boolean hasRawAttribute(ObservationPoll observation, int oid) {
        return observation != null && observation.getAttributes() != null && observation.getAttributes().get(OIDType.lookup(oid)) != null;
    }
}
