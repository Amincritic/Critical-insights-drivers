package org.mdpnp.devices.headless;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpJsonPublisher implements JsonPublisher {
    private final URL url;
    private final int timeoutMs;
    private final Map<String, String> headers;

    public HttpJsonPublisher(String url, int timeoutMs) throws IOException {
        this(url, timeoutMs, Collections.<String, String>emptyMap(), false);
    }

    public HttpJsonPublisher(String url, int timeoutMs, Map<String, String> headers) throws IOException {
        this(url, timeoutMs, headers, false);
    }

    public HttpJsonPublisher(String url, int timeoutMs, Map<String, String> headers, boolean allowInsecureHttp) throws IOException {
        this.url = new URL(url);
        if (!allowInsecureHttp && !"https".equalsIgnoreCase(this.url.getProtocol())) {
            throw new IOException("Refusing insecure HTTP publisher URL. Use https:// or pass --allow-insecure-http true for local/test deployments.");
        }
        if (timeoutMs <= 0) { throw new IllegalArgumentException("timeoutMs must be > 0"); }
        this.timeoutMs = timeoutMs;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(headers);
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        byte[] body = JsonUtil.toJson(event).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int status = c.getResponseCode();
            drain(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP publish failed with status " + status);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void drain(InputStream response) throws IOException {
        if (response == null) { return; }
        try (InputStream is = response) {
            byte[] buffer = new byte[1024];
            while (is.read(buffer) != -1) { }
        }
    }
}
