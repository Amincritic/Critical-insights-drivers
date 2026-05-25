package org.mdpnp.devices.headless;

import java.io.IOException;
import java.io.InputStream;
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
 *   <li>POST /api/events — accepts one canonical JSON event for replay/UI tests</li>
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
    private static final int MAX_BUFFERED = 2000;

    public WebDashboardPublisher(int httpPort, int wsPort) throws IOException {
        this(httpPort);
    }

    public WebDashboardPublisher(int httpPort) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", this::handleIndex);
        httpServer.createContext("/events", this::handleSSE);
        httpServer.createContext("/api/latest", this::handleLatest);
        httpServer.createContext("/api/events", this::handlePostEvent);
        httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        httpServer.start();
        log.info("Web dashboard started: http://localhost:" + httpPort);
    }

    @Override
    public void publish(Map<String, Object> event) throws IOException {
        publishJson(JsonUtil.toJson(event));
    }

    private void publishJson(String json) throws IOException {
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

    private void handlePostEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}", "application/json");
            return;
        }
        String body = readBody(exchange.getRequestBody()).trim();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            respond(exchange, 400, "{\"error\":\"expected_json_object\"}", "application/json");
            return;
        }
        publishJson(body);
        respond(exchange, 202, "{\"accepted\":true}", "application/json");
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        byte[] body = HTML_PAGE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private static String readBody(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
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
#header h1{font-size:18px;color:#0af;display:flex;flex-direction:column;line-height:1.1}
#header small{font-size:11px;color:#888;font-weight:400}
#deviceSelect{background:#111;color:#ddd;border:1px solid #444;border-radius:4px;padding:6px 8px;min-width:220px}
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
  <h1><span id="deviceTitle">CriticalInsights</span><small id="deviceSubtitle">Waiting for device data</small></h1>
  <select id="deviceSelect" title="Active device"></select>
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
    <div class="num-box hr"><div class="label" id="lHR">HR</div><div class="value" id="vHR">--</div><div class="unit" id="uHR">bpm</div></div>
    <div class="num-box spo2"><div class="label" id="lSpO2">SpO2</div><div class="value" id="vSpO2">--</div><div class="unit" id="uSpO2">%</div></div>
    <div class="num-box abp"><div class="label" id="lABP">ABP</div><div class="value" id="vABP">--</div><div class="unit" id="uABP">mmHg</div></div>
    <div class="num-box rr"><div class="label" id="lRR">Resp</div><div class="value" id="vRR">--</div><div class="unit" id="uRR">/min</div></div>
    <div class="num-box etco2"><div class="label" id="lCO2">etCO2</div><div class="value" id="vCO2">--</div><div class="unit" id="uCO2">mmHg</div></div>
    <div class="num-box temp"><div class="label" id="lTemp">Temp</div><div class="value" id="vTemp">--</div><div class="unit" id="uTemp">&deg;C</div></div>
    <div class="num-box vent"><div class="label" id="lVent">Paw / PEEP</div><div class="value" id="vVent">--</div><div class="unit" id="uVent">cmH2O</div></div>
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
var currentMode='unknown';
var useRealWaveforms=false;
var traces=[[],[],[],[]]; // ECG, SpO2, ABP, Resp
var tracePos=[0,0,0,0];
var TRACE_LEN=600;
var colors=['#00ff00','#00ffff','#ff0000','#ffff00'];
var labels=['ECG','SpO2','ABP','Resp'];
var canvases=[];
var writePos=0;
var deviceStates={};
var activeDeviceId=null;
var selector=document.getElementById('deviceSelect');
var requestedDeviceId=new URLSearchParams(window.location.search).get('device');

function metricToken(v){return String(v||'').toUpperCase().replace(/[^A-Z0-9]+/g,'');}
function metricHas(metric,token){return metric.indexOf(metricToken(token))>=0;}

function newDeviceState(id){
  var t=[];
  for(var i=0;i<4;i++)t[i]=new Array(TRACE_LEN).fill(0.5);
  return {id:id,mode:'unknown',hr:0,spo2:0,abpS:0,abpD:0,abpM:0,rr:0,etco2:0,temp:0,paw:0,peep:0,
    useRealWaveforms:false,traces:t,tracePos:[0,0,0,0],labels:['ECG','SpO2','ABP','Resp'],
    colors:['#00ff00','#00ffff','#ff0000','#ffff00'],lastEvent:null};
}

function deviceIdOf(d){
  return d.unique_device_identifier||d.deviceId||d.device_id||'unknown_device';
}

function stateFor(d){
  var id=deviceIdOf(d);
  if(!deviceStates[id]){
    deviceStates[id]=newDeviceState(id);
    var opt=document.createElement('option');
    opt.value=id; opt.textContent=id;
    selector.appendChild(opt);
    if(!activeDeviceId&&(!requestedDeviceId||requestedDeviceId===id)){setActiveDevice(id);}
    else if(requestedDeviceId===id){setActiveDevice(id);}
  }
  return deviceStates[id];
}

selector.onchange=function(){setActiveDevice(selector.value);};

function setActiveDevice(id){
  if(!id||!deviceStates[id])return;
  activeDeviceId=id;
  selector.value=id;
  applyStateToGlobals(deviceStates[id]);
  renderDevice(deviceStates[id]);
}

function applyStateToGlobals(s){
  traces=s.traces; tracePos=s.tracePos; labels=s.labels; colors=s.colors;
  currentMode=s.mode; useRealWaveforms=s.useRealWaveforms;
  hr=s.hr; spo2=s.spo2; abpS=s.abpS; abpD=s.abpD; abpM=s.abpM; rr=s.rr; etco2=s.etco2; temp=s.temp; paw=s.paw; peep=s.peep;
}

function saveGlobalsToState(s){
  s.traces=traces; s.tracePos=tracePos; s.labels=labels; s.colors=colors;
  s.mode=currentMode; s.useRealWaveforms=useRealWaveforms;
  s.hr=hr; s.spo2=spo2; s.abpS=abpS; s.abpD=abpD; s.abpM=abpM; s.rr=rr; s.etco2=etco2; s.temp=temp; s.paw=paw; s.peep=peep;
}

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
  var s=stateFor(d);
  s.lastEvent=d;
  var visible=deviceIdOf(d)===activeDeviceId;
  var visibleState=activeDeviceId?deviceStates[activeDeviceId]:null;
  applyStateToGlobals(s);
  configureDevice(d);
  var m=d.metric||d.metric_id||d.vendor_metric_id||d.metricCode||'';
  var v=d.value;

  if(d.eventType==='realtime_waveform_sample'){
    useRealWaveforms=true;
    if(m.indexOf('AirwayPressure')>=0){pushTrace(0,v,-10,60);}
    else if(m.indexOf('FlowInspExp')>=0){pushTrace(1,v,-60,60);}
    else if(m.indexOf('RespiratoryVolumeSinceInspBegin')>=0){pushTrace(2,v,0,800);}
    finishEvent(s,visible,visibleState);
    return;
  }

  var samples=d.samples||d.values;
  if((d.eventType==='waveform'||d.topic==='SampleArray')&&samples){
    useRealWaveforms=true;
    if(m.indexOf('AirwayPressure')>=0){pushSampleArrayScaled(0,samples,-10,60);}
    else if(m.indexOf('FlowInspExp')>=0){pushSampleArrayScaled(1,samples,-60,60);}
    else if(m.indexOf('RespiratoryVolumeSinceInspBegin')>=0){pushSampleArrayScaled(2,samples,0,800);}
    else if(m.indexOf('ECG')>=0||m.indexOf('ELEC_POTL')>=0){pushSampleArray(0,samples);}
    else if(m.indexOf('PLETH')>=0||m.indexOf('PULS_OXIM')>=0){pushSampleArray(1,samples);}
    else if(m.indexOf('PRESS_BLD_ART')>=0||m.indexOf('ABP')>=0){pushSampleArray(2,samples);}
    else if(m.indexOf('RESP')>=0){pushSampleArray(3,samples);}
    finishEvent(s,visible,visibleState);
    return;
  }

  if(d.eventType==='alarm'||d.topic==='PatientAlert'||d.topic==='TechnicalAlert'){
    var text=d.text||d.message||d.identifier||d.alarmCode||'Alarm';
    var pri=d.priority||d.alarmType||'';
    document.getElementById('statusMsg').textContent='Alarm: '+text+' '+pri;
    finishEvent(s,visible,visibleState);
    return;
  }

  if(d.topic==='DeviceConnectivity'){
    document.getElementById('statusMsg').textContent='Device '+(d.state||'state')+': '+(d.info||'');
    finishEvent(s,visible,visibleState);
    return;
  }

  if(v==null||v==undefined){finishEvent(s,visible,visibleState);return;}
  v=Math.round(v*10)/10;

  var mt=metricToken(m);

  // Philips patient-monitor metrics
  if(metricHas(mt,'CARD_BEAT_RATE')||metricHas(mt,'ECG_CARD')||metricHas(mt,'PULS_RATE')){hr=v;}
  else if(metricHas(mt,'SAT_O2')||metricHas(mt,'SATO2')||metricHas(mt,'PULSOXIM')){spo2=v;}
  else if(metricHas(mt,'ABP_SYS')||metricHas(mt,'ART_ABP_SYS')||metricHas(mt,'PRESS_BLD_ART_ABP_SYS')){abpS=v;}
  else if(metricHas(mt,'ABP_DIA')||metricHas(mt,'ART_ABP_DIA')||metricHas(mt,'PRESS_BLD_ART_ABP_DIA')){abpD=v;}
  else if(metricHas(mt,'ABP_MEAN')||metricHas(mt,'ART_ABP_MEAN')||metricHas(mt,'PRESS_BLD_ART_ABP_MEAN')){abpM=v;}
  else if(metricHas(mt,'RESP_RATE')||mt==='NOMRESP'||metricHas(mt,'NOM_RESP_RATE')){rr=v;}
  else if(metricHas(mt,'CO2_ET')||metricHas(mt,'AWAY_CO2')||metricHas(mt,'ENDTIDALCO2')){etco2=v;}
  else if((metricHas(mt,'TEMP_BLD')||metricHas(mt,'TEMP'))&&!metricHas(mt,'AIRWAY')){temp=v;}

  // Draeger ventilator metrics. Values reuse the display slots after Draeger chrome relabels them.
  else if(metricHas(mt,'PEEPBREATHINGPRESSURE')||mt==='PEEP'||metricHas(mt,'INTERMITTENTPEEP')){peep=v;}
  else if(metricHas(mt,'PEAKBREATHINGPRESSURE')||metricHas(mt,'BREATHINGPRESSURE')){paw=v;}
  else if(metricHas(mt,'TIDALVOLUME')||metricHas(mt,'VTEMAND')||metricHas(mt,'VTESPON')||metricHas(mt,'VTE')||metricHas(mt,'VTI')){hr=v;}
  else if(metricHas(mt,'INSPO2')||metricHas(mt,'INSPIREDOXYGEN')||metricHas(mt,'FIO2')){spo2=v;}
  else if(metricHas(mt,'COMPLIANCE')){etco2=v;}
  else if(metricHas(mt,'AIRWAYTEMPERATURE')||metricHas(mt,'AIRWAYTEMP')){temp=v;}
  else if(metricHas(mt,'RESPIRATORYMINUTEVOLUME')||metricHas(mt,'MINUTEVOLUME')){abpM=v;}
  else if(metricHas(mt,'SPONTANEOUSRESPIRATORYRATE')||metricHas(mt,'RESPIRATORYRATE')){rr=v;}
  finishEvent(s,visible,visibleState);
}

function finishEvent(s,visible,visibleState){
  saveGlobalsToState(s);
  if(visible){
    renderDevice(s);
  } else if(visibleState){
    applyStateToGlobals(visibleState);
    renderDevice(visibleState);
  }
}

function pushTrace(index,value,min,max){
  var span=max-min;
  var norm=span>0?(value-min)/span:0.5;
  if(norm<0)norm=0;if(norm>1)norm=1;
  traces[index][tracePos[index]%TRACE_LEN]=norm;
  tracePos[index]++;
}

function pushSampleArray(index,values){
  if(!values||!values.length)return;
  for(var i=0;i<values.length;i++){
    var norm=Number(values[i])/255;
    if(norm<0)norm=0;if(norm>1)norm=1;
    traces[index][tracePos[index]%TRACE_LEN]=norm;
    tracePos[index]++;
  }
}

function pushSampleArrayScaled(index,values,min,max){
  if(!values||!values.length)return;
  var span=max-min;
  for(var i=0;i<values.length;i++){
    var norm=span>0?(Number(values[i])-min)/span:0.5;
    if(norm<0)norm=0;if(norm>1)norm=1;
    traces[index][tracePos[index]%TRACE_LEN]=norm;
    tracePos[index]++;
  }
}

function configureDevice(d){
  var vendor=(d.vendor||'').toLowerCase();
  var deviceType=(d.deviceType||'').toLowerCase();
  if((vendor==='draeger'||deviceType==='ventilator')&&currentMode!=='draeger'){
    currentMode='draeger';
    applyDraegerChrome(d);
  } else if((vendor==='philips'||deviceType==='patient_monitor')&&currentMode!=='philips'){
    currentMode='philips';
    applyPhilipsChrome(d);
  }
}

function applyDraegerChrome(d){
  document.title='CriticalInsights Ventilator';
  document.getElementById('deviceTitle').textContent='CriticalInsights Ventilator';
  document.getElementById('deviceSubtitle').textContent=((d&&d.deviceId)||((d&&d.unique_device_identifier)||'draeger'))+' · Draeger MEDIBUS';
  document.getElementById('lHR').textContent='Vt'; document.getElementById('uHR').textContent='mL';
  document.getElementById('lSpO2').textContent='FiO2'; document.getElementById('uSpO2').textContent='%';
  document.getElementById('lABP').textContent='MV'; document.getElementById('uABP').textContent='L/min';
  document.getElementById('lRR').textContent='RR'; document.getElementById('uRR').textContent='/min';
  document.getElementById('lCO2').textContent='Compliance'; document.getElementById('uCO2').textContent='mL/mbar';
  document.getElementById('lTemp').textContent='Airway Temp'; document.getElementById('uTemp').innerHTML='&deg;C';
  document.getElementById('lVent').textContent='Paw / PEEP'; document.getElementById('uVent').textContent='cmH2O';
  labels=['Paw','Flow','Volume','Resp'];
  colors=['#ffae00','#00d8ff','#50ff6a','#ffff00'];
}

function applyPhilipsChrome(d){
  document.title='CriticalInsights Monitor';
  document.getElementById('deviceTitle').textContent='CriticalInsights Monitor';
  document.getElementById('deviceSubtitle').textContent=((d&&d.deviceId)||((d&&d.unique_device_identifier)||'philips'))+' · Philips IntelliVue';
  document.getElementById('lHR').textContent='HR'; document.getElementById('uHR').textContent='bpm';
  document.getElementById('lSpO2').textContent='SpO2'; document.getElementById('uSpO2').textContent='%';
  document.getElementById('lABP').textContent='ABP'; document.getElementById('uABP').textContent='mmHg';
  document.getElementById('lRR').textContent='Resp'; document.getElementById('uRR').textContent='/min';
  document.getElementById('lCO2').textContent='etCO2'; document.getElementById('uCO2').textContent='mmHg';
  document.getElementById('lTemp').textContent='Temp'; document.getElementById('uTemp').innerHTML='&deg;C';
  document.getElementById('lVent').textContent='Paw / PEEP'; document.getElementById('uVent').textContent='cmH2O';
  labels=['ECG','SpO2','ABP','Resp'];
  colors=['#00ff00','#00ffff','#ff0000','#ffff00'];
}

function renderDevice(s){
  if(s.mode==='draeger')applyDraegerChrome(s.lastEvent);
  else if(s.mode==='philips')applyPhilipsChrome(s.lastEvent);
  document.getElementById('vHR').textContent='--';
  document.getElementById('vSpO2').textContent='--';
  document.getElementById('vABP').textContent='--';
  document.getElementById('vRR').textContent='--';
  document.getElementById('vCO2').textContent='--';
  document.getElementById('vTemp').textContent='--';
  document.getElementById('vVent').textContent='--';
  if(s.lastEvent)configureDevice(s.lastEvent);
  if(s.mode==='draeger'){
    if(s.hr>0)document.getElementById('vHR').textContent=Math.round(s.hr);
    if(s.spo2>0)document.getElementById('vSpO2').textContent=Math.round(s.spo2);
    if(s.abpM>0)document.getElementById('vABP').textContent=s.abpM.toFixed(1);
    if(s.rr>0)document.getElementById('vRR').textContent=Math.round(s.rr);
    if(s.etco2>0)document.getElementById('vCO2').textContent=Math.round(s.etco2);
    if(s.temp>0)document.getElementById('vTemp').textContent=s.temp.toFixed(1);
    if(s.paw>0||s.peep>0)document.getElementById('vVent').textContent=Math.round(s.paw)+' / '+Math.round(s.peep);
  } else {
    if(s.hr>0)document.getElementById('vHR').textContent=Math.round(s.hr);
    if(s.spo2>0)document.getElementById('vSpO2').textContent=Math.round(s.spo2);
    if(s.abpS>0||s.abpD>0){abpS=s.abpS;abpD=s.abpD;abpM=s.abpM;updateABP();}
    if(s.rr>0)document.getElementById('vRR').textContent=Math.round(s.rr);
    if(s.etco2>0)document.getElementById('vCO2').textContent=Math.round(s.etco2);
    if(s.temp>0)document.getElementById('vTemp').textContent=s.temp.toFixed(1);
  }
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
  if(currentMode==='draeger'&&useRealWaveforms)return;
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
    var wp=(tracePos[c]>0?tracePos[c]:writePos)%TRACE_LEN;
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
