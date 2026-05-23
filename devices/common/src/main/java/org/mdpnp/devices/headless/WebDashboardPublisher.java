package org.mdpnp.devices.headless;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

/**
 * A {@link JsonPublisher} that serves a patient monitor dashboard on a single HTTP port.
 * Uses Server-Sent Events (SSE) to push data to the browser — no WebSocket needed.
 *
 * <ul>
 *   <li>GET /       — serves the embedded HTML dashboard page</li>
 *   <li>GET /events — SSE stream of JSON vital sign events</li>
 *   <li>GET /api/latest — returns the most recent event as JSON</li>
 * </ul>
 *
 * Usage: new WebDashboardPublisher(8080) or new WebDashboardPublisher(8080, 0)
 * Then open http://localhost:8080 in a browser.
 */
public final class WebDashboardPublisher implements JsonPublisher {

    private static final Logger log = Logger.getLogger(WebDashboardPublisher.class.getName());

    private final HttpServer httpServer;
    private final ConcurrentHashMap<HttpExchange, OutputStream> sseClients = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private volatile String latestEventJson = "{}";
    private static final int MAX_BUFFERED = 100;

    public WebDashboardPublisher(int httpPort, int wsPort) throws IOException {
        this(httpPort);
    }

    public WebDashboardPublisher(int httpPort) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", this::handleIndex);
        httpServer.createContext("/events", this::handleSSE);
        httpServer.createContext("/api/latest", this::handleLatest);
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        httpServer.start();
        log.info("Web dashboard started: http://localhost:" + httpPort);
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        String json = JsonUtil.toJson(event);
        latestEventJson = json;
        recentEvents.addLast(json);
        while (recentEvents.size() > MAX_BUFFERED) recentEvents.pollFirst();

        // Send to all SSE clients
        byte[] sseData = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        Iterator<Map.Entry<HttpExchange, OutputStream>> it = sseClients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<HttpExchange, OutputStream> entry = it.next();
            try {
                OutputStream os = entry.getValue();
                synchronized (os) {
                    os.write(sseData);
                    os.flush();
                }
            } catch (IOException e) {
                it.remove();
            }
        }
    }

    @Override
    public void close() throws IOException {
        for (Map.Entry<HttpExchange, OutputStream> entry : sseClients.entrySet()) {
            try { entry.getValue().close(); } catch (Exception ignored) {}
            try { entry.getKey().close(); } catch (Exception ignored) {}
        }
        sseClients.clear();
        httpServer.stop(0);
    }

    // ─── HTTP Handlers ──────────────────────────────────────────────────

    private void handleSSE(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();

        // Send recent buffered events
        for (String evt : recentEvents) {
            os.write(("data: " + evt + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
        os.flush();

        sseClients.put(exchange, os);
        log.info("SSE client connected: " + exchange.getRemoteAddress());

        // Keep the connection open — the thread will block here
        // The connection stays open until the client disconnects
        // publish() writes to the OutputStream from the gateway thread
        try {
            while (sseClients.containsKey(exchange)) {
                Thread.sleep(5000);
                // Send SSE comment as keepalive
                synchronized (os) {
                    os.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        } catch (Exception e) {
            sseClients.remove(exchange);
        }
    }

    private void handleLatest(HttpExchange exchange) throws IOException {
        byte[] body = latestEventJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        byte[] body = HTML_PAGE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    // ─── Embedded HTML Dashboard ────────────────────────────────────────

    private static final String HTML_PAGE = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>CriticalInsights Monitor</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0a0a0a;color:#eee;font-family:'Segoe UI',system-ui,sans-serif;overflow:hidden;height:100vh;display:flex;flex-direction:column}
#header{background:#1a1a1a;padding:8px 16px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #333;flex-shrink:0}
#header h1{font-size:18px;color:#0af}
.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}
.dot.on{background:#0f0}.dot.off{background:#f00}
#main{display:flex;flex:1;overflow:hidden}
#waves{flex:1;display:flex;flex-direction:column;padding:4px}
#waves canvas{flex:1;width:100%;min-height:0}
#nums{width:280px;display:flex;flex-direction:column;padding:8px;gap:6px;border-left:1px solid #333;overflow-y:auto}
.num-box{background:#111;border-radius:6px;padding:8px 12px;border-left:3px solid #555}
.num-box .label{font-size:12px;font-weight:bold;opacity:0.8}
.num-box .value{font-size:42px;font-weight:bold;font-family:'Courier New',monospace;line-height:1.1}
.num-box .unit{font-size:11px;opacity:0.5}
.hr{border-color:#0f0;color:#0f0}.spo2{border-color:#0ff;color:#0ff}
.abp{border-color:#f00;color:#f00}.rr{border-color:#ff0;color:#ff0}
.etco2{border-color:#fff;color:#fff}.temp{border-color:#f0f;color:#f0f}
.vent{border-color:#fa0;color:#fa0}
#footer{background:#1a1a1a;padding:6px 16px;font-size:12px;color:#888;display:flex;gap:20px;border-top:1px solid #333;flex-shrink:0}
</style></head><body>
<div id="header">
  <h1>CriticalInsights</h1>
  <div><span class="dot off" id="connDot"></span><span id="connText">Connecting...</span></div>
  <div id="clock"></div>
</div>
<div id="main">
  <div id="waves">
    <canvas id="c0"></canvas>
    <canvas id="c1"></canvas>
    <canvas id="c2"></canvas>
    <canvas id="c3"></canvas>
  </div>
  <div id="nums">
    <div class="num-box hr"><div class="label">HR</div><div class="value" id="vHR">--</div><div class="unit">bpm</div></div>
    <div class="num-box spo2"><div class="label">SpO2</div><div class="value" id="vSpO2">--</div><div class="unit">%</div></div>
    <div class="num-box abp"><div class="label">ABP</div><div class="value" id="vABP">--</div><div class="unit">mmHg</div></div>
    <div class="num-box rr"><div class="label">Resp</div><div class="value" id="vRR">--</div><div class="unit">/min</div></div>
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
var evtCount=0, startTime=Date.now();
var hr=0,spo2=0,abpS=0,abpD=0,abpM=0,rr=0,etco2=0,temp=0,paw=0,peep=0;
var traces=[[],[],[],[]]; // ECG, SpO2, ABP, Resp
var TRACE_LEN=600;
var colors=['#00ff00','#00ffff','#ff0000','#ffff00'];
var labels=['ECG','SpO2','ABP','Resp'];
var canvases=[];
var writePos=0;

// Init canvases
for(var i=0;i<4;i++){
  canvases.push(document.getElementById('c'+i));
  traces[i]=new Array(TRACE_LEN).fill(0.5);
}

// SSE connection
var evtSource=null;
function connect(){
  evtSource=new EventSource('/events');
  evtSource.onopen=function(){
    document.getElementById('connDot').className='dot on';
    document.getElementById('connText').textContent='Connected';
    document.getElementById('statusMsg').textContent='Receiving data';
  };
  evtSource.onerror=function(){
    document.getElementById('connDot').className='dot off';
    document.getElementById('connText').textContent='Disconnected';
    document.getElementById('statusMsg').textContent='Reconnecting...';
  };
  evtSource.onmessage=function(e){
    evtCount++;
    document.getElementById('evtCount').textContent=evtCount;
    try{processEvent(JSON.parse(e.data));}catch(ex){}
  };
}

function processEvent(d){
  var m=d.metric||d.metricCode||'';
  var v=d.value;
  if(v==null||v==undefined)return;
  v=Math.round(v*10)/10;

  // Philips metrics
  if(m.indexOf('CARD_BEAT_RATE')>=0||m.indexOf('ECG_CARD')>=0){hr=v;document.getElementById('vHR').textContent=Math.round(v);}
  else if(m.indexOf('SAT_O2')>=0&&m.indexOf('PULS_OXIM')>=0){spo2=v;document.getElementById('vSpO2').textContent=Math.round(v);}
  else if(m.indexOf('ABP_SYS')>=0||m.indexOf('ART_ABP_SYS')>=0){abpS=v;updateABP();}
  else if(m.indexOf('ABP_DIA')>=0||m.indexOf('ART_ABP_DIA')>=0){abpD=v;updateABP();}
  else if(m.indexOf('ABP_MEAN')>=0||m.indexOf('ART_ABP_MEAN')>=0){abpM=v;updateABP();}
  else if(m.indexOf('RESP_RATE')>=0||m=='NOM_RESP'||m.indexOf('NOM_RESP_RATE')>=0){rr=v;document.getElementById('vRR').textContent=Math.round(v);}
  else if(m.indexOf('CO2_ET')>=0||m.indexOf('AWAY_CO2')>=0){etco2=v;document.getElementById('vCO2').textContent=Math.round(v);}
  else if(m.indexOf('TEMP_BLD')>=0||m.indexOf('TEMP')>=0&&m.indexOf('AW')<0){temp=v;document.getElementById('vTemp').textContent=v.toFixed(1);}
  else if(m.indexOf('PULS_RATE')>=0){hr=v;document.getElementById('vHR').textContent=Math.round(v);}

  // Draeger metrics
  else if(m.indexOf('PeakBreathingPressure')>=0||m.indexOf('BreathingPressure')>=0&&m.indexOf('PEEP')<0){paw=v;updateVent();}
  else if(m.indexOf('PEEP')>=0){peep=v;updateVent();}
  else if(m.indexOf('TidalVolume')>=0){}
  else if(m.indexOf('InspO2')>=0||m.indexOf('FiO2')>=0){}
  else if(m.indexOf('Compliance')>=0){}
  else if(m.indexOf('Resistance')>=0){}
  else if(m.indexOf('AirwayTemp')>=0){temp=v;document.getElementById('vTemp').textContent=v.toFixed(1);}
  else if(m.indexOf('RespiratoryMinuteVolume')>=0){}
  else if(m.indexOf('SpontaneousRespiratoryRate')>=0||m.indexOf('RespiratoryRate')>=0){rr=v;document.getElementById('vRR').textContent=Math.round(v);}
}

function updateABP(){
  var t=Math.round(abpS)+'/'+Math.round(abpD);
  if(abpM>0)t+=' ('+Math.round(abpM)+')';
  document.getElementById('vABP').textContent=t;
}
function updateVent(){
  document.getElementById('vVent').textContent=Math.round(paw)+' / '+Math.round(peep);
}

// Waveform generation from numeric values
function genWaveforms(){
  var t=writePos/60;
  // ECG - PQRST
  var beat=hr>0?hr:72;
  var period=3600/beat;
  var phase=(writePos%period)/period;
  var ecg=0.5;
  if(phase<0.05)ecg=0.5+0.05*Math.sin(phase/0.05*Math.PI);
  else if(phase<0.15)ecg=0.5;
  else if(phase<0.2)ecg=0.5+0.4*Math.sin((phase-0.15)/0.05*Math.PI);
  else if(phase<0.22)ecg=0.5-0.1;
  else if(phase<0.35)ecg=0.5+0.08*Math.sin((phase-0.22)/0.13*Math.PI);
  else ecg=0.5;
  traces[0][writePos%TRACE_LEN]=ecg;

  // SpO2 pleth
  var sp=spo2>0?spo2:97;
  var pphase=(writePos%(period*0.9))/(period*0.9);
  var pleth=0.3;
  if(pphase<0.15)pleth=0.3+0.5*(pphase/0.15);
  else if(pphase<0.4)pleth=0.8-0.3*((pphase-0.15)/0.25);
  else if(pphase<0.5)pleth=0.5+0.1*Math.sin((pphase-0.4)/0.1*Math.PI);
  else pleth=0.5-0.2*((pphase-0.5)/0.5);
  traces[1][writePos%TRACE_LEN]=pleth;

  // ABP
  var s=abpS>0?abpS:120,di=abpD>0?abpD:80;
  var aphase=(writePos%period)/period;
  var abpNorm;
  if(aphase<0.1)abpNorm=di/200+(s-di)/200*(aphase/0.1);
  else if(aphase<0.15)abpNorm=s/200-(s-di)/200*0.3*((aphase-0.1)/0.05);
  else if(aphase<0.25)abpNorm=(s*0.7+di*0.3)/200+0.03*Math.sin((aphase-0.15)/0.1*Math.PI);
  else abpNorm=di/200+(s-di)/200*0.1*Math.exp(-(aphase-0.25)*8);
  traces[2][writePos%TRACE_LEN]=abpNorm;

  // Resp
  var rrate=rr>0?rr:16;
  var rperiod=3600/rrate;
  var rphase=(writePos%rperiod)/rperiod;
  traces[3][writePos%TRACE_LEN]=0.5+0.3*Math.sin(rphase*2*Math.PI);

  writePos++;
}

// Render
function render(){
  for(var c=0;c<4;c++){
    var cv=canvases[c];
    var ctx=cv.getContext('2d');
    cv.width=cv.clientWidth;cv.height=cv.clientHeight;
    var w=cv.width,h=cv.height;

    ctx.fillStyle='#0a0a0a';ctx.fillRect(0,0,w,h);

    // Grid
    ctx.strokeStyle='#1a1a1a';ctx.lineWidth=1;
    for(var gx=0;gx<w;gx+=50){ctx.beginPath();ctx.moveTo(gx,0);ctx.lineTo(gx,h);ctx.stroke();}
    for(var gy=0;gy<h;gy+=25){ctx.beginPath();ctx.moveTo(0,gy);ctx.lineTo(w,gy);ctx.stroke();}

    // Label
    ctx.fillStyle=colors[c];ctx.font='bold 13px sans-serif';ctx.fillText(labels[c],4,15);

    // Trace
    ctx.strokeStyle=colors[c];ctx.lineWidth=1.5;ctx.beginPath();
    var wp=writePos%TRACE_LEN;
    for(var i=0;i<TRACE_LEN;i++){
      var idx=(wp+i)%TRACE_LEN;
      var x=i*w/TRACE_LEN;
      var y=h-traces[c][idx]*h;
      if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);
    }
    ctx.stroke();

    // Sweep line
    var sx=wp*w/TRACE_LEN;
    ctx.strokeStyle='#fff';ctx.lineWidth=1;
    ctx.beginPath();ctx.moveTo(sx,0);ctx.lineTo(sx,h);ctx.stroke();
  }
}

// Clock
function updateClock(){
  var d=new Date();
  document.getElementById('clock').textContent=d.toLocaleTimeString();
  var elapsed=Math.floor((Date.now()-startTime)/1000);
  var hh=String(Math.floor(elapsed/3600)).padStart(2,'0');
  var mm=String(Math.floor((elapsed%3600)/60)).padStart(2,'0');
  var ss=String(elapsed%60).padStart(2,'0');
  document.getElementById('uptime').textContent=hh+':'+mm+':'+ss;
}

// Main loop
setInterval(function(){genWaveforms();genWaveforms();genWaveforms();},50);
setInterval(render,33);
setInterval(updateClock,1000);
connect();
</script></body></html>
""";
}
