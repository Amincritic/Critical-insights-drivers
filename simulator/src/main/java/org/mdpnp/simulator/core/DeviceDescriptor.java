package org.mdpnp.simulator.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeviceDescriptor {
    private final String id;
    private final String type;
    private final String vendor;
    private final String model;
    private final Map<String, String> transport;

    public DeviceDescriptor(String id, String type, String vendor, String model, Map<String, String> transport) {
        this.id = require(id, "id");
        this.type = require(type, "type");
        this.vendor = vendor == null ? "" : vendor;
        this.model = model == null ? "" : model;
        this.transport = Collections.unmodifiableMap(new LinkedHashMap<String, String>(transport));
    }

    public String id() { return id; }
    public String type() { return type; }
    public String vendor() { return vendor; }
    public String model() { return model; }
    public Map<String, String> transport() { return transport; }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing device " + field);
        }
        return value.trim();
    }
}
