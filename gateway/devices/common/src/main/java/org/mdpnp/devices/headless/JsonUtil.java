package org.mdpnp.devices.headless;

import java.util.Iterator;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() { }

    public static String toJson(Object o) {
        if (o == null) { return "null"; }
        if (o instanceof String) { return quote((String)o); }
        if (o instanceof Number) {
            if (o instanceof Double || o instanceof Float) {
                double d = ((Number)o).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) { return "null"; }
            }
            return String.valueOf(o);
        }
        if (o instanceof Boolean) { return String.valueOf(o); }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            Iterator<? extends Map.Entry<?,?>> it = ((Map<?,?>)o).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?,?> e = it.next();
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
                if (it.hasNext()) { sb.append(','); }
            }
            sb.append('}');
            return sb.toString();
        }
        if (o instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Iterator<?> it = ((Iterable<?>)o).iterator();
            while (it.hasNext()) {
                sb.append(toJson(it.next()));
                if (it.hasNext()) { sb.append(','); }
            }
            sb.append(']');
            return sb.toString();
        }
        if (o.getClass().isArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(o);
            for (int i=0; i<n; i++) {
                if (i > 0) { sb.append(','); }
                sb.append(toJson(java.lang.reflect.Array.get(o, i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return quote(String.valueOf(o));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int)c)); }
                    else { sb.append(c); }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
