package org.mdpnp.devices.headless;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

/**
 * A {@link JsonPublisher} that serves a patient monitor dashboard as a single HTML page
 * and pushes every vital-sign event to connected browsers via WebSocket (RFC 6455).
 *
 * <ul>
 *   <li>HTTP on {@code httpPort} serves the embedded HTML page and a polling fallback endpoint.</li>
 *   <li>WebSocket on {@code wsPort} streams JSON events to all connected browsers.</li>
 * </ul>
 */
public final class WebDashboardPublisher implements JsonPublisher {

    private static final Logger log = Logger.getLogger(WebDashboardPublisher.class.getName());
    private static final String WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-5AB8FC6455B3";
    /** Maximum number of recent events to buffer for replay to new clients. */
    private static final int MAX_BUFFERED_EVENTS = 3000;

    private final int wsPort;
    private final HttpServer httpServer;
    private final ServerSocket wsServerSocket;
    private final Thread wsAcceptThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Connected WebSocket clients: socket -> output stream
    private final ConcurrentHashMap<Socket, OutputStream> clients = new ConcurrentHashMap<>();

    // Ring buffer of recent events (last ~60 seconds worth)
    private final ConcurrentLinkedDeque<String> recentEvents = new ConcurrentLinkedDeque<>();
    // Most recent event for polling fallback
    private volatile String latestEventJson = "{}";

    // Keep-alive thread
    private final Thread pingThread;

    public WebDashboardPublisher(int httpPort, int wsPort) throws IOException {
        this.wsPort = wsPort;

        // --- HTTP Server ---
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", this::handleIndex);
        httpServer.createContext("/api/latest", this::handleLatest);
        httpServer.setExecutor(null);
        httpServer.start();
        log.info("WebDashboard HTTP server started on port " + httpPort);

        // --- WebSocket Server ---
        wsServerSocket = new ServerSocket(wsPort);
        wsAcceptThread = new Thread(this::acceptLoop, "ws-accept");
        wsAcceptThread.setDaemon(true);
        wsAcceptThread.start();
        log.info("WebDashboard WebSocket server started on port " + wsPort);

        // --- Ping thread ---
        pingThread = new Thread(this::pingLoop, "ws-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    // ─── JsonPublisher ───────────────────────────────────────────────────

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        String json = JsonUtil.toJson(event);
        latestEventJson = json;

        // Buffer for replay to newly connecting clients
        recentEvents.addLast(json);
        while (recentEvents.size() > MAX_BUFFERED_EVENTS) {
            recentEvents.pollFirst();
        }

        // Broadcast to all connected WebSocket clients
        byte[] frame = encodeTextFrame(json);
        Iterator<Map.Entry<Socket, OutputStream>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Socket, OutputStream> entry = it.next();
            try {
                OutputStream os = entry.getValue();
                synchronized (os) {
                    os.write(frame);
                    os.flush();
                }
            } catch (IOException e) {
                closeQuietly(entry.getKey());
                it.remove();
            }
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        httpServer.stop(0);
        try { wsServerSocket.close(); } catch (IOException ignored) {}
        pingThread.interrupt();
        for (Socket s : clients.keySet()) {
            closeQuietly(s);
        }
        clients.clear();
        log.info("WebDashboard shut down");
    }

    // ─── HTTP handlers ───────────────────────────────────────────────────

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = getHtmlPage().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleLatest(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = latestEventJson.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ─── WebSocket Server ────────────────────────────────────────────────

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = wsServerSocket.accept();
                Thread handler = new Thread(() -> handleWsClient(client), "ws-client-" + client.getRemoteSocketAddress());
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException e) {
                if (running.get()) log.log(Level.WARNING, "WS accept error", e);
            } catch (IOException e) {
                if (running.get()) log.log(Level.WARNING, "WS accept error", e);
            }
        }
    }

    private void handleWsClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read HTTP upgrade request
            StringBuilder request = new StringBuilder();
            int prev = 0, curr;
            while ((curr = in.read()) != -1) {
                request.append((char) curr);
                if (prev == '\r' && curr == '\n' && request.length() >= 4 &&
                    request.charAt(request.length() - 4) == '\r' && request.charAt(request.length() - 3) == '\n') {
                    break;
                }
                prev = curr;
            }

            String req = request.toString();
            String wsKey = extractHeader(req, "Sec-WebSocket-Key");
            if (wsKey == null) {
                socket.close();
                return;
            }

            // Compute accept key
            String acceptKey = computeAcceptKey(wsKey);

            // Send upgrade response
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Register client
            clients.put(socket, out);

            // Send buffered recent events
            for (String evt : recentEvents) {
                byte[] frame = encodeTextFrame(evt);
                synchronized (out) {
                    out.write(frame);
                }
            }
            out.flush();

            // Read loop (handle client frames: pong, close, ping)
            readLoop(socket, in, out);

        } catch (IOException e) {
            if (running.get()) log.log(Level.FINE, "WS client error", e);
        } finally {
            clients.remove(socket);
            closeQuietly(socket);
        }
    }

    private void readLoop(Socket socket, InputStream in, OutputStream out) throws IOException {
        while (running.get() && !socket.isClosed()) {
            int b1 = in.read();
            if (b1 == -1) break;
            int b2 = in.read();
            if (b2 == -1) break;

            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long payloadLen = b2 & 0x7F;

            if (payloadLen == 126) {
                payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) {
                    payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
                }
            }

            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(in, maskKey);
            }

            byte[] payload = new byte[(int) Math.min(payloadLen, 65536)];
            if (payloadLen > 0) {
                readFully(in, payload);
            }

            if (masked && maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i % 4];
                }
            }

            switch (opcode) {
                case 0x8: // Close
                    byte[] closeFrame = new byte[]{(byte) 0x88, 0x00};
                    synchronized (out) { out.write(closeFrame); out.flush(); }
                    return;
                case 0x9: // Ping -> send pong
                    byte[] pongFrame = encodePongFrame(payload);
                    synchronized (out) { out.write(pongFrame); out.flush(); }
                    break;
                case 0xA: // Pong - ignore
                    break;
                default:
                    // Text or binary from client - ignore
                    break;
            }
        }
    }

    private void pingLoop() {
        byte[] pingFrame = new byte[]{(byte) 0x89, 0x00}; // ping, no payload
        while (running.get()) {
            try {
                Thread.sleep(30_000);
                Iterator<Map.Entry<Socket, OutputStream>> it = clients.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Socket, OutputStream> entry = it.next();
                    try {
                        OutputStream os = entry.getValue();
                        synchronized (os) {
                            os.write(pingFrame);
                            os.flush();
                        }
                    } catch (IOException e) {
                        closeQuietly(entry.getKey());
                        it.remove();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ─── WebSocket framing helpers ───────────────────────────────────────

    private static byte[] encodeTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        byte[] frame;
        if (len <= 125) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x81; // FIN + text opcode
            frame[1] = (byte) len;
            System.arraycopy(payload, 0, frame, 2, len);
        } else if (len <= 65535) {
            frame = new byte[4 + len];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) 126;
            frame[2] = (byte) ((len >> 8) & 0xFF);
            frame[3] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, frame, 4, len);
        } else {
            frame = new byte[10 + len];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) 127;
            long l = len;
            for (int i = 7; i >= 0; i--) {
                frame[2 + (7 - i)] = (byte) ((l >> (i * 8)) & 0xFF);
            }
            System.arraycopy(payload, 0, frame, 10, len);
        }
        return frame;
    }

    private static byte[] encodePongFrame(byte[] payload) {
        int len = payload.length;
        byte[] frame;
        if (len <= 125) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x8A; // FIN + pong
            frame[1] = (byte) len;
            System.arraycopy(payload, 0, frame, 2, len);
        } else {
            frame = new byte[4 + len];
            frame[0] = (byte) 0x8A;
            frame[1] = (byte) 126;
            frame[2] = (byte) ((len >> 8) & 0xFF);
            frame[3] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, frame, 4, len);
        }
        return frame;
    }

    private static String extractHeader(String request, String header) {
        String lower = header.toLowerCase() + ":";
        for (String line : request.split("\r\n")) {
            if (line.toLowerCase().startsWith(lower)) {
                return line.substring(lower.length()).trim();
            }
        }
        return null;
    }

    private static String computeAcceptKey(String wsKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((wsKey + WS_MAGIC_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("Unexpected end of stream");
            off += n;
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    // ─── Embedded HTML Dashboard ─────────────────────────────────────────

    private String getHtmlPage() {
        return ("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CriticalInsights Patient Monitor</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0a0a0a;color:#e0e0e0;font-family:'Courier New',monospace;overflow:hidden;height:100vh;display:flex;flex-direction:column}
#header{display:flex;justify-content:space-between;align-items:center;padding:8px 16px;background:#111;border-bottom:1px solid #333;flex-shrink:0}
#header .title{font-size:18px;font-weight:bold;color:#00cc66}
#header .status{display:flex;align-items:center;gap:8px;font-size:13px}
#header .dot{width:10px;height:10px;border-radius:50%;display:inline-block}
#header .dot.on{background:#00ff66;box-shadow:0 0 6px #00ff66}
#header .dot.off{background:#ff3333;box-shadow:0 0 6px #ff3333}
#header .clock{color:#888;font-size:13px}
#main{display:flex;flex:1;overflow:hidden}
#waves{flex:1;display:flex;flex-direction:column;border-right:1px solid #333;min-width:0}
.wave-row{flex:1;display:flex;flex-direction:column;border-bottom:1px solid #222;position:relative;min-height:0}
.wave-label{position:absolute;top:2px;left:8px;font-size:11px;font-weight:bold;z-index:2;text-shadow:0 0 4px #000}
.wave-label.ecg{color:#00ff66}
.wave-label.spo2{color:#00ffff}
.wave-label.abp{color:#ff3333}
.wave-label.resp{color:#ffff00}
.wave-row canvas{width:100%;height:100%;display:block}
#numerics{width:280px;display:flex;flex-direction:column;padding:4px;overflow-y:auto;flex-shrink:0}
.num-box{flex:1;display:flex;flex-direction:column;justify-content:center;align-items:flex-end;padding:4px 16px;border-bottom:1px solid #222;min-height:60px}
.num-box .label{font-size:11px;text-align:right;width:100%}
.num-box .value{font-size:48px;font-weight:bold;text-align:right;width:100%;line-height:1.1}
.num-box .unit{font-size:12px;color:#888;text-align:right;width:100%}
.num-box.hr .label,.num-box.hr .value{color:#00ff66}
.num-box.spo2 .label,.num-box.spo2 .value{color:#00ffff}
.num-box.abp .label{color:#ff3333}
.num-box.abp .value{color:#ff3333}
.num-box.abp .sub{font-size:22px;color:#ff6666}
.num-box.rr .label,.num-box.rr .value{color:#ffff00}
.num-box.etco2 .label,.num-box.etco2 .value{color:#cc99ff}
.num-box.temp .label,.num-box.temp .value{color:#ff9966}
.num-box.vent .label,.num-box.vent .value{color:#66ccff}
#footer{display:flex;justify-content:space-between;padding:4px 16px;background:#111;border-top:1px solid #333;font-size:11px;color:#666;flex-shrink:0}
@media(max-width:768px){
  #main{flex-direction:column}
  #numerics{width:100%;flex-direction:row;flex-wrap:wrap;max-height:200px}
  .num-box{min-width:33%;min-height:50px}
  .num-box .value{font-size:28px}
  #waves{min-height:50vh}
}
</style>
</head>
<body>
<div id="header">
  <span class="title">CriticalInsights</span>
  <span class="status">
    <span>Bed: <span id="bedId">--</span></span>
    <span class="dot off" id="connDot"></span>
    <span id="connText">Disconnected</span>
  </span>
  <span class="clock" id="clock">--:--:--</span>
</div>
<div id="main">
  <div id="waves">
    <div class="wave-row"><span class="wave-label ecg">II ECG</span><canvas id="cvEcg"></canvas></div>
    <div class="wave-row"><span class="wave-label spo2">SpO2 Pleth</span><canvas id="cvSpo2"></canvas></div>
    <div class="wave-row"><span class="wave-label abp">ABP</span><canvas id="cvAbp"></canvas></div>
    <div class="wave-row"><span class="wave-label resp">Resp</span><canvas id="cvResp"></canvas></div>
  </div>
  <div id="numerics">
    <div class="num-box hr"><div class="label">HR</div><div class="value" id="vHR">--</div><div class="unit">bpm</div></div>
    <div class="num-box spo2"><div class="label">SpO2</div><div class="value" id="vSpO2">--</div><div class="unit">%</div></div>
    <div class="num-box abp"><div class="label">ABP</div><div class="value" id="vABP">--</div><div class="unit">mmHg</div></div>
    <div class="num-box rr"><div class="label">RR</div><div class="value" id="vRR">--</div><div class="unit">/min</div></div>
    <div class="num-box etco2"><div class="label">etCO2</div><div class="value" id="vCO2">--</div><div class="unit">mmHg</div></div>
    <div class="num-box temp"><div class="label">Temp</div><div class="value" id="vTemp">--</div><div class="unit">&deg;C</div></div>
    <div class="num-box vent"><div class="label">Paw / PEEP</div><div class="value" id="vVent">--</div><div class="unit">cmH2O</div></div>
  </div>
</div>
<div id="footer">
  <span id="statusMsg">Waiting for data...</span>
  <span>Events: <span id="evtCount">0</span></span>
  <span>Uptime: <span id="uptime">00:00:00</span></span>
</div>
<script>
(function(){
"use strict";
var WS_PORT = __WS_PORT__;
var startTime = Date.now();
var eventCount = 0;
var connected = false;

// Vital sign state
var vitals = {hr:0,spo2:0,abpSys:0,abpDia:0,abpMean:0,rr:0,etco2:0,temp:0,paw:0,peep:0,vt:0,fio2:0,compl:0};
var bedId = '--';

// DOM refs
var vHR=document.getElementById('vHR'),vSpO2=document.getElementById('vSpO2');
var vABP=document.getElementById('vABP'),vRR=document.getElementById('vRR');
var vCO2=document.getElementById('vCO2'),vTemp=document.getElementById('vTemp');
var vVent=document.getElementById('vVent');
var connDot=document.getElementById('connDot'),connText=document.getElementById('connText');
var clockEl=document.getElementById('clock'),bedIdEl=document.getElementById('bedId');
var statusMsg=document.getElementById('statusMsg'),evtCountEl=document.getElementById('evtCount');
var uptimeEl=document.getElementById('uptime');

// Canvases
var canvases = [
  {id:'cvEcg', color:'#00ff66', buf:[], pos:0, name:'ecg'},
  {id:'cvSpo2', color:'#00ffff', buf:[], pos:0, name:'spo2'},
  {id:'cvAbp', color:'#ff3333', buf:[], pos:0, name:'abp'},
  {id:'cvResp', color:'#ffff00', buf:[], pos:0, name:'resp'}
];
var SAMPLES = 600;
for(var i=0;i<canvases.length;i++){
  canvases[i].canvas = document.getElementById(canvases[i].id);
  canvases[i].ctx = canvases[i].canvas.getContext('2d');
  canvases[i].buf = new Float32Array(SAMPLES);
}

// Resize canvases
function resize(){
  for(var i=0;i<canvases.length;i++){
    var c=canvases[i].canvas;
    var r=c.parentElement.getBoundingClientRect();
    c.width=r.width;c.height=r.height;
  }
}
window.addEventListener('resize',resize);
resize();

// WebSocket connection
var ws = null;
function connect(){
  var host = window.location.hostname || 'localhost';
  ws = new WebSocket('ws://'+host+':'+WS_PORT);
  ws.onopen=function(){
    connected=true;
    connDot.className='dot on';connText.textContent='Connected';
    statusMsg.textContent='Receiving data';
  };
  ws.onclose=function(){
    connected=false;
    connDot.className='dot off';connText.textContent='Disconnected';
    statusMsg.textContent='Connection lost, reconnecting...';
    setTimeout(connect,2000);
  };
  ws.onerror=function(){ws.close();};
  ws.onmessage=function(e){
    eventCount++;evtCountEl.textContent=eventCount;
    try{var d=JSON.parse(e.data);processEvent(d);}catch(ex){}
  };
}

function processEvent(d){
  // Extract bed/device identifier
  if(d.device_id) bedId=d.device_id;
  if(d.bedLabel) bedId=d.bedLabel;
  if(d.bed_label) bedId=d.bed_label;
  bedIdEl.textContent=bedId;

  var metric = (d.metric||d.metric_id||d.metricId||d.param||'').toString();
  var val = parseFloat(d.value!=null?d.value:(d.numeric_value!=null?d.numeric_value:(d.numericValue!=null?d.numericValue:NaN)));
  if(isNaN(val)) return;

  var mu = metric.toUpperCase();

  // HR
  if(mu.indexOf('BEAT_RATE')>=0||mu.indexOf('HEART_RATE')>=0||mu.indexOf('CARD')>=0||mu.indexOf('PULSE_RATE')>=0||(mu.indexOf('HR')>=0&&mu.length<6)){
    vitals.hr=Math.round(val);
  }
  // SpO2
  else if(mu.indexOf('SAT_O2')>=0||mu.indexOf('SPO2')>=0||mu.indexOf('OXYGEN_SAT')>=0){
    vitals.spo2=Math.round(val);
  }
  // ABP / ART systolic
  else if((mu.indexOf('ABP')>=0||mu.indexOf('ART')>=0||mu.indexOf('BLOOD_PRESS')>=0)&&(mu.indexOf('SYS')>=0)){
    vitals.abpSys=Math.round(val);
  }
  // ABP / ART diastolic
  else if((mu.indexOf('ABP')>=0||mu.indexOf('ART')>=0||mu.indexOf('BLOOD_PRESS')>=0)&&(mu.indexOf('DIA')>=0)){
    vitals.abpDia=Math.round(val);
  }
  // ABP / ART mean
  else if((mu.indexOf('ABP')>=0||mu.indexOf('ART')>=0||mu.indexOf('BLOOD_PRESS')>=0)&&(mu.indexOf('MEAN')>=0)){
    vitals.abpMean=Math.round(val);
  }
  // Resp rate
  else if(mu.indexOf('RESP')>=0&&mu.indexOf('RATE')>=0||mu==='RR'||mu.indexOf('BREATHING_RATE')>=0){
    vitals.rr=Math.round(val);
  }
  else if(mu.indexOf('RESP')>=0&&mu.indexOf('RATE')<0&&mu.indexOf('PRESS')<0){
    vitals.rr=Math.round(val);
  }
  // etCO2
  else if(mu.indexOf('CO2')>=0&&(mu.indexOf('ET')>=0||mu.indexOf('END_TIDAL')>=0)){
    vitals.etco2=Math.round(val);
  }
  else if(mu.indexOf('CO2')>=0){
    vitals.etco2=Math.round(val);
  }
  // Temperature
  else if(mu.indexOf('TEMP')>=0){
    vitals.temp=Math.round(val*10)/10;
  }
  // Draeger ventilator params
  else if(mu.indexOf('PEAKBREATHINGPRESSURE')>=0||mu==='PAW'){
    vitals.paw=Math.round(val);
  }
  else if(mu.indexOf('PEEPBREATHINGPRESSURE')>=0||mu==='PEEP'){
    vitals.peep=Math.round(val);
  }
  else if(mu.indexOf('TIDALVOLUME')>=0||mu==='VT'){
    vitals.vt=Math.round(val);
  }
  else if(mu.indexOf('FIO2')>=0||mu.indexOf('INSPO2')>=0){
    vitals.fio2=Math.round(val);
  }
  else if(mu.indexOf('COMPLIANCE')>=0){
    vitals.compl=Math.round(val);
  }

  updateNumerics();
}

function updateNumerics(){
  vHR.textContent=vitals.hr||'--';
  vSpO2.textContent=vitals.spo2||'--';
  if(vitals.abpSys&&vitals.abpDia){
    var mean=vitals.abpMean||(Math.round((vitals.abpSys+2*vitals.abpDia)/3));
    vABP.innerHTML=vitals.abpSys+'/'+vitals.abpDia+'<br><span class="sub">('+mean+')</span>';
  }
  vRR.textContent=vitals.rr||'--';
  vCO2.textContent=vitals.etco2||'--';
  vTemp.textContent=vitals.temp||'--';
  if(vitals.paw||vitals.peep){
    vVent.textContent=(vitals.paw||'--')+'/'+(vitals.peep||'--');
  }
}

// Waveform generation phase counters
var phase = {ecg:0, spo2:0, abp:0, resp:0};
var lastWaveTime = 0;

function generateWaveforms(dt){
  var hr = vitals.hr||72;
  var spo2 = vitals.spo2||97;
  var sys = vitals.abpSys||120;
  var dia = vitals.abpDia||80;
  var rr = vitals.rr||16;

  var samplesPerFrame = Math.max(1, Math.round(dt * 50 / 16.67)); // ~50 samples/sec at 60fps
  if(samplesPerFrame>10) samplesPerFrame=10;

  for(var s=0;s<samplesPerFrame;s++){
    // ECG: PQRST complex
    var ecgFreq = hr/60;
    phase.ecg += ecgFreq/50;
    if(phase.ecg>=1) phase.ecg-=1;
    var t=phase.ecg;
    var ecgVal=0;
    if(t<0.05) ecgVal=0.1*Math.sin(t/0.05*Math.PI); // P wave
    else if(t<0.1) ecgVal=0;
    else if(t<0.12) ecgVal=-0.15*(t-0.1)/0.02; // Q
    else if(t<0.16) ecgVal=-0.15+1.15*((t-0.12)/0.04); // R up
    else if(t<0.20) ecgVal=1.0-1.3*((t-0.16)/0.04); // R down to S
    else if(t<0.24) ecgVal=-0.3+0.3*((t-0.20)/0.04); // S return
    else if(t<0.40) ecgVal=0.15*Math.sin((t-0.24)/0.16*Math.PI); // T wave
    else ecgVal=0;
    var ci=canvases[0];ci.buf[ci.pos]=ecgVal*0.4+0.5;ci.pos=(ci.pos+1)%SAMPLES;

    // SpO2 pleth
    phase.spo2 += ecgFreq/50;
    if(phase.spo2>=1) phase.spo2-=1;
    var pt=phase.spo2;
    var plethVal;
    if(pt<0.3) plethVal=Math.sin(pt/0.3*Math.PI*0.5);
    else if(pt<0.45) plethVal=1.0-0.3*((pt-0.3)/0.15);
    else if(pt<0.55) plethVal=0.7+0.15*Math.sin((pt-0.45)/0.1*Math.PI);
    else plethVal=0.85*Math.pow(1-(pt-0.55)/0.45,2);
    plethVal=plethVal*(spo2/100)*0.7+0.15;
    var ci1=canvases[1];ci1.buf[ci1.pos]=plethVal;ci1.pos=(ci1.pos+1)%SAMPLES;

    // ABP arterial waveform
    phase.abp += ecgFreq/50;
    if(phase.abp>=1) phase.abp-=1;
    var at=phase.abp;
    var abpVal;
    var range=sys-dia;
    if(at<0.1) abpVal=dia+range*Math.sin(at/0.1*Math.PI*0.5);
    else if(at<0.15) abpVal=sys-range*0.15*((at-0.1)/0.05);
    else if(at<0.25) abpVal=(sys-range*0.15)+range*0.1*Math.sin((at-0.15)/0.1*Math.PI);
    else if(at<0.35) abpVal=(sys-range*0.05)-range*0.1*((at-0.25)/0.1);
    else abpVal=dia+range*0.15*Math.pow(1-(at-0.35)/0.65,2);
    abpVal=(abpVal-40)/200; // normalize to 0-1
    var ci2=canvases[2];ci2.buf[ci2.pos]=abpVal;ci2.pos=(ci2.pos+1)%SAMPLES;

    // Resp sine wave
    var respFreq = rr/60;
    phase.resp += respFreq/50;
    if(phase.resp>=1) phase.resp-=1;
    var respVal=0.5+0.4*Math.sin(phase.resp*2*Math.PI);
    var ci3=canvases[3];ci3.buf[ci3.pos]=respVal;ci3.pos=(ci3.pos+1)%SAMPLES;
  }
}

function drawWaveform(c){
  var ctx=c.ctx,w=c.canvas.width,h=c.canvas.height;
  if(w===0||h===0) return;
  ctx.fillStyle='#0a0a0a';
  ctx.fillRect(0,0,w,h);

  // Grid lines
  ctx.strokeStyle='#1a1a1a';
  ctx.lineWidth=0.5;
  for(var gy=0;gy<h;gy+=h/4){ctx.beginPath();ctx.moveTo(0,gy);ctx.lineTo(w,gy);ctx.stroke();}
  for(var gx=0;gx<w;gx+=w/10){ctx.beginPath();ctx.moveTo(gx,0);ctx.lineTo(gx,h);ctx.stroke();}

  // Waveform trace
  ctx.strokeStyle=c.color;
  ctx.lineWidth=1.5;
  ctx.lineJoin='round';
  ctx.beginPath();
  var drawn=false;
  for(var i=0;i<SAMPLES;i++){
    var idx=(c.pos+i)%SAMPLES;
    var x=i*w/SAMPLES;
    var y=h-(c.buf[idx]*h*0.8+h*0.1);
    if(!drawn){ctx.moveTo(x,y);drawn=true;}else{ctx.lineTo(x,y);}
  }
  ctx.stroke();

  // Sweep line
  var sx=c.pos*w/SAMPLES;
  ctx.strokeStyle='rgba(255,255,255,0.7)';
  ctx.lineWidth=2;
  ctx.beginPath();ctx.moveTo(sx,0);ctx.lineTo(sx,h);ctx.stroke();

  // Erase ahead of sweep
  ctx.fillStyle='#0a0a0a';
  var eraseW=w*0.05;
  ctx.fillRect(sx,0,eraseW,h);
}

function animationLoop(ts){
  var dt = ts-lastWaveTime;
  if(dt>200) dt=16.67;
  lastWaveTime=ts;

  generateWaveforms(dt);
  for(var i=0;i<canvases.length;i++) drawWaveform(canvases[i]);

  // Clock
  var now=new Date();
  clockEl.textContent=pad2(now.getHours())+':'+pad2(now.getMinutes())+':'+pad2(now.getSeconds());

  // Uptime
  var elapsed=Math.floor((Date.now()-startTime)/1000);
  var uh=Math.floor(elapsed/3600),um=Math.floor((elapsed%3600)/60),us=elapsed%60;
  uptimeEl.textContent=pad2(uh)+':'+pad2(um)+':'+pad2(us);

  requestAnimationFrame(animationLoop);
}
function pad2(n){return n<10?'0'+n:''+n;}

requestAnimationFrame(function(ts){lastWaveTime=ts;animationLoop(ts);});
connect();
})();
</script>
</body>
</html>
""").replace("__WS_PORT__", String.valueOf(wsPort));
    }
}
