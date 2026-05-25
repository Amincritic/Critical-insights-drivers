package org.mdpnp.devices.headless.events;

import java.util.Map;

public final class CanonicalEvents {
    public static final String SCHEMA_VERSION = "1.0";

    private CanonicalEvents() { }

    public static void common(Map<String, Object> event, String topic, String deviceId, String gatewayId, String bedId) {
        event.put("schema_version", SCHEMA_VERSION);
        event.put("topic", topic);
        event.put("unique_device_identifier", deviceId);
        event.put("gateway_id", gatewayId);
        event.put("bed_id", bedId);
        event.put("presentation_time", event.get("timestamp"));
    }

    public static void numeric(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String metricId, String vendorMetricId, int instanceId, String unitId, Number value) {
        common(event, "Numeric", deviceId, gatewayId, bedId);
        event.put("metric_id", fallback(metricId, "unknown"));
        event.put("vendor_metric_id", vendorMetricId);
        event.put("instance_id", Integer.valueOf(instanceId));
        event.put("unit_id", unitId);
        event.put("value", value);
        event.put("device_time", null);
    }

    public static void sampleArray(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String metricId, String vendorMetricId, int instanceId, String unitId, Integer frequency, Object values) {
        common(event, "SampleArray", deviceId, gatewayId, bedId);
        event.put("metric_id", fallback(metricId, "unknown"));
        event.put("vendor_metric_id", vendorMetricId);
        event.put("instance_id", Integer.valueOf(instanceId));
        event.put("unit_id", unitId);
        event.put("frequency", frequency);
        event.put("values", values);
        event.put("device_time", null);
    }

    public static void alert(Map<String, Object> event, String topic, String deviceId, String gatewayId, String bedId,
            String identifier, String text, String priority) {
        common(event, topic, deviceId, gatewayId, bedId);
        event.put("identifier", identifier);
        event.put("text", text);
        event.put("priority", priority);
    }

    public static void patient(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String mrn, String givenName, String familyName) {
        common(event, "Patient", deviceId, gatewayId, bedId);
        event.put("mrn", mrn);
        event.put("given_name", givenName);
        event.put("family_name", familyName);
    }

    public static void patientDemographics(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String mrn, String givenName, String familyName) {
        common(event, "PatientDemographics", deviceId, gatewayId, bedId);
        event.put("mrn", mrn);
        event.put("given_name", givenName);
        event.put("family_name", familyName);
    }

    public static void deviceIdentity(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String manufacturer, String model, String serialNumber, String build, String operatingSystem) {
        common(event, "DeviceIdentity", deviceId, gatewayId, bedId);
        event.put("manufacturer", manufacturer);
        event.put("model", model);
        event.put("serial_number", serialNumber);
        event.put("build", build);
        event.put("operating_system", operatingSystem);
    }

    public static void deviceConnectivity(Map<String, Object> event, String deviceId, String gatewayId, String bedId,
            String state, String type, String info, Object validTargets, String comPort) {
        common(event, "DeviceConnectivity", deviceId, gatewayId, bedId);
        event.put("state", state);
        event.put("type", type);
        event.put("info", info);
        event.put("valid_targets", validTargets);
        event.put("comPort", comPort);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }
}
