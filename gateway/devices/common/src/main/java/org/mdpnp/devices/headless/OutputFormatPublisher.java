package org.mdpnp.devices.headless;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OutputFormatPublisher implements JsonPublisher {
    public static final String CANONICAL = "canonical";

    private final JsonPublisher downstream;
    private final String format;

    public OutputFormatPublisher(JsonPublisher downstream, String format) {
        this.downstream = downstream;
        this.format = normalize(format);
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        Map<String, Object> filtered = filter(event);
        if (filtered != null) {
            downstream.publish(filtered);
        }
    }

    @Override
    public void close() throws IOException {
        downstream.close();
    }

    private Map<String, Object> filter(Map<String, Object> event) {
        if (event == null) { return null; }
        if (CANONICAL.equals(format)) { return canonical(event); }
        throw new IllegalStateException("Unsupported output format: " + format);
    }

    private Map<String, Object> canonical(Map<String, Object> event) {
        if (event.get("topic") == null) { return null; }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        copy(event, out, "schema_version");
        copy(event, out, "topic");
        copy(event, out, "unique_device_identifier");
        copy(event, out, "metric_id");
        copy(event, out, "vendor_metric_id");
        copy(event, out, "instance_id");
        copy(event, out, "unit_id");
        copy(event, out, "value");
        copy(event, out, "frequency");
        copy(event, out, "values");
        copy(event, out, "raw_sample_bytes");
        copy(event, out, "sample_size_bits");
        copy(event, out, "significant_bits");
        copy(event, out, "array_size");
        copy(event, out, "sample_period_us");
        copy(event, out, "scale_range");
        copy(event, out, "physiological_range");
        copy(event, out, "calibration");
        copy(event, out, "fixed_values");
        copy(event, out, "sample_flags");
        copy(event, out, "device_time");
        copy(event, out, "profile");
        copy(event, out, "streams");
        copy(event, out, "parameters");
        copy(event, out, "presentation_time");
        copy(event, out, "identifier");
        copy(event, out, "text");
        copy(event, out, "priority");
        copy(event, out, "mrn");
        copy(event, out, "given_name");
        copy(event, out, "family_name");
        copy(event, out, "date_of_birth");
        copy(event, out, "sex");
        copy(event, out, "patient_type");
        copy(event, out, "manufacturer");
        copy(event, out, "model");
        copy(event, out, "serial_number");
        copy(event, out, "build");
        copy(event, out, "operating_system");
        copy(event, out, "state");
        copy(event, out, "type");
        copy(event, out, "info");
        copy(event, out, "valid_targets");
        copy(event, out, "comPort");
        copy(event, out, "gateway_id");
        copy(event, out, "bed_id");
        copy(event, out, "vendor");
        copy(event, out, "protocol");
        copy(event, out, "schema_valid");
        copy(event, out, "schema_errors");
        return out;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) { to.put(key, from.get(key)); }
    }

    public static String normalize(String value) {
        if (value == null || value.length() == 0) { return CANONICAL; }
        if (CANONICAL.equalsIgnoreCase(value)) { return CANONICAL; }
        throw new IllegalArgumentException("--output-format supports only canonical");
    }
}
