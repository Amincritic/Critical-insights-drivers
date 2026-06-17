# Technical Implementation Overview

Critical Insights is implemented as a Java 17 medical-device gateway workspace. The main design is separated into four layers:

```text
transport layer
  -> protocol driver layer
  -> canonical event layer
  -> publishing / dashboard layer
```

There is also a simulator layer used for development and testing.

## Transport Layer

The transport layer is responsible for physically or virtually connecting to devices.

Supported transports include:

- Philips IntelliVue LAN/UDP
- Philips IntelliVue MIB/RS232 serial
- Draeger MEDIBUS TCP
- Draeger MEDIBUS RS232 serial
- Canonical JSON replay over HTTP POST

The gateway runtime decides which transport to start based on command-line arguments. The main runtime is `HeadlessGatewayRuntimeApp`.

For example:

```text
--philips-host       starts Philips LAN/UDP mode
--philips-serial     starts Philips MIB/RS232 mode
--draeger-tcp        starts Draeger TCP mode
--draeger-serial     starts Draeger RS232 mode
--web-only           starts dashboard/API replay mode
```

For serial communication, the project uses serial abstractions such as `SerialProvider`, `SerialSocket`, `SerialProviderFactory`, `TCPSerialProvider`, and `PureJavaCommSerialProvider`. This allows the gateway to work with real RS232 ports, virtual serial pairs, or TCP-backed serial simulation.

## Protocol Driver Layer

This layer understands device-specific protocols.

The Philips driver lives under:

```text
gateway/devices/philips-intellivue/
```

It handles Philips IntelliVue Data Export traffic. Philips protocol objects are mostly binary record classes. Many of them implement small parsing and formatting contracts:

```java
public interface Parseable {
    void parse(ByteBuffer bb);
}

public interface Formatable {
    void format(ByteBuffer bb);
}
```

That means each protocol object knows how to read itself from a binary `ByteBuffer`, and some can write themselves back into protocol format.

Examples of Philips protocol object categories include:

- association messages
- connect indication messages
- data export commands
- actions
- attributes
- observed values
- numeric values
- sample arrays
- alert objects
- patient demographic structures
- unit codes
- object identifiers

The Philips object model is very protocol-oriented. Instead of one large parser doing everything, many small objects represent individual protocol structures.

The Draeger driver lives under:

```text
gateway/devices/draeger-medibus/
```

Its object hierarchy is more compact:

```text
Medibus
  -> RTMedibus
      -> HeadlessDraegerMedibus
```

`Medibus` handles core MEDIBUS framing, commands, checksums, and response parsing. `RTMedibus` adds realtime polling behavior. `HeadlessDraegerMedibus` converts decoded values into canonical JSON events.

Draeger protocol vocabulary is represented mainly as enums and mapping files:

- `Command`
- `MeasuredDataCP1`
- `MeasuredDataCP2`
- `AlarmMessageCP1`
- `AlarmMessageCP2`
- `RealtimeData`
- `Setting`
- `TextMessage`
- `DataType`

These enums map raw MEDIBUS protocol codes to meaningful Java names.

## Driver-to-Canonical Conversion

Once a device-specific driver decodes data, the gateway converts it into canonical events.

The shared helper is `CanonicalEvents`. It fills standard event fields such as:

- `schema_version`
- `topic`
- `unique_device_identifier`
- `gateway_id`
- `bed_id`
- `presentation_time`

Then it adds topic-specific fields.

For example, a numeric vital-sign event contains:

```text
topic = Numeric
metric_id
vendor_metric_id
instance_id
unit_id
value
device_time
presentation_time
```

A waveform event contains:

```text
topic = SampleArray
metric_id
vendor_metric_id
instance_id
unit_id
frequency
values
presentation_time
```

An alert event contains:

```text
topic = PatientAlert or TechnicalAlert
identifier
text
priority
state
presentation_time
```

This is the central architectural boundary: the rest of the system does not need to understand Philips IntelliVue or Draeger MEDIBUS. It only consumes canonical JSON.

## Publishing Pipeline

The publishing layer is built around one interface:

```java
public interface JsonPublisher extends AutoCloseable {
    void publish(Map<String, Object> event) throws IOException;
}
```

Each output target implements this interface.

Publisher implementations include:

- `StdoutJsonPublisher`
- `CompactStdoutPublisher`
- `FileJsonPublisher`
- `HttpJsonPublisher`
- `WebDashboardPublisher`
- `QueuedJsonPublisher`
- `MultiJsonPublisher`
- `OutputFormatPublisher`

The runtime builds a composed publishing pipeline like this:

```text
driver
  -> CanonicalEvents
  -> OutputFormatPublisher
  -> QueuedJsonPublisher
  -> MultiJsonPublisher
  -> stdout / JSONL / HTTP / web dashboard
```

`QueuedJsonPublisher` provides buffering and retry behavior. `MultiJsonPublisher` fans out one event to multiple sinks. `WebDashboardPublisher` serves the built-in dashboard and Server-Sent Events stream.

This allows one decoded event to be sent to several places at the same time.

## Web Dashboard

The built-in dashboard is served by the gateway. It is not a separate frontend build system.

The dashboard receives canonical events from:

```text
GET /events
```

using Server-Sent Events.

It also supports replay and testing through:

```text
POST /api/events
```

That means a replay simulator or external tool can post canonical JSON directly into the dashboard without running a real device protocol driver.

The dashboard does not parse device protocol frames. It only renders already-decoded canonical events.

## Simulator Runtime

The simulator lives under:

```text
simulator/
```

Its main runtime is `SimulatorRuntimeApp`.

The simulator object model is centered around:

- `SimulatedDevice`
- `DeviceDescriptor`
- `DeviceStatus`
- `SimulatorDeviceFactory`
- `SimulatorRegistry`
- `SimulatorManager`
- `RuntimeDeviceConfig`
- `SimulatorConfig`
- `SimulatorApiServer`

The key abstraction is:

```java
public interface SimulatedDevice extends AutoCloseable {
    DeviceDescriptor descriptor();
    DeviceStatus status();
    String lastMessage();
    void start() throws Exception;
    void stop();
}
```

Concrete simulator devices include:

- `CanonicalReplayDevice`
- `LegacyJavaProcessDevice`
- `InProcessSimulatedDevice`

`SimulatorRegistry` stores device factories by type. `SimulatorManager` creates devices from configuration and starts or stops them. `SimulatorApiServer` exposes simulator control over HTTP.

The simulator supports two test styles:

```text
protocol simulation
canonical replay
```

Protocol simulation tests the real driver path:

```text
simulated Philips/Draeger protocol traffic
  -> gateway driver
  -> protocol decode
  -> canonical JSON
```

Canonical replay bypasses protocol decoding:

```text
JSONL fixture
  -> POST /api/events
  -> dashboard/API
```

That is useful for deterministic UI and schema testing.

## Configuration and Scripts

Top-level scripts wrap the Java runtime commands:

- `scripts/run-gateway.sh`
- `scripts/run-simulator.sh`
- `scripts/run-simulator-gui.sh`
- `scripts/start-virtual-serial-pairs.sh`
- `scripts/test-canonical-replay.sh`
- `scripts/test-rs232-simulators.sh`

Simulator configs live in:

```text
simulator/config/
```

Canonical replay fixtures live in:

```text
simulator/fixtures/canonical/
```

JSON schemas live in:

```text
gateway/schemas/
```

## Technical Summary

The software is implemented as a Java 17 gateway with protocol-specific drivers for Philips IntelliVue and Draeger MEDIBUS devices. The drivers handle transport setup, protocol handshakes, polling, frame parsing, checksums, binary decoding, and vendor-specific metric mapping. Decoded values are normalized into canonical JSON events using a shared event model.

Those events are pushed through a composable publisher pipeline that can write to stdout, JSONL files, HTTP endpoints, and a built-in web dashboard. A separate simulator runtime provides virtual protocol devices and canonical replay fixtures so the system can be tested without real medical hardware.
