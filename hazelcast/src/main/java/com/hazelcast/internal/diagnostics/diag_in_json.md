# Diagnostics Log JSON Format

## Overview

Hazelcast diagnostics can now emit log entries in JSON format in addition to the
existing STANDARD (human-readable text) format. Each plugin execution produces a
**single line of JSON**, which makes the output directly consumable by log
aggregators (e.g. Filebeat → Elasticsearch, Splunk, Loki).

Introduced in: **5.6** (commit `78f5283deeb`)

---

## Configuration

### Via `DiagnosticsConfig` (programmatic)

```java
config.getDiagnosticsConfig()
      .setEnabled(true)
      .setLogFormat(DiagnosticsLogFormat.JSON);   // default: STANDARD
```

### Via system property (legacy property-based config)

```
hazelcast.diagnostics.log.format=JSON
```

Allowed values: `STANDARD` (default), `JSON`.

---

## Output format

Every plugin execution produces exactly **one newline-terminated JSON object**:

```
{"time":"<dd-MM-yyyy HH:mm:ss>","epoch":<millis>,"name":"<plugin>","content":{...}}
```

| Field     | Type   | Description                                       |
|-----------|--------|---------------------------------------------------|
| `time`    | string | Wall-clock timestamp (system default timezone)    |
| `epoch`   | number | Unix epoch in milliseconds (omitted when `includeEpochTime=false`) |
| `name`    | string | Diagnostics plugin name                           |
| `content` | object | Plugin-specific key-value data (nested as needed) |

### Example — flat key-value

```json
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"SystemProperties","content":{"java.version":"21.0.2","os.name":"Linux"}}
```

### Example — nested sections

```json
{"time":"...","name":"OperationsProfiler","content":{"java.lang.String":{"count":5,"totalTime(us)":120,"avg(us)":24,"max(us)":80,"latency-distribution":{"16..31us":3,"64..127us":2}}}}
```

### The `"entries"` array

The reserved key `"entries"` collects all unkeyed values written by a plugin via
`writeEntry()` (plain text) and `writeStructuredEntry()` (structured object).
Both types share the same array so their order is preserved.

- Always a JSON array, even when only one item was written.
- Items are either a `string` (plain text) or an `object` (structured entry with named fields).
- Omitted entirely when neither method is called in a section.

```json
{"time":"...","name":"SomePlugin","content":{"entries":["plain text"]}}
{"time":"...","name":"SomePlugin","content":{"entries":[{"operation":"PutOp","samples":4}]}}
{"time":"...","name":"SomePlugin","content":{"entries":[
  {"operation":"PutOp","samples":4},
  "max number of invocations to print reached."
]}}
```

---

## Implementation

| Class | Role |
|-------|------|
| `DiagnosticsLogFormat` | Enum: `STANDARD`, `JSON` |
| `DiagnosticsLogWriterFactory` | Creates the right writer based on `DiagnosticsLogFormat` |
| `DiagnosticsLogWriterJsonImpl` | Stateful JSON writer; emits one JSON line per top-level `startSection`/`endSection` pair |
| `DiagnosticsLogConverter` | Utility: parses a STANDARD-format entry string into a `DiagnosticEntry` POJO and can serialize it back to JSON |
| `DiagnosticsConfig` | Holds the `logFormat` field; serialized/deserialized via `IdentifiedDataSerializable` |

### Key design decisions

- JSON escaping is handled inline (`"`, `\`, control characters).
- Numbers (`long`, `double`, `boolean`) are written as JSON literals, not
  quoted strings — preserving their type in JSON consumers.
- `DiagnosticsLogConverter.parseStandard()` can round-trip an existing
  STANDARD log entry to JSON (useful for offline conversion or testing).

#### Format-aware entry writing (acknowledged design compromise)

Plugins that need to emit structured data in JSON mode call `writer.getFormat()`
and branch on the result:

```java
if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
    writer.writeStructuredEntry("operation", item, "samples", count);
} else {
    writer.writeEntry(item + " samples=" + count);   // STANDARD: unchanged
}
```

The STANDARD path is preserved byte-for-byte. The JSON path emits a structured
object into the shared `"entries"` array. This is intentionally verbose (two
code paths per call site) to avoid any risk of changing the STANDARD output.

The following call sites use format-aware branching to emit structured JSON:

| Plugin | Call site | JSON key(s) |
|--------|-----------|-------------|
| `BuildInfoPlugin` | `writeBuildNumber` | `BuildNumber` as `long` (STANDARD: string, no comma grouping) |
| `NetworkingImbalancePlugin` | `writePercentageEntry` | percentage keys as `double` (STANDARD: `"X,XXX.XX %"` string) |
| `OperationHeartbeatPlugin` | `run` | STANDARD appends `lastHeartbeat(date-time)` and `now(date-time)`; JSON omits them (epoch ms values sufficient) |
| `MemberHeartbeatPlugin` | `render` | STANDARD appends `lastHeartbeat(date-time)` and `now(date-time)`; JSON omits them |
| `SlowOperationPlugin.renderStackTrace` | stack trace lines | `"line"` |
| `SlowOperationPlugin.renderInvocations` | per invocation | `"startedAt"`, `"duration(ms)"`, `"operationDetails"` (STANDARD also writes `started(date-time)`) |
| `SystemLogPlugin.render(LifecycleEvent)` | lifecycle state | `"state"` |
| `SystemLogPlugin.render(Version)` | cluster version | `"version"` |
| `SystemLogPlugin.render(MembershipEvent)` | member list entries | `"address"`, `"isThis"`, `"isMaster"` |
| `SystemLogPlugin.render(ConnectionEvent)` | connection string | `"connection"` |
| `SystemLogPlugin.renderConnectionClose` | close cause | `"exceptionClass"`, `"message"` |
| `MemberHazelcastInstanceInfoPlugin.run` | member addresses | `"address"` |
| `PendingInvocationsPlugin.renderInvocations` | pending ops | `"operation"`, `"count"` |
| `EventQueuePlugin.renderSamples` | event type samples | `"eventType"`, `"sampleCount"`, `"percentage"` |
| `InvocationSamplePlugin.runCurrent` | slow pending invocations | `"description"`, `"duration"`, `"unit"` |
| `InvocationSamplePlugin.renderOccurrences` | invocation samples | `"operation"`, `"samples"` |
| `OperationThreadSamplerPlugin.write` | thread samples | `"operation"`, `"samples"`, `"percentage"` |
| `OverloadedConnectionsPlugin.renderSamples` | connection type samples | `"connectionType"`, `"sampleCount"`, `"percentage"` |

**Exception — CloseCause stack trace lines in `SystemLogPlugin.renderConnectionClose`:**
The individual `StackTraceElement` lines within a close cause continue to call
`writeEntry(String)` unchanged. They are already inside a dedicated
`"CloseCause"` section whose first entry provides the structured exception class
and message; adding a second level of wrapping for every frame would add noise
without value.

**Exception — `SystemLogPlugin.render(MigrationState)`:**
`writeKeyValueEntryAsDateTime("startTime", ...)` is called unconditionally (both
STANDARD and JSON), writing a formatted `"dd-MM-yyyy HH:mm:ss"` string in both
formats. No epoch equivalent is available from `MigrationState.getStartTime()`
so the string representation is the only option.

`writeStructuredEntry` is defined on the `DiagnosticsLogWriter` interface with a
default implementation that formats as `key=value` pairs and delegates to
`writeEntry`, so STANDARD writers and test doubles do not need to override it.

#### MetricsPlugin — one line per metric

`MetricsPlugin` does not use a single top-level section. Instead it calls
`writer.writeSectionKeyValue("Metric", timeMillis, metricKey, value)` for each
collected metric. In JSON mode, this produces one JSON line per metric:

```json
{"time":"...","epoch":1710849600000,"name":"Metric","content":{"[metric=jvm.memory.heap.used]":1048576}}
```

This design predates the JSON format; each metric is self-contained so log
aggregators can filter by metric name without parsing nested JSON.

---

## Per-plugin JSON output specification

> **Reading guide:** Each section shows the JSON `content` object only (the
> envelope `{"time":...,"epoch":...,"name":...,"content":{...}}` is omitted).
> Types: `S`=string, `N`=number (long/double), `B`=boolean, `[]`=array, `{}`=object.

---

### BuildInfoPlugin

**name:** `"BuildInfo"` | **schedule:** run-once

```json
{
  "Build": "S",
  "BuildNumber": "N",
  "Revision": "S",
  "UpstreamRevision": "S",     // optional
  "Version": "S",
  "SerialVersion": "S",
  "Enterprise": "B"
}
```

> `BuildNumber` is a `long` in JSON. In STANDARD format it is written as a
> plain string to prevent comma-grouping (e.g. `"20250101"` not `"20,250,101"`).

---

### ConfigPropertiesPlugin

**name:** `"ConfigProperties"` | **schedule:** run-once

```json
{
  "<hazelcast-property-name>": "S",
  ...
}
```

All values are strings regardless of the underlying type.

---

### SystemPropertiesPlugin

**name:** `"SystemProperties"` | **schedule:** run-once

```json
{
  "<system-property-name>": "S",
  ...
}
```

All values are strings regardless of the underlying type.

---

### MetricsPlugin

**name:** `"Metric"` | **schedule:** periodic | **one JSON line per metric**

```json
{ "<[metric=fully.qualified.metric.string]>": "N" }
```

Each `MetricsRegistry.collect()` callback produces an independent JSON line with
a single key-value pair. The value type is `long`, `double`, or `string`
depending on `collectLong`, `collectDouble`, `collectException`, `collectNoValue`.

---

### EventQueuePlugin

**name:** `"EventQueues"` | **schedule:** periodic | **output only when queue size ≥ threshold**

```json
{
  "worker=<N>": {
    "eventCount": "N",
    "sampleCount": "N",
    "samples": {
      "entries": [
        { "eventType": "S", "sampleCount": "N", "percentage": "N" }
      ]
    }
  }
}
```

`"entries"` is omitted if the sample count is below threshold or the queue is
empty.

---

### PendingInvocationsPlugin

**name:** `"PendingInvocations"` | **schedule:** periodic

```json
{
  "count": "N",
  "invocations": {
    "entries": [
      { "operation": "S", "count": "N" }
    ]
  }
}
```

`"entries"` is omitted when the registry is empty or all counts are below
threshold.

---

### SlowOperationPlugin

**name:** `"SlowOperations"` | **schedule:** periodic

```json
{
  "<fully.qualified.OperationClassName>": {
    "invocations": "N",
    "stackTrace": {
      "entries": [
        { "line": "S" }
      ]
    },
    "slowInvocations": {
      "entries": [
        {
          "startedAt": "N",
          "duration(ms)": "N",
          "operationDetails": "S"
        }
      ]
    }
  }
}
```

> In STANDARD format, `slowInvocations` also writes `started(date-time)` (a
> formatted string) for each invocation. This is omitted in JSON because
> `startedAt` (epoch ms) is sufficient for machine consumption.

---

### SystemLogPlugin

**name:** varies per event | **schedule:** 1-second poll | **one JSON line per event**

This plugin emits a separate top-level JSON object for each event it dequeues.
Possible top-level section names and their `content` shapes:

#### `"Lifecycle"`

```json
{
  "entries": [ { "state": "S" } ]
}
```

`state` values: `STARTING`, `STARTED`, `SHUTTING_DOWN`, `SHUTDOWN`,
`MERGING`, `MERGED`, `CLIENT_CONNECTED`, `CLIENT_DISCONNECTED`.

#### `"MemberAdded"` / `"MemberRemoved"`

```json
{
  "member": "S",
  "Members": {
    "entries": [
      { "address": "S", "isThis": "B", "isMaster": "B" }
    ]
  }
}
```

#### `"ConnectionAdded"` / `"ConnectionRemoved"`

```json
{
  "entries": [ { "connection": "S" } ],
  "type": "S",
  "isAlive": "B",
  "closeReason": "S",
  "CloseCause": {
    "entries": [
      { "exceptionClass": "S", "message": "S" },
      "<stack-frame-string>",
      "..."
    ]
  }
}
```

`"type"` is present only when the connection is a `ServerConnection`.
`"closeReason"` and `"CloseCause"` are present only for `"ConnectionRemoved"`.
`"CloseCause"` is present only when a `Throwable` close cause exists.
Stack trace frames within `"CloseCause"` are plain strings (not structured
objects); only the first entry (exception class + message) is structured.

#### `"ClusterVersionChanged"`

```json
{
  "entries": [ { "version": "S" } ]
}
```

#### `"MigrationState"`

```json
{
  "startTime": "S",
  "plannedMigrations": "N",
  "completedMigrations": "N",
  "remainingMigrations": "N",
  "totalElapsedTime(ms)": "N"
}
```

> `"startTime"` is a formatted datetime string (`"dd-MM-yyyy HH:mm:ss"`) in
> **both** STANDARD and JSON formats. `MigrationState.getStartTime()` does not
> expose an epoch value, so no numeric equivalent is available.

#### `"MigrationCompleted"` / `"MigrationFailed"`

```json
{
  "source": "S",
  "destination": "S",
  "partitionId": "N",
  "replicaIndex": "N",
  "elapsedTime(ms)": "N",
  "MigrationState": {
    "startTime": "S",
    "plannedMigrations": "N",
    "completedMigrations": "N",
    "remainingMigrations": "N",
    "totalElapsedTime(ms)": "N"
  }
}
```

---

### OperationHeartbeatPlugin

**name:** `"OperationHeartbeat"` | **schedule:** periodic | **output only when deviation ≥ threshold**

```json
{
  "member<address>": {
    "deviation(%)": "N",
    "noHeartbeat(ms)": "N",
    "lastHeartbeat(ms)": "N",
    "now(ms)": "N"
  }
}
```

> In STANDARD format, each member sub-section also contains
> `lastHeartbeat(date-time)` and `now(date-time)` (formatted strings). These
> are omitted in JSON because the epoch-ms values are sufficient.

---

### MemberHeartbeatPlugin

**name:** `"MemberHeartbeats"` | **schedule:** periodic | **output only when deviation ≥ threshold**

```json
{
  "member<address>": {
    "deviation(%)": "N",
    "noHeartbeat(ms)": "N",
    "lastHeartbeat(ms)": "N",
    "now(ms)": "N"
  }
}
```

> Same datetime-string omission as `OperationHeartbeatPlugin`.

---

### NetworkingImbalancePlugin

**name:** `"NetworkingImbalance"` | **schedule:** periodic (disabled by default)

```json
{
  "InputThreads": {
    "<thread-name>": {
      "frames-percentage": "N",
      "frames": "N",
      "priority-frames-percentage": "N",
      "priority-frames": "N",
      "bytes-percentage": "N",
      "bytes": "N",
      "events-percentage": "N",
      "events": "N",
      "handle-count-percentage": "N",
      "handle-count": "N",
      "tasks-percentage": "N",
      "tasks": "N"
    }
  },
  "OutputThreads": {
    "<thread-name>": { "...": "same keys as above" }
  }
}
```

> Percentage fields are `double` in JSON (e.g. `33.333...`) and a formatted
> string in STANDARD (e.g. `"33,333.33 %"`).

---

### OverloadedConnectionsPlugin

**name:** `"OverloadedConnections"` | **schedule:** periodic (disabled by default)

```json
{
  "<connection.toString()>": {
    "urgentPacketCount": "N",
    "sampleCount": "N",
    "samples": {
      "entries": [
        { "connectionType": "S", "sampleCount": "N", "percentage": "N" }
      ]
    }
  }
}
```

`"urgentPacketCount"` is used for the priority queue; `"packetCount"` for the
normal queue. Both may appear for the same connection (two separate
`startSection`/`endSection` pairs).

---

### StoreLatencyPlugin

**name:** `"<serviceName>"` | **schedule:** periodic (disabled by default)

```json
{
  "<dataStructureName>": {
    "<methodName>": {
      "count": "N",
      "totalTime(us)": "N",
      "avg(us)": "N",
      "max(us)": "N",
      "latency-distribution": {
        "<bucket-label>": "N"
      }
    }
  }
}
```

Bucket labels are from `LatencyDistribution.LATENCY_KEYS` (e.g. `"0us"`,
`"1us"`, `"2..3us"`, `"4..7us"`, …, `"134217728us.."`). Only non-zero buckets
are emitted.

---

### OperationProfilerPlugin

**name:** `"OperationsProfiler"` | **schedule:** periodic

```json
{
  "<fully.qualified.OperationClassName>": {
    "count": "N",
    "totalTime(us)": "N",
    "avg(us)": "N",
    "max(us)": "N",
    "latency-distribution": {
      "<bucket-label>": "N"
    }
  }
}
```

Only operations with at least one recorded sample are emitted.

---

### InvocationProfilerPlugin

**name:** `"InvocationProfiler"` | **schedule:** periodic

Same structure as `OperationProfilerPlugin` but sourced from
`InvocationRegistry.latencyDistributions()`.

```json
{
  "<fully.qualified.OperationClassName>": {
    "count": "N",
    "totalTime(us)": "N",
    "avg(us)": "N",
    "max(us)": "N",
    "latency-distribution": {
      "<bucket-label>": "N"
    }
  }
}
```

---

### OperationThreadSamplerPlugin

**name:** `"OperationThreadSamples"` | **schedule:** periodic (disabled by default)

```json
{
  "Partition": {
    "entries": [
      { "operation": "S", "samples": "N", "percentage": "N" }
    ]
  },
  "Generic": {
    "entries": [
      { "operation": "S", "samples": "N", "percentage": "N" }
    ]
  }
}
```

`"entries"` is omitted for a category when no samples have been recorded.

> In STANDARD format, these are written as `writeKeyValueEntry(name, "count pct%")`
> flat key-value pairs, not as structured entries.

---

### MemberHazelcastInstanceInfoPlugin

**name:** `"HazelcastInstance"` | **schedule:** periodic

```json
{
  "thisAddress": "S",
  "isRunning": "B",
  "isLite": "B",
  "joined": "B",
  "nodeState": "S",
  "clusterId": "S",
  "clusterSize": "N",
  "isMaster": "B",
  "masterAddress": "S",
  "Members": {
    "entries": [
      { "address": "S" }
    ]
  }
}
```

> In STANDARD format, member addresses are written as plain `writeEntry` strings.
> In JSON they are structured objects with a single `"address"` key.

---

### InvocationSamplePlugin

**name:** `"Invocations"` | **schedule:** periodic (disabled by default)

```json
{
  "Pending": {
    "entries": [
      { "description": "S", "duration": "N", "unit": "S" },
      "max number of invocations to print reached."
    ]
  },
  "History": {
    "entries": [
      { "operation": "S", "samples": "N" }
    ]
  },
  "SlowHistory": {
    "entries": [
      { "operation": "S", "samples": "N" }
    ]
  }
}
```

- `"Pending"` entries contain slow invocations (duration ≥ threshold). `unit`
  is always `"ms"`. The sentinel string `"max number of invocations to print
  reached."` may appear as a plain string entry if the configured max is hit.
- `"History"` accumulates all invocations seen (regardless of speed).
- `"SlowHistory"` accumulates only slow invocations.
- `"entries"` is omitted for a section when no items were collected.

> In STANDARD format, `"Pending"` entries are written as
> `writeEntry(invocation + " duration=" + durationMs + " ms")`.

---

## Issues found during validation (all fixed)

1. **`DiagnosticsConfig.equals()` and `hashCode()` omitted `logFormat`.**
   Two configs differing only in log format compared as equal. Fixed by adding
   `logFormat` to both methods and `toString()`.

2. **`writeEntry()` produced duplicate `"entry"` keys (invalid JSON per RFC 8259).**
   Fixed by collecting all `writeEntry()` calls in a section into a single
   `"entries": [...]` array. The key was also renamed from `"entry"` to `"entries"`
   to make the always-array contract explicit. A single call still produces an
   array of length 1. The `"entries"` key is omitted entirely when no entries
   are written.

3. **`OperationHeartbeatPlugin` wrote `lastHeartbeat(date-time)` and `now(date-time)` in JSON.**
   These are redundant when the epoch-ms values are present. Fixed by guarding
   both `writeKeyValueEntryAsDateTime` calls with
   `writer.getFormat() != DiagnosticsLogFormat.JSON` (matching `MemberHeartbeatPlugin`
   which already had this guard).

4. **`SlowOperationPlugin.renderInvocations` wrote multiple invocations as flat key-value pairs.**
   For multiple invocations, keys like `startedAt` and `duration(ms)` would be
   repeated, violating RFC 8259. Fixed by using `writeStructuredEntry` per
   invocation in JSON mode. The `started(date-time)` entry is now written only
   in STANDARD mode.

---

## Tests

| Test class | Covers |
|------------|--------|
| `DiagnosticsLogWriterJsonImplTest` | Basic JSON output, escaping, epoch field, nested sections, `writeStructuredEntry` types and mixed arrays, boundary conditions |
| `DiagnosticsLogConverterTest` | STANDARD→`DiagnosticEntry`→JSON round-trip, edge cases, null epoch handling |
| `DiagnosticsLogWriterFactoryTest` | Factory dispatch: JSON→`DiagnosticsLogWriterJsonImpl`, STANDARD→`DiagnosticsLogWriterImpl`, epoch flag propagation |
| `DiagnosticsPluginsConverterTest` | Real plugin output converted to JSON |
| `DiagnosticsConverterRoundTripTest` | Full STANDARD→parse→JSON→parse→STANDARD round-trip for all 18 plugins |
| `DiagnosticsConfigTest` | `equals`, `hashCode`, `toString`, serialization coverage for `logFormat` |
| `DiagnosticsTest` | Integration: `DiagnosticsConfig.logFormat` wired end-to-end |
| `SystemLogPluginTest` | JSON membership event produces `"address"`, `"isThis"`, `"isMaster"` keys; date-time entries absent |
| `MemberHeartbeatPluginTest` | JSON format omits `lastHeartbeat(date-time)` and `now(date-time)` |
| `InvocationPluginTest` | JSON invocation samples produce `"operation"`, `"samples"`, `"percentage"` keys |
| `EventQueuePluginTest` | JSON event samples produce `"eventType"`, `"sampleCount"`, `"percentage"` keys |
| `StoreLatencyPluginTest` | JSON format emits structured latency-distribution section |
