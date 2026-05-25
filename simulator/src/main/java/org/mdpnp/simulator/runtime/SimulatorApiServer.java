package org.mdpnp.simulator.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.mdpnp.simulator.core.DeviceDescriptor;
import org.mdpnp.simulator.core.SimulatedDevice;

final class SimulatorApiServer implements AutoCloseable {
    private final HttpServer server;
    private final SimulatorManager manager;

    SimulatorApiServer(int port, SimulatorManager manager) throws IOException {
        this.manager = manager;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                route(exchange);
            }
        });
    }

    void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(1);
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/".equals(path)) {
                respond(exchange, 200, html(), "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/api/devices".equals(path)) {
                respond(exchange, 200, devicesJson(), "application/json");
                return;
            }
            if ("POST".equals(method) && path.startsWith("/api/devices/")) {
                handleDeviceAction(exchange, path);
                return;
            }
            respond(exchange, 404, "{\"error\":\"not_found\"}", "application/json");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "{\"error\":" + JsonUtil.quote(e.getMessage()) + "}", "application/json");
        } catch (Exception e) {
            respond(exchange, 500, "{\"error\":" + JsonUtil.quote(e.toString()) + "}", "application/json");
        }
    }

    private void handleDeviceAction(HttpExchange exchange, String path) throws Exception {
        String[] parts = path.split("/");
        if (parts.length != 5) {
            respond(exchange, 404, "{\"error\":\"not_found\"}", "application/json");
            return;
        }
        String id = parts[3];
        String action = parts[4];
        SimulatedDevice device = manager.device(id);
        if ("start".equals(action)) {
            device.start();
        } else if ("stop".equals(action)) {
            device.stop();
        } else {
            respond(exchange, 404, "{\"error\":\"not_found\"}", "application/json");
            return;
        }
        respond(exchange, 200, deviceJson(device), "application/json");
    }

    private String devicesJson() {
        StringBuilder sb = new StringBuilder("{\"devices\":[");
        boolean first = true;
        for (SimulatedDevice device : manager.devices()) {
            if (!first) {
                sb.append(",");
            }
            sb.append(deviceJson(device));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String deviceJson(SimulatedDevice device) {
        DeviceDescriptor d = device.descriptor();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(JsonUtil.quote(d.id())).append(",");
        sb.append("\"type\":").append(JsonUtil.quote(d.type())).append(",");
        sb.append("\"vendor\":").append(JsonUtil.quote(d.vendor())).append(",");
        sb.append("\"model\":").append(JsonUtil.quote(d.model())).append(",");
        sb.append("\"status\":").append(JsonUtil.quote(String.valueOf(device.status()))).append(",");
        sb.append("\"lastMessage\":").append(JsonUtil.quote(device.lastMessage())).append(",");
        sb.append("\"transport\":").append(JsonUtil.map(d.transport()));
        sb.append("}");
        return sb.toString();
    }

    private String html() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Simulator Runtime</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:32px;background:#101214;color:#e8eef2}"
                + "button{margin-left:8px}pre{background:#181d21;padding:16px;border-radius:6px}</style></head>"
                + "<body><h1>Simulator Runtime</h1><pre id=\"out\">Loading...</pre>"
                + "<script>async function load(){const r=await fetch('/api/devices');const j=await r.json();"
                + "document.getElementById('out').textContent=JSON.stringify(j,null,2);}load();setInterval(load,2000);</script>"
                + "</body></html>";
    }

    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
