package org.mdpnp.devices.headless.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CanonicalEventValidator {
    private CanonicalEventValidator() { }

    public static List<String> validate(Map<String, Object> event) {
        List<String> errors = new ArrayList<String>();
        if (event == null) {
            errors.add("event is null");
            return errors;
        }

        Object topic = event.get("topic");
        if (topic == null) { return errors; }

        require(event, errors, "schema_version");
        require(event, errors, "unique_device_identifier");
        require(event, errors, "presentation_time");

        String t = String.valueOf(topic);
        if ("Numeric".equals(t)) {
            require(event, errors, "metric_id");
            require(event, errors, "instance_id");
            require(event, errors, "value");
        } else if ("SampleArray".equals(t)) {
            require(event, errors, "metric_id");
            require(event, errors, "instance_id");
            require(event, errors, "values");
        } else if ("PatientAlert".equals(t) || "TechnicalAlert".equals(t)) {
            require(event, errors, "identifier");
            require(event, errors, "text");
        } else if ("Patient".equals(t)) {
            require(event, errors, "mrn");
        } else if ("DeviceIdentity".equals(t)) {
            require(event, errors, "unique_device_identifier");
        } else if ("DeviceConnectivity".equals(t)) {
            require(event, errors, "state");
            require(event, errors, "type");
        }
        return errors;
    }

    public static void annotate(Map<String, Object> event) {
        List<String> errors = validate(event);
        if (event != null && event.get("topic") != null) {
            event.put("schema_valid", Boolean.valueOf(errors.isEmpty()));
            if (!errors.isEmpty()) { event.put("schema_errors", errors); }
        }
    }

    private static void require(Map<String, Object> event, List<String> errors, String field) {
        if (!event.containsKey(field)) {
            errors.add("missing " + field);
            return;
        }
        Object value = event.get(field);
        if (value instanceof String && ((String) value).length() == 0) {
            errors.add("empty " + field);
        }
    }
}
