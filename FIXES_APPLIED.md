# Fixes applied in this package

## Runtime/data-integrity fixes

- `MultiJsonPublisher` no longer replays an event into already-successful sinks when another sink fails. This prevents duplicate JSONL/stdout rows when HTTP fails after local file output succeeds.
- `QueuedJsonPublisher` now rejects `shutdownDrainTimeoutMs <= 0` because Java `Thread.join(0)` waits forever.
- `QueuedJsonPublisher` now uses bounded retry backoff multiplication to avoid `long` overflow.
- `QueuedJsonPublisher` logs full exception `toString()` instead of only `getMessage()` for publish/dead-letter failures.
- Multi-device launcher now validates device selection before starting publishers/device threads.
- Multi-device launcher now tracks device supervisor threads, interrupts/joins them on shutdown, then drains/closes the queue.

## Transport/security fixes

- `TCPSerialProvider.connect()` now uses the supplied connect timeout.
- `TCPSerialProvider` now validates `host:port` and supports bracketed IPv6 form `[addr]:port`.
- `SerialProviderFactory.defaultProvider` is now `volatile`, and default-provider initialization is synchronized.
- `HttpJsonPublisher` now refuses plain `http://` by default. Use `--allow-insecure-http true` only for local/test deployments.

## Schema/data-quality fixes

- Philips waveform events now include explicit `unit` and `unitCode` fields set to `null` because this trimmed `SampleArrayObservedValue` model does not expose units.
- Draeger `unitCode` now carries the source metric/code string instead of duplicating the normalized `unit` value.
- Draeger numeric parsing now handles common forms such as `>100`, `<5`, `+12`, `12 %`, `1,234`, and placeholder dashes while preserving `rawValue`.

## Build/offline fixes

- Removed unnecessary JUnit Platform/Vintage configuration; existing tests are JUnit 4 style.
- Changed `gradle/wrapper/gradle-wrapper.properties` to use a local Gradle distribution zip instead of downloading from `services.gradle.org`:

```text
distributionUrl=file\:gradle/wrapper/gradle-7.6-bin.zip
```

Place the official Gradle 7.6 binary distribution zip at:

```text
gradle/wrapper/gradle-7.6-bin.zip
```

The Gradle distribution zip itself is not bundled here because it is about 122 MB. Once placed there, `./gradlew` will not need internet to download Gradle.

## Checksum/resource-leak fixes

- `ChecksumOutputStream` now computes checksums for bulk `write(byte[])` and `write(byte[], int, int)` calls. Previously only the single-byte `write(int)` updated the checksum, causing incorrect MEDIBUS protocol checksums when array writes were used.
- `TCPSerialProvider.connect()` now closes the `Socket` if `connect()` throws, preventing file descriptor leaks on repeated connection failures.
- `HeadlessDraegerGatewayApp.openTransport()` now closes the `Socket` if `getInputStream()` or `getOutputStream()` throws after socket creation.
- `SerialProviderFactory.addCandidates()` now closes `BufferedReader` in a finally block so the stream is released even if `readLine()` throws.
- `HeadlessDraegerGatewayApp` poller thread now catches `InterruptedException` separately, re-sets the interrupt flag, and exits the loop cleanly instead of swallowing the interrupt.
- Fixed raw type `Class` → `Class<?>` in `SerialProviderFactory.locateDefaultProvider()`.

## Build fixes

- Increased Gradle wrapper default heap from 64 MB to 512 MB (`gradlew` line 47) to prevent OOM failures on multi-module builds, especially on constrained devices like Jetson Nano.

## Verification done here

- Compiled the changed headless/common publisher classes with `javac`.
- Compiled the changed Philips/Draeger/multidevice classes with a local SLF4J stub to catch syntax/type errors in modified code paths.
- Could not run a full Gradle build in this sandbox because external Gradle/Maven artifacts are not locally cached here.
