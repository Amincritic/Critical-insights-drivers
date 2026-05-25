package org.mdpnp.simulator.runtime;

import java.util.Iterator;
import java.util.Map;

final class JsonUtil {
    private JsonUtil() { }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String map(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        Iterator<Map.Entry<String, String>> it = values.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            sb.append(quote(e.getKey())).append(":").append(quote(e.getValue()));
            if (it.hasNext()) {
                sb.append(",");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
