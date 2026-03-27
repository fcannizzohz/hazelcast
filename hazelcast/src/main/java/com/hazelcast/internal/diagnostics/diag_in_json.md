# Diagnostics Log JSON Format

## Overview

Hazelcast diagnostics can emit log entries in JSON format in addition to the
existing STANDARD (human-readable text) format. Each plugin execution produces a
**single newline-terminated JSON object** on its own line, making the output
directly consumable by log aggregators such as Loki, Filebeat → Elasticsearch,
or Splunk.

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

## Parsing guide

### Line format

Each line is an independent, self-contained JSON object — **one object per
line**, no wrapping array, no commas between lines. Parsers must process the
file line-by-line (NDJSON style).

### Envelope schema

```typescript
interface DiagnosticsLine {
  time:   string;   // always present; format: "dd-MM-yyyy HH:mm:ss" in JVM system timezone
  epoch:  number;   // always present in JSON writer output (milliseconds since Unix epoch);
                    // absent only in DiagnosticsLogConverter output when source STANDARD had no epoch
  name:   string;   // message-type discriminator; see table below
  content: object;  // plugin-specific payload; always an object, never null
}
```

**`time` format:** `DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")` in
`ZoneId.systemDefault()`. This is **day-first, not ISO 8601**. A timestamp such
as `"19-03-2026 14:05:00"` means 19 March 2026 14:05:00 in the JVM's local
timezone. Parsers that need UTC must also consume `epoch`.

**`epoch` presence:** The JSON writer always emits `epoch` regardless of the
`includeEpochTime` setting (which only controls the STANDARD format). The only
case where `epoch` may be absent is when `DiagnosticsLogConverter` converts a
STANDARD log that was originally written without epoch.

### `name` discriminator table

Every JSON line carries a `name` field that identifies its content schema.

| `name` value | Source plugin | Content schema (section below) |
|---|---|---|
| `"BuildInfo"` | `BuildInfoPlugin` | [BuildInfo](#buildinfoplugin) |
| `"ConfigProperties"` | `ConfigPropertiesPlugin` | [ConfigProperties](#configpropertiesplugin) |
| `"SystemProperties"` | `SystemPropertiesPlugin` | [SystemProperties](#systempropertiesplugin) |
| `"Metric"` | `MetricsPlugin` | [Metric](#metricsplugin) |
| `"EventQueues"` | `EventQueuePlugin` | [EventQueues](#eventqueueplugin) |
| `"PendingInvocations"` | `PendingInvocationsPlugin` | [PendingInvocations](#pendinginvocationsplugin) |
| `"SlowOperations"` | `SlowOperationPlugin` | [SlowOperations](#slowoperationplugin) |
| `"Lifecycle"` | `SystemLogPlugin` | [Lifecycle](#lifecycle) |
| `"MemberAdded"` | `SystemLogPlugin` | [MemberAdded/Removed](#memberadded--memberremoved) |
| `"MemberRemoved"` | `SystemLogPlugin` | [MemberAdded/Removed](#memberadded--memberremoved) |
| `"ConnectionAdded"` | `SystemLogPlugin` | [ConnectionAdded/Removed](#connectionadded--connectionremoved) |
| `"ConnectionRemoved"` | `SystemLogPlugin` | [ConnectionAdded/Removed](#connectionadded--connectionremoved) |
| `"ClusterVersionChanged"` | `SystemLogPlugin` | [ClusterVersionChanged](#clusterversionchanged) |
| `"MigrationState"` | `SystemLogPlugin` | [MigrationState](#migrationstate) |
| `"MigrationCompleted"` | `SystemLogPlugin` | [MigrationCompleted/Failed](#migrationcompleted--migrationfailed) |
| `"MigrationFailed"` | `SystemLogPlugin` | [MigrationCompleted/Failed](#migrationcompleted--migrationfailed) |
| `"OperationHeartbeat"` | `OperationHeartbeatPlugin` | [OperationHeartbeat](#operationheartbeatplugin) |
| `"MemberHeartbeats"` | `MemberHeartbeatPlugin` | [MemberHeartbeats](#memberheartbeatplugin) |
| `"NetworkingImbalance"` | `NetworkingImbalancePlugin` | [NetworkingImbalance](#networkingimbalanceplugin) |
| `"OverloadedConnections"` | `OverloadedConnectionsPlugin` | [OverloadedConnections](#overloadedconnectionsplugin) |
| `"OperationsProfiler"` | `OperationProfilerPlugin` | [OperationsProfiler](#operationprofilerplugin) |
| `"InvocationProfiler"` | `InvocationProfilerPlugin` | [InvocationProfiler](#invocationprofilerplugin) |
| `"OperationThreadSamples"` | `OperationThreadSamplerPlugin` | [OperationThreadSamples](#operationthreadsamplereplugin) |
| `"HazelcastInstance"` | `MemberHazelcastInstanceInfoPlugin` | [HazelcastInstance](#memberhazelcastinstanceinfoplugin) |
| `"Invocations"` | `InvocationSamplePlugin` | [Invocations](#invocationsampleplugin) |
| *(any other string)* | `StoreLatencyPlugin` | [StoreLatency](#storelatencyplugin) |

> **`StoreLatencyPlugin` caveat:** `StoreLatencyPlugin` sets `name` to the
> service name (e.g. `"MapService"`, `"CacheService"`). These service names are
> not reserved and not in the table above. Parsers should treat any `name` value
> that does not appear in the table as a potential `StoreLatencyPlugin` line.

### Emission semantics (when a line is produced)

Not every plugin run produces a JSON line. A line is emitted **only when the
plugin calls `startSection`/`endSection`**. Plugins that use lazy (on-demand)
sections suppress output entirely when there is nothing to report:

| Behaviour | Plugins |
|---|---|
| **Always emits** one or more lines per run | `BuildInfoPlugin` (run-once), `ConfigPropertiesPlugin` (run-once), `SystemPropertiesPlugin` (run-once), `MetricsPlugin` (one line per metric), `PendingInvocationsPlugin`, `SlowOperationPlugin`, `OperationProfilerPlugin`, `InvocationProfilerPlugin`, `OperationThreadSamplerPlugin`, `MemberHazelcastInstanceInfoPlugin`, `InvocationSamplePlugin` |
| **Emits one line per event** (event-driven) | `SystemLogPlugin` |
| **Emits per service** (one line per service with data) | `StoreLatencyPlugin` |
| **Suppresses output** when nothing to report | `OperationHeartbeatPlugin`, `MemberHeartbeatPlugin`, `EventQueuePlugin` (below threshold), `OverloadedConnectionsPlugin` (below threshold), `NetworkingImbalancePlugin` |

### `entries` array semantics

The `"entries"` key appears inside a section when the plugin calls
`writeEntry(String)` or `writeStructuredEntry(Object... kvPairs)`:

- **Present** only when at least one such call was made in that section.
- **Absent** (the key does not exist, not `null`, not `[]`) when no such calls
  were made.
- **Always an array** when present — even if only one item was written.
- **Always objects**: every item in the array is a JSON object.
  - `writeEntry(String)` produces `{"text": "<escaped value>"}`.
  - `writeStructuredEntry(kvPairs)` produces an object with plugin-defined keys.

```json
{"entries":[{"text":"plain text"},{"key":"value"},{"text":"another plain text"}]}
```

> **Output is compact JSON** — no spaces between tokens in the actual output.
> Examples in this document use spaces/newlines only for human readability.

### Absent vs null

Fields that may be conditionally absent are **absent** (the key is not emitted),
never JSON `null`. Examples:
- `"CloseCause"` in a `ConnectionRemoved` line is absent when the connection has
  no cause exception.
- `"UpstreamRevision"` in `BuildInfo` is absent when the build has no upstream.

The only value emitted as JSON `null` is when a Java `null` is passed to
`writeStructuredEntry` (e.g. `operationDetails` in `slowInvocations` if the
operation details string is null).

### Numeric types

| Java type | JSON encoding |
|---|---|
| `long` / `int` | integer (no decimal point, no quotes) |
| `double` / `float` | number (may include decimal point; `NaN` is written as the token `NaN`) |
| `boolean` | `true` / `false` (no quotes) |
| `String` | double-quoted, JSON-escaped |

---

## Per-plugin JSON content schemas

> Type notation:
> - `field: T` — required, always present
> - `field?: T` — optional, absent when not applicable
> - `[key: string]: T` — **dynamic key** (the key name is data, not fixed)
> - `(A | B)[]` — mixed-type array
> - `"literal"` — a fixed string constant

---

### BuildInfoPlugin

**`name`:** `"BuildInfo"` | run-once

```typescript
interface BuildInfoContent {
  Build:              string;
  BuildNumber:        number;   // long; written as string in STANDARD to suppress comma-grouping
  Revision:           string;
  UpstreamRevision?:  string;   // absent when no upstream build
  Version:            string;
  SerialVersion:      string;
  Enterprise:         boolean;
}
```

```json
{"time":"19-03-2026 12:00:00","name":"BuildInfo","content":{
  "Build":"20260319",
  "BuildNumber":20260319,
  "Revision":"abc1234",
  "Version":"5.6.0",
  "SerialVersion":"1",
  "Enterprise":false
}}
```

---

### ConfigPropertiesPlugin

**`name`:** `"ConfigProperties"` | run-once

```typescript
interface ConfigPropertiesContent {
  [propertyName: string]: string;   // all values are strings
}
```

```json
{"time":"19-03-2026 12:00:00","name":"ConfigProperties","content":{
  "hazelcast.operation.call.timeout.millis":"60000",
  "hazelcast.slow.operation.detector.enabled":"true"
}}
```

---

### SystemPropertiesPlugin

**`name`:** `"SystemProperties"` | run-once

```typescript
interface SystemPropertiesContent {
  [systemPropertyName: string]: string;   // all values are strings
}
```

```json
{"time":"19-03-2026 12:00:00","name":"SystemProperties","content":{
  "java.version":"21.0.2",
  "os.name":"Linux",
  "user.timezone":"UTC"
}}
```

---

### MetricsPlugin

**`name`:** `"Metric"` | periodic | **one JSON line per metric**

Each call to `metricsRegistry.collect()` produces an independent JSON line.
The entire `content` object contains exactly **one key**: the metric name in
parsed form.

**Metric key format:** `[prefix.]metric[discriminator=value][tag=value](unit)`
where the discriminator/tag and unit parts are omitted when not present.
Unit names are lower-cased (e.g. `bytes`, `ms`, `ns`, `percent`).

```typescript
interface MetricContent {
  [parsedMetricKey: string]: number | string;
  // number when collectLong/collectDouble; string when collectException/collectNoValue
  // key examples: "jvm.memory.heap.used(bytes)", "map.size[instance=myMap]", "os.cpu.load"
}
```

```json
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used(bytes)":1048576}}
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used(percent)":68.4}}
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Metric","content":{"os.cpu.load":"NA"}}
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Metric","content":{"map.size[instance=myMap]":42}}
```

> **Parser note:** All lines in a single metrics collection share the same
> `epoch` value (set once before `metricsRegistry.collect()` is called).

> **STANDARD vs JSON difference:** STANDARD uses the raw `[metric=...,unit=...]`
> bracket notation. JSON uses the human-readable parsed form above.

---

### EventQueuePlugin

**`name`:** `"EventQueues"` | periodic | suppressed when all queues are below threshold

```typescript
interface EventQueuesContent {
  [workerKey: string]: WorkerSection;   // key is "worker=<N>" (1-based index)
}

interface WorkerSection {
  eventCount:   number;    // total events in the queue at sample time
  sampleCount:  number;    // number of events actually sampled
  samples: {
    entries?: SampleEntry[];
  };
}

interface SampleEntry {
  eventType:   string;   // e.g. "IMap 'employees' UPDATED", "ICache 'myCache' CREATED"
  sampleCount: number;
  percentage:  number;   // fraction 0.0–1.0 (not 0–100)
}
```

```json
{"time":"19-03-2026 12:00:00","name":"EventQueues","content":{
  "worker=1": {
    "eventCount": 1500,
    "sampleCount": 100,
    "samples": {
      "entries": [
        { "eventType": "IMap 'employees' UPDATED", "sampleCount": 72, "percentage": 0.72 },
        { "eventType": "IMap 'orders' ADDED",      "sampleCount": 28, "percentage": 0.28 }
      ]
    }
  }
}}
```

---

### PendingInvocationsPlugin

**`name`:** `"PendingInvocations"` | periodic

```typescript
interface PendingInvocationsContent {
  count: number;   // total size of InvocationRegistry
  invocations: {
    entries?: InvocationEntry[];   // absent when registry is empty or all below threshold
  };
}

interface InvocationEntry {
  operation: string;   // fully-qualified operation class name
  count:     number;
}
```

```json
{"time":"19-03-2026 12:00:00","name":"PendingInvocations","content":{
  "count": 3,
  "invocations": {
    "entries": [
      { "operation": "com.hazelcast.map.impl.operation.PutOperation", "count": 2 },
      { "operation": "com.hazelcast.map.impl.operation.GetOperation", "count": 1 }
    ]
  }
}}
```

---

### SlowOperationPlugin

**`name`:** `"SlowOperations"` | periodic

```typescript
interface SlowOperationsContent {
  [operationClassName: string]: SlowOperationEntry;   // dynamic: fully-qualified class name
}

interface SlowOperationEntry {
  invocations: number;
  stackTrace: {
    entries?: StackLineEntry[];
  };
  slowInvocations: {
    entries?: InvocationEntry[];
  };
}

interface StackLineEntry {
  line: string;   // one stack frame, e.g. "at com.example.MyOp.run(MyOp.java:42)"
}

interface InvocationEntry {
  startedAt:        number;           // epoch ms when invocation began
  "duration(ms)":   number;
  operationDetails: string | null;    // null when no details available
}
```

```json
{"time":"19-03-2026 12:00:00","name":"SlowOperations","content":{
  "com.hazelcast.map.impl.operation.PutOperation": {
    "invocations": 2,
    "stackTrace": {
      "entries": [
        { "line": "at com.hazelcast.map.impl.operation.PutOperation.run(PutOperation.java:99)" },
        { "line": "at com.hazelcast.spi.impl.operationexecutor.impl.OperationThread.run(OperationThread.java:176)" }
      ]
    },
    "slowInvocations": {
      "entries": [
        { "startedAt": 1710849540000, "duration(ms)": 8200, "operationDetails": "PutOperation{...}" }
      ]
    }
  }
}}
```

> **STANDARD vs JSON difference:** STANDARD also writes `started(date-time)` (a
> formatted string) per invocation. JSON omits it; `startedAt` (epoch ms) is
> sufficient.

---

### SystemLogPlugin

This plugin emits **one JSON line per cluster event**, not one per plugin run.
The `name` field is the event type. Events are captured by listeners and
flushed on each 1-second scheduler tick.

#### Lifecycle

**`name`:** `"Lifecycle"`

```typescript
interface LifecycleContent {
  entries: [LifecycleEntry];   // always exactly one entry per line
}
interface LifecycleEntry {
  state: "STARTING" | "STARTED" | "SHUTTING_DOWN" | "SHUTDOWN"
       | "MERGING" | "MERGED" | "CLIENT_CONNECTED" | "CLIENT_DISCONNECTED";
}
```

```json
{"time":"19-03-2026 12:00:00","name":"Lifecycle","content":{"entries":[{"state":"STARTED"}]}}
```

#### MemberAdded / MemberRemoved

**`name`:** `"MemberAdded"` | `"MemberRemoved"`

```typescript
interface MembershipContent {
  member: string;   // address of the changed member, e.g. "192.168.1.10:5701"
  Members: {
    entries?: MemberEntry[];   // current member list after the event
  };
}
interface MemberEntry {
  address:  string;    // e.g. "192.168.1.10:5701"
  isThis:   boolean;   // true when this is the local member
  isMaster: boolean;   // true for the first member in the member list (oldest member)
}
```

```json
{"time":"19-03-2026 12:00:00","name":"MemberAdded","content":{
  "member": "192.168.1.11:5701",
  "Members": {
    "entries": [
      { "address": "192.168.1.10:5701", "isThis": true,  "isMaster": true  },
      { "address": "192.168.1.11:5701", "isThis": false, "isMaster": false }
    ]
  }
}}
```

#### ConnectionAdded / ConnectionRemoved

**`name`:** `"ConnectionAdded"` | `"ConnectionRemoved"`

```typescript
interface ConnectionContent {
  entries: [ConnectionEntry];   // always exactly one entry per line
  type?:        string;         // connection type (e.g. "MEMBER"); absent for non-ServerConnection
  isAlive:      boolean;
  closeReason?: string;         // present only for ConnectionRemoved
  CloseCause?:  CloseCauseSection;  // present only when connection has a cause exception
}
interface ConnectionEntry {
  connection: string;   // connection.toString()
}
interface CloseCauseSection {
  entries: (ExceptionEntry | TextEntry)[];
  // first item is always an ExceptionEntry object;
  // subsequent items are stack-frame strings wrapped as TextEntry
}
interface TextEntry {
  text: string;   // plain string from writeEntry(), e.g. a stack frame line
}
interface ExceptionEntry {
  exceptionClass: string;
  message:        string | null;
}
```

```json
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"ConnectionRemoved","content":{"entries":[{"connection":"Connection[192.168.1.11:5701->192.168.1.10:5701]"}],"type":"MEMBER","isAlive":false,"closeReason":"Connection closed by peer","CloseCause":{"entries":[{"exceptionClass":"java.io.EOFException","message":"Connection reset"},{"text":"at java.io.DataInputStream.readFully(DataInputStream.java:197)"},{"text":"at com.hazelcast.internal.nio.IOUtil.readFully(IOUtil.java:88)"}]}}}
```

#### ClusterVersionChanged

**`name`:** `"ClusterVersionChanged"`

```typescript
interface ClusterVersionContent {
  entries: [VersionEntry];
}
interface VersionEntry {
  version: string;   // e.g. "5.6"
}
```

```json
{"time":"19-03-2026 12:00:00","name":"ClusterVersionChanged","content":{"entries":[{"version":"5.6"}]}}
```

#### MigrationState

**`name`:** `"MigrationState"`

```typescript
interface MigrationStateContent {
  startTime:            string;   // "dd-MM-yyyy HH:mm:ss" string in BOTH STANDARD and JSON
  plannedMigrations:    number;
  completedMigrations:  number;
  remainingMigrations:  number;
  "totalElapsedTime(ms)": number;
}
```

```json
{"time":"19-03-2026 12:00:00","name":"MigrationState","content":{
  "startTime": "19-03-2026 12:00:00",
  "plannedMigrations": 271,
  "completedMigrations": 10,
  "remainingMigrations": 261,
  "totalElapsedTime(ms)": 3200
}}
```

> **`startTime` is a formatted string, not epoch ms**, in both formats.
> `MigrationState.getStartTime()` does not expose an epoch value.

#### MigrationCompleted / MigrationFailed

**`name`:** `"MigrationCompleted"` | `"MigrationFailed"`

```typescript
interface ReplicaMigrationContent {
  source:              string;   // source member address, or "null"
  destination:         string;
  partitionId:         number;
  replicaIndex:        number;
  "elapsedTime(ms)":   number;
  MigrationState:      MigrationStateContent;   // nested; same shape as standalone MigrationState
}
```

```json
{"time":"19-03-2026 12:00:00","name":"MigrationCompleted","content":{
  "source": "192.168.1.10:5701",
  "destination": "192.168.1.11:5701",
  "partitionId": 42,
  "replicaIndex": 1,
  "elapsedTime(ms)": 120,
  "MigrationState": {
    "startTime": "19-03-2026 12:00:00",
    "plannedMigrations": 271,
    "completedMigrations": 11,
    "remainingMigrations": 260,
    "totalElapsedTime(ms)": 3320
  }
}}
```

---

### OperationHeartbeatPlugin

**`name`:** `"OperationHeartbeat"` | periodic | **suppressed when no deviation exceeds threshold**

```typescript
interface OperationHeartbeatContent {
  [memberKey: string]: MemberHeartbeatEntry;
  // key is "member" + address.toString(), e.g. "member192.168.1.11:5701"
}
interface MemberHeartbeatEntry {
  "deviation(%)":      number;   // float: percentage over expected interval
  "noHeartbeat(ms)":   number;   // ms since last heartbeat
  "lastHeartbeat(ms)": number;   // epoch ms of last heartbeat
  "now(ms)":           number;   // epoch ms at check time
}
```

```json
{"time":"19-03-2026 12:00:00","name":"OperationHeartbeat","content":{
  "member192.168.1.11:5701": {
    "deviation(%)": 66.66667,
    "noHeartbeat(ms)": 25000,
    "lastHeartbeat(ms)": 1710849575000,
    "now(ms)": 1710849600000
  }
}}
```

> **STANDARD vs JSON difference:** STANDARD also writes `lastHeartbeat(date-time)`
> and `now(date-time)` (formatted strings). JSON omits them.

---

### MemberHeartbeatPlugin

**`name`:** `"MemberHeartbeats"` | periodic | **suppressed when no deviation exceeds threshold**

```typescript
interface MemberHeartbeatsContent {
  [memberKey: string]: MemberHeartbeatEntry;
  // key is "member" + address.toString(), e.g. "member192.168.1.11:5701"
}
interface MemberHeartbeatEntry {
  "deviation(%)":      number;
  "noHeartbeat(ms)":   number;
  "lastHeartbeat(ms)": number;
  "now(ms)":           number;
}
```

Same shape as `OperationHeartbeat`; different data source and threshold.

```json
{"time":"19-03-2026 12:00:00","name":"MemberHeartbeats","content":{
  "member192.168.1.11:5701": {
    "deviation(%)": 120.0,
    "noHeartbeat(ms)": 11000,
    "lastHeartbeat(ms)": 1710849589000,
    "now(ms)": 1710849600000
  }
}}
```

---

### NetworkingImbalancePlugin

**`name`:** `"NetworkingImbalance"` | periodic (disabled by default)

```typescript
interface NetworkingImbalanceContent {
  InputThreads:  ThreadsSection;
  OutputThreads: ThreadsSection;
}
interface ThreadsSection {
  [threadName: string]: ThreadEntry;   // key is NioThread.getName(), e.g. "hz.thread.io.in.0"
}
interface ThreadEntry {
  "frames-percentage":          number;   // double 0.0–100.0 in JSON; formatted string in STANDARD
  "frames":                     number;
  "priority-frames-percentage": number;
  "priority-frames":            number;
  "bytes-percentage":           number;
  "bytes":                      number;
  "events-percentage":          number;
  "events":                     number;
  "handle-count-percentage":    number;
  "handle-count":               number;
  "tasks-percentage":           number;
  "tasks":                      number;
}
```

```json
{"time":"19-03-2026 12:00:00","name":"NetworkingImbalance","content":{
  "InputThreads": {
    "hz.thread.io.in.0": {
      "frames-percentage": 60.0, "frames": 600,
      "priority-frames-percentage": 50.0, "priority-frames": 50,
      "bytes-percentage": 55.5, "bytes": 55500,
      "events-percentage": 66.6, "events": 333,
      "handle-count-percentage": 70.0, "handle-count": 140,
      "tasks-percentage": 80.0, "tasks": 80
    }
  },
  "OutputThreads": {
    "hz.thread.io.out.0": { "...": "same keys" }
  }
}}
```

> **STANDARD vs JSON difference:** `*-percentage` fields are `double` in JSON
> (e.g. `33.333...`). In STANDARD they are formatted strings (e.g. `"33,333.33 %"`).

---

### OverloadedConnectionsPlugin

**`name`:** `"OverloadedConnections"` | periodic (disabled by default) | suppressed when all queues below threshold

```typescript
interface OverloadedConnectionsContent {
  connection: ConnectionEntry[];   // array; one item per overloaded queue scan
}
interface ConnectionEntry {
  from:               string;   // local socket address, e.g. "/192.168.1.10:5701"
  to:                 string;   // remote socket address, e.g. "/192.168.1.11:5701"
  urgentPacketCount?: number;   // present for the priority queue scan
  packetCount?:       number;   // present for the normal queue scan
  // exactly one of the above is present per entry
  sampleCount: number;
  samples: {
    entries?: SampleEntry[];
  };
}
interface SampleEntry {
  connectionType: string;   // deserialized operation class name or packet class name
  sampleCount:    number;
  percentage:     number;   // fraction 0.0–1.0 (not 0–100)
}
```

> **STANDARD vs JSON difference:** STANDARD uses `connection.toString()` as a
> nested section key (may appear twice for normal + priority queues). JSON uses
> a single `"connection"` array so duplicate keys are impossible.

```json
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"OverloadedConnections","content":{"connection":[{"from":"/192.168.1.10:5701","to":"/192.168.1.11:5701","packetCount":15000,"sampleCount":950,"samples":{"entries":[{"connectionType":"com.hazelcast.map.impl.operation.PutOperation","sampleCount":700,"percentage":0.736},{"connectionType":"com.hazelcast.map.impl.operation.GetOperation","sampleCount":250,"percentage":0.263}]}},{"from":"/192.168.1.10:5701","to":"/192.168.1.11:5701","urgentPacketCount":200,"sampleCount":50,"samples":{}}]}}
```

---

### StoreLatencyPlugin

**`name`:** *service name* (dynamic, e.g. `"MapService"`, `"CacheService"`) | periodic (disabled by default)

One JSON line per service with recorded probes. The `name` value is not a fixed
constant — it is whatever string was passed to `newProbe(serviceName, ...)`.

```typescript
// Envelope name = serviceName (dynamic)
interface StoreLatencyContent {
  [dataStructureName: string]: DataStructureEntry;
}
interface DataStructureEntry {
  [methodName: string]: MethodEntry;
}
interface MethodEntry {
  count:              number;
  "totalTime(us)":    number;
  "avg(us)":          number;
  "max(us)":          number;
  "latency-distribution": {
    [bucketLabel: string]: number;   // e.g. "4..7us", "64..127us"; only non-zero buckets
  };
}
```

```json
{"time":"19-03-2026 12:00:00","name":"MapService","content":{
  "employees": {
    "load": {
      "count": 100,
      "totalTime(us)": 4200,
      "avg(us)": 42,
      "max(us)": 310,
      "latency-distribution": {
        "32..63us": 60,
        "64..127us": 35,
        "256..511us": 5
      }
    },
    "store": {
      "count": 50,
      "totalTime(us)": 8100,
      "avg(us)": 162,
      "max(us)": 450,
      "latency-distribution": { "128..255us": 40, "256..511us": 10 }
    }
  }
}}
```

Bucket labels come from `LatencyDistribution.LATENCY_KEYS`. Only buckets with
`value > 0` are emitted.

---

### OperationProfilerPlugin

**`name`:** `"OperationsProfiler"` | periodic

```typescript
interface OperationsProfilerContent {
  [operationClassName: string]: LatencyEntry;   // only classes with count > 0
}
interface LatencyEntry {
  count:            number;
  "totalTime(us)":  number;
  "avg(us)":        number;
  "max(us)":        number;
  "latency-distribution": {
    [bucketLabel: string]: number;
  };
}
```

```json
{"time":"19-03-2026 12:00:00","name":"OperationsProfiler","content":{
  "com.hazelcast.map.impl.operation.PutOperation": {
    "count": 500,
    "totalTime(us)": 12500,
    "avg(us)": 25,
    "max(us)": 310,
    "latency-distribution": { "16..31us": 420, "32..63us": 75, "256..511us": 5 }
  }
}}
```

---

### InvocationProfilerPlugin

**`name`:** `"InvocationProfiler"` | periodic

Same content schema as `OperationsProfiler`; sourced from
`InvocationRegistry.latencyDistributions()`.

```typescript
interface InvocationProfilerContent {
  [operationClassName: string]: LatencyEntry;   // same LatencyEntry as OperationsProfiler
}
```

---

### OperationThreadSamplerPlugin

**`name`:** `"OperationThreadSamples"` | periodic (disabled by default)

```typescript
interface OperationThreadSamplesContent {
  Partition: ThreadSamplesSection;
  Generic:   ThreadSamplesSection;
}
interface ThreadSamplesSection {
  entries?: SampleEntry[];   // absent when no samples recorded for this category
}
interface SampleEntry {
  operation:  string;   // class name, optionally suffixed with "#<dataStructureName>" when includeName=true
  samples:    number;
  percentage: number;   // 0.0–100.0 (not 0.0–1.0)
}
```

```json
{"time":"19-03-2026 12:00:00","name":"OperationThreadSamples","content":{
  "Partition": {
    "entries": [
      { "operation": "com.hazelcast.map.impl.operation.PutOperation", "samples": 12, "percentage": 60.0 },
      { "operation": "com.hazelcast.map.impl.operation.GetOperation", "samples": 8,  "percentage": 40.0 }
    ]
  },
  "Generic": {}
}}
```

> **`percentage` scale:** 0–100 here (unlike `EventQueuePlugin` and
> `OverloadedConnectionsPlugin` which use 0–1 fractions).

---

### MemberHazelcastInstanceInfoPlugin

**`name`:** `"HazelcastInstance"` | periodic

```typescript
interface HazelcastInstanceContent {
  thisAddress:  string;
  isRunning:    boolean;
  isLite:       boolean;
  joined:       boolean;
  nodeState:    "ACTIVE" | "PASSIVE" | "SHUTTING_DOWN" | "SHUT_DOWN" | "null";
  clusterId:    string;   // UUID string, or "null" when not yet assigned
  clusterSize:  number;
  isMaster:     boolean;
  masterAddress: string;   // address string, or "null" when unknown
  Members: {
    entries?: MemberAddressEntry[];
  };
}
interface MemberAddressEntry {
  address: string;   // e.g. "192.168.1.10:5701"
}
```

```json
{"time":"19-03-2026 12:00:00","name":"HazelcastInstance","content":{
  "thisAddress": "192.168.1.10:5701",
  "isRunning": true,
  "isLite": false,
  "joined": true,
  "nodeState": "ACTIVE",
  "clusterId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "clusterSize": 2,
  "isMaster": true,
  "masterAddress": "192.168.1.10:5701",
  "Members": {
    "entries": [
      { "address": "192.168.1.10:5701" },
      { "address": "192.168.1.11:5701" }
    ]
  }
}}
```

---

### InvocationSamplePlugin

**`name`:** `"Invocations"` | periodic (disabled by default)

```typescript
interface InvocationsContent {
  Pending:     PendingSection;
  History:     SampleHistorySection;
  SlowHistory: SampleHistorySection;
}
interface PendingSection {
  entries?: (SlowInvocationEntry | TextEntry)[];
  // TextEntry items: only the sentinel {"text":"max number of invocations to print reached."}
}
interface SlowInvocationEntry {
  description: string;   // invocation.toString()
  duration:    number;   // milliseconds
  unit:        "ms";     // always the literal string "ms"
}
interface SampleHistorySection {
  entries?: SampleEntry[];
}
interface SampleEntry {
  operation: string;   // fully-qualified operation class name
  samples:   number;
}
```

```json
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Invocations","content":{"Pending":{"entries":[{"description":"BasicInvocation{op=PutOperation, ...}","duration":12000,"unit":"ms"},{"text":"max number of invocations to print reached."}]},"History":{"entries":[{"operation":"com.hazelcast.map.impl.operation.PutOperation","samples":50}]},"SlowHistory":{"entries":[{"operation":"com.hazelcast.map.impl.operation.PutOperation","samples":2}]}}}
```

<!-- expanded for reference:
{"time":"19-03-2026 12:00:00","epoch":1710849600000,"name":"Invocations","content":{
  "Pending": {
    "entries": [
      { "description": "BasicInvocation{op=PutOperation, ...}", "duration": 12000, "unit": "ms" },
      { "text": "max number of invocations to print reached." }
    ]
  },
  "History": {
    "entries": [
      { "operation": "com.hazelcast.map.impl.operation.PutOperation", "samples": 50 }
    ]
  },
  "SlowHistory": {
    "entries": [
      { "operation": "com.hazelcast.map.impl.operation.PutOperation", "samples": 2 }
    ]
  }
}}
-->
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

- JSON escaping handles `"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t` inline.
- Numbers (`long`, `double`, `boolean`) are written as JSON literals, not
  quoted strings — preserving their type in JSON consumers.
- The `"entries"` array is opened lazily; the key is absent entirely when no
  entries are written in a section.

#### Format-aware entry writing (acknowledged design compromise)

Plugins that need to emit structured data in JSON mode call `writer.getFormat()`
and branch on the result. The STANDARD path is preserved byte-for-byte. The JSON
path emits a structured object into the shared `"entries"` array.

The following call sites use format-aware branching to emit structured JSON:

| Plugin | Call site | JSON key(s) |
|--------|-----------|-------------|
| `BuildInfoPlugin` | `writeBuildNumber` | `BuildNumber` as `long` (STANDARD: string, no comma grouping) |
| `NetworkingImbalancePlugin` | `writePercentageEntry` | percentage keys as `double` (STANDARD: `"X,XXX.XX %"` string) |
| `OperationHeartbeatPlugin` | `run` | STANDARD appends `lastHeartbeat(date-time)` and `now(date-time)`; JSON omits them |
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
| `OverloadedConnectionsPlugin.renderJson` | connection array items | `"from"`, `"to"`, `"packetCount"`/`"urgentPacketCount"`, `"sampleCount"`, nested `"samples"` section |
| `OverloadedConnectionsPlugin.renderSamples` | connection type samples | `"connectionType"`, `"sampleCount"`, `"percentage"` |

**CloseCause stack trace lines:**
Stack frames within a `"CloseCause"` section are written via `writeEntry` and
therefore appear as `{"text":"<frame>"}` objects in the `entries` array. The
first entry (exception class + message) is a structured object with
`exceptionClass` and `message` keys.

**Exception — `SystemLogPlugin.render(MigrationState)`:**
`startTime` is written with `writeKeyValueEntryAsDateTime` unconditionally in
both formats, producing a `"dd-MM-yyyy HH:mm:ss"` string. No epoch value is
available from `MigrationState.getStartTime()`.
