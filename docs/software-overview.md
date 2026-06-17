# Software Overview

Critical Insights is a Java-based medical device integration system. Its purpose is to connect to bedside medical devices, decode their native communication protocols, normalize the data into a common JSON format, and make that data available to dashboards, files, APIs, or other downstream systems.

The system is split into two major parts: a gateway and a simulator.

The gateway is the production runtime. It connects to supported devices such as Philips IntelliVue monitors and Draeger ventilators. These devices can communicate over different transports, including LAN/UDP, TCP, and RS232 serial. The gateway contains protocol-specific drivers that understand how to read Philips IntelliVue Data Export messages and Draeger MEDIBUS messages. After decoding those messages, it converts the device-specific data into canonical JSON events.

The simulator is used for development and testing. It can simulate Philips and Draeger devices without requiring real hardware. It can also replay prebuilt canonical JSON event files. This allows developers to test the gateway, dashboard, schemas, and integrations in a repeatable way.

The most important design idea is the canonical event layer. Instead of forcing every dashboard or backend service to understand Philips and Draeger protocols directly, the gateway converts everything into a shared event model. Examples of canonical event topics include device connectivity, device identity, numeric vital signs, waveform samples, patient alerts, technical alerts, device settings, and text messages.

The high-level flow is:

```text
medical device
  -> device protocol driver
  -> decoded data
  -> canonical JSON event
  -> publisher
  -> dashboard / HTTP endpoint / JSONL file / stdout
```

The project also includes a built-in web dashboard. The dashboard receives already-decoded canonical events through the gateway. It does not parse raw device protocol frames in the browser.

In practical terms, this software acts as a bridge between medical devices and modern software systems. It hides the complexity of vendor-specific medical device protocols and exposes a cleaner JSON-based interface that other applications can consume.
