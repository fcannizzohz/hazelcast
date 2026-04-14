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
    epoch:  number;   // always present; milliseconds since Unix epoch
    name:   string;   // message-type discriminator; see table below
    content: object;  // plugin-specific payload; always an object, never null
}
```

**`epoch` presence:** Always emitted. The JSON writer emits it directly.

**No `time` field:** A human-readable timestamp is intentionally omitted.
It would be redundant with `epoch`, and the STANDARD format uses a non-ISO
day-first format (`dd-MM-yyyy HH:mm:ss`) tied to the JVM timezone — all of
which make `time` a strictly worse timestamp source. Use `epoch` directly.

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
| `"StoreLatency"` | `StoreLatencyPlugin` | [StoreLatency](#storelatencyplugin) |

### Emission semantics (when a line is produced)

Not every plugin run produces a JSON line. A line is emitted **only when the
plugin calls `startSection`/`endSection`**. Plugins that use lazy (on-demand)
sections suppress output entirely when there is nothing to report:

| Behaviour | Plugins |
|---|---|
| **Always emits** one or more lines per run | `BuildInfoPlugin` (run-once), `ConfigPropertiesPlugin` (run-once), `SystemPropertiesPlugin` (run-once), `MetricsPlugin` (one line per cycle), `PendingInvocationsPlugin`, `SlowOperationPlugin`, `OperationProfilerPlugin`, `InvocationProfilerPlugin`, `OperationThreadSamplerPlugin`, `MemberHazelcastInstanceInfoPlugin`, `InvocationSamplePlugin` |
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

JSON `null` appears in two cases:
- `writeNull(key)` — used by `MetricsPlugin.collectNoValue` when a metric was
  registered but had no value at collection time (e.g. `"os.cpu.load": null`).
  In STANDARD format the same case writes `"NA"`.
- `writeString(key, null)` — e.g. `operationDetails` in a `slowInvocations`
  array item when the operation details string is absent.

### Numeric types

| Java type | JSON encoding |
|---|---|
| `long` / `int` | integer (no decimal point, no quotes) |
| `double` / `float` | number (may include decimal point) |
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
{"epoch":1742385600000,"name":"BuildInfo","content":{
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
{"epoch":1742385600000,"name":"ConfigProperties","content":{
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
{"epoch":1742385600000,"name":"SystemProperties","content":{
  "java.version":"21.0.2",
  "os.name":"Linux",
  "user.timezone":"UTC"
}}
```

---

### MetricsPlugin

**`name`:** `"Metric"` | periodic | **one JSON line per collection cycle**

One `metricsRegistry.collect()` call produces exactly **one** JSON line.
All metrics are flat key-value pairs directly inside `content` — no wrapper
arrays.  This keeps field extraction simple (each metric is directly
accessible as a label after the `json` pipeline stage) and minimises line size.

**Metric key format:** `[prefix.]metric[_unit]`
where `prefix` and `unit` are omitted when not present.
Unit names: `bytes`, `ms`, `ns`, `pct`, `count`, `boolean`, `enum`, `us`.

```typescript
interface MetricContent {
  [key: string]: number | null;
  // number — collectLong / collectDouble
  // null   — collectNoValue (metric registered but no value at collection time)
  // collectException metrics are skipped (probe implementation bug; check logs)
  // key examples:
  //   "jvm.memory.heap.used_bytes"   (prefix=jvm.memory, metric=heap.used, unit=BYTES)
  //   "jvm.memory.heap.used_pct"     (prefix=jvm.memory, metric=heap.used, unit=PERCENT)
  //   "os.cpu.load"                  (no unit)
  //   "testLongMetric"               (no prefix, no unit)
}
```

```json
{"epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used_bytes":1048576,"jvm.memory.heap.used_pct":68.4,"os.cpu.load":null,"operation.thread.completedOperationCount_count":2048}}
```

> **STANDARD vs JSON difference:** STANDARD uses the raw `[metric=...,unit=...]`
> bracket notation and emits one section per metric. JSON uses the parsed
> `prefix.metric_unit` key format and consolidates all metrics from one cycle
> into a single flat object, ~48 % smaller than a per-metric array structure.

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
  // Known Hazelcast event types: serviceType + dataStructureName + eventType are all present
  serviceType?:       string;   // "IMap" | "ICache" | "IQueue" | "ISet" | "IList"
  dataStructureName?: string;   // name of the data structure (e.g. "employees")
  eventType?:         string;   // event type name (e.g. "UPDATED"), or runnable class name for unknown types
  sampleCount:        number;
  percentage:         number;   // fraction 0.0–1.0 (not 0–100)
}
// Note: for unknown/custom event runnables, only eventType (class name) is present.
```

```json
{"epoch":1742385600000,"name":"EventQueues","content":{
  "worker=1": {
    "eventCount": 1500,
    "sampleCount": 100,
    "samples": {
      "entries": [
        { "serviceType": "IMap", "dataStructureName": "employees", "eventType": "UPDATED", "sampleCount": 72, "percentage": 0.72 },
        { "serviceType": "IMap", "dataStructureName": "orders",    "eventType": "ADDED",   "sampleCount": 28, "percentage": 0.28 }
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
{"epoch":1742385600000,"name":"PendingInvocations","content":{
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
  slowInvocations?: InvocationEntry[];   // array; absent when no invocations
}

interface StackLineEntry {
  line: string;   // one stack frame, e.g. "at com.example.MyOp.run(MyOp.java:42)"
}

interface InvocationEntry {
  startedAt:        number;           // epoch ms when invocation began
  duration_ms:      number;
  operationDetails: string | null;    // null when no details available
}
```

```json
{"epoch":1742385600000,"name":"SlowOperations","content":{
  "com.hazelcast.map.impl.operation.PutOperation": {
    "invocations": 2,
    "stackTrace": {
      "entries": [
        { "line": "at com.hazelcast.map.impl.operation.PutOperation.run(PutOperation.java:99)" },
        { "line": "at com.hazelcast.spi.impl.operationexecutor.impl.OperationThread.run(OperationThread.java:176)" }
      ]
    },
    "slowInvocations": [
      { "startedAt": 1710849540000, "duration_ms": 8200, "operationDetails": "PutOperation{...}" }
    ]
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
  state: "STARTING" | "STARTED" | "SHUTTING_DOWN" | "SHUTDOWN"
       | "MERGING" | "MERGED" | "CLIENT_CONNECTED" | "CLIENT_DISCONNECTED"
       | "MERGE_FAILED" | "CLIENT_CHANGED_CLUSTER";
}
```

```json
{"epoch":1742385600000,"name":"Lifecycle","content":{"state":"STARTED"}}
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
{"epoch":1742385600000,"name":"MemberAdded","content":{
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
  remoteAddress: string;        // remote endpoint address, e.g. "192.168.1.11:5701"; "null" when unavailable
  type?:        string;         // connection type (e.g. "MEMBER"); absent for non-ServerConnection
  isAlive:      boolean;
  closeReason?: string;         // present only for ConnectionRemoved
  CloseCause?:  CloseCauseSection;  // present only when connection has a cause exception
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
{"epoch":1710849600000,"name":"ConnectionRemoved","content":{"remoteAddress":"192.168.1.11:5701","type":"MEMBER","isAlive":false,"closeReason":"Connection closed by peer","CloseCause":{"entries":[{"exceptionClass":"java.io.EOFException","message":"Connection reset"},{"text":"at java.io.DataInputStream.readFully(DataInputStream.java:197)"},{"text":"at com.hazelcast.internal.nio.IOUtil.readFully(IOUtil.java:88)"}]}}}
```

#### ClusterVersionChanged

**`name`:** `"ClusterVersionChanged"`

```typescript
interface ClusterVersionContent {
  version: string;   // e.g. "5.6"
}
```

```json
{"epoch":1742385600000,"name":"ClusterVersionChanged","content":{"version":"5.6"}}
```

#### MigrationState

**`name`:** `"MigrationState"`

```typescript
interface MigrationStateContent {
  startTime:           string;   // ISO-8601 UTC string, e.g. "2026-03-19T12:00:00Z"
  plannedMigrations:   number;
  completedMigrations: number;
  remainingMigrations: number;
  totalElapsedTime_ms: number;
}
```

```json
{"epoch":1742385600000,"name":"MigrationState","content":{
  "startTime": "2026-03-19T12:00:00Z",
  "plannedMigrations": 271,
  "completedMigrations": 10,
  "remainingMigrations": 261,
  "totalElapsedTime_ms": 3200
}}
```

> **`startTime` is an ISO-8601 UTC string** produced by `Instant.ofEpochMilli(...).toString()`.
> STANDARD format uses a `"dd-MM-yyyy HH:mm:ss"` local-time string for the same field.

#### MigrationCompleted / MigrationFailed

**`name`:** `"MigrationCompleted"` | `"MigrationFailed"`

```typescript
interface ReplicaMigrationContent {
  source:          string;   // source member address, or "null"
  destination:     string;
  partitionId:     number;
  replicaIndex:    number;
  elapsedTime_ms:  number;
  MigrationState:  MigrationStateContent;   // nested; same shape as standalone MigrationState
}
```

```json
{"epoch":1742385600000,"name":"MigrationCompleted","content":{
  "source": "192.168.1.10:5701",
  "destination": "192.168.1.11:5701",
  "partitionId": 42,
  "replicaIndex": 1,
  "elapsedTime_ms": 120,
  "MigrationState": {
    "startTime": "2026-03-19T12:00:00Z",
    "plannedMigrations": 271,
    "completedMigrations": 11,
    "remainingMigrations": 260,
    "totalElapsedTime_ms": 3320
  }
}}
```

---

### OperationHeartbeatPlugin

**`name`:** `"OperationHeartbeat"` | periodic | **suppressed when no deviation exceeds threshold**

```typescript
interface OperationHeartbeatContent {
  members: MemberHeartbeatEntry[];  // one entry per member exceeding the deviation threshold
}
interface MemberHeartbeatEntry {
  address:        string;   // member address, e.g. "192.168.1.11:5701"
  deviation_pct:  number;   // float: percentage over expected interval
  noHeartbeat_ms: number;   // ms since last heartbeat
  lastHeartbeat_ms: number; // epoch ms of last heartbeat
  now_ms:         number;   // epoch ms at check time
}
```

```json
{"epoch":1742385600000,"name":"OperationHeartbeat","content":{"members":[{"address":"192.168.1.11:5701","deviation_pct":66.66667,"noHeartbeat_ms":25000,"lastHeartbeat_ms":1710849575000,"now_ms":1710849600000}]}}
```

> **STANDARD vs JSON difference:** STANDARD uses `"member" + address` as the
> section key (e.g. `member192.168.1.11:5701[...]`) and also writes
> `lastHeartbeat(date-time)` and `now(date-time)` formatted strings.
> JSON lifts the address into an `"address"` field inside a `"members"` array
> and omits the redundant date-time strings.

**jq examples** — given a diagnostics log file `diag.log` (one JSON object per line):

```bash
# All OperationHeartbeat events that contain at least one deviating member
grep '"name":"OperationHeartbeat"' diag.log | jq .

# Deviation percentage for every member in every event
grep '"name":"OperationHeartbeat"' diag.log \
  | jq '.content.members[] | {address, deviation_pct}'

# Only members whose deviation exceeds 100 %
grep '"name":"OperationHeartbeat"' diag.log \
  | jq '.content.members[] | select(.deviation_pct > 100) | {address, deviation_pct}'

# Worst deviation across all events (single number)
grep '"name":"OperationHeartbeat"' diag.log \
  | jq '.content.members[].deviation_pct' \
  | jq -s 'max'

# Timeline: epoch + address + deviation for all events, sorted by epoch
grep '"name":"OperationHeartbeat"' diag.log \
  | jq -s '[.[] | .epoch as $e | .content.members[] | {epoch: $e, address, deviation_pct}] | sort_by(.epoch)[]'
```

---

### MemberHeartbeatPlugin

**`name`:** `"MemberHeartbeats"` | periodic | **suppressed when no deviation exceeds threshold**

```typescript
interface MemberHeartbeatsContent {
  members: MemberHeartbeatEntry[];  // one entry per member exceeding the deviation threshold
}
interface MemberHeartbeatEntry {
  address:          string;
  deviation_pct:    number;
  noHeartbeat_ms:   number;
  lastHeartbeat_ms: number;
  now_ms:           number;
}
```

Same shape as `OperationHeartbeat`; different data source and threshold.

```json
{"epoch":1742385600000,"name":"MemberHeartbeats","content":{"members":[{"address":"192.168.1.11:5701","deviation_pct":120.0,"noHeartbeat_ms":11000,"lastHeartbeat_ms":1710849589000,"now_ms":1710849600000}]}}
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
  frames_pct:        number;   // double 0.0–100.0 in JSON; formatted string in STANDARD
  frames:            number;
  priorityFrames_pct: number;
  priorityFrames:    number;
  bytes_pct:         number;
  bytes:             number;
  events_pct:        number;
  events:            number;
  handleCount_pct:   number;
  handleCount:       number;
  tasks_pct:         number;
  tasks:             number;
}
```

```json
{"epoch":1742385600000,"name":"NetworkingImbalance","content":{
  "InputThreads": {
    "hz.thread.io.in.0": {
      "frames_pct": 60.0, "frames": 600,
      "priorityFrames_pct": 50.0, "priorityFrames": 50,
      "bytes_pct": 55.5, "bytes": 55500,
      "events_pct": 66.6, "events": 333,
      "handleCount_pct": 70.0, "handleCount": 140,
      "tasks_pct": 80.0, "tasks": 80
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
  from:               string;   // local socket address, e.g. "192.168.1.10:5701"
  to:                 string;   // remote socket address, e.g. "192.168.1.11:5701"
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
{"epoch":1710849600000,"name":"OverloadedConnections","content":{"connection":[{"from":"192.168.1.10:5701","to":"192.168.1.11:5701","packetCount":15000,"sampleCount":950,"samples":{"entries":[{"connectionType":"com.hazelcast.map.impl.operation.PutOperation","sampleCount":700,"percentage":0.736},{"connectionType":"com.hazelcast.map.impl.operation.GetOperation","sampleCount":250,"percentage":0.263}]}},{"from":"192.168.1.10:5701","to":"192.168.1.11:5701","urgentPacketCount":200,"sampleCount":50,"samples":{}}]}}
```

---

### StoreLatencyPlugin

**`name`:** `"StoreLatency"` | periodic (disabled by default)

One JSON line per service with recorded probes. `content.service` identifies
which Hazelcast service the line belongs to (e.g. `"MapService"`, `"CacheService"`).
All other keys in `content` are data-structure names containing per-method latency data.

```typescript
interface StoreLatencyContent {
  service:                   string;           // service name passed to newProbe()
  [dataStructureName: string]: DataStructureEntry;
}
interface DataStructureEntry {
  [methodName: string]: MethodEntry;
}
interface MethodEntry {
  count:                   number;
  totalTime_us:            number;
  avg_us:                  number;
  max_us:                  number;
  latency_distribution:    LatencyDistributionBucket[];   // only non-zero buckets
}
interface LatencyDistributionBucket {
  lo_us:  number;   // inclusive lower bound in microseconds
  hi_us:  number;   // inclusive upper bound in microseconds
  count:  number;   // number of observations in this bucket
}
```

```json
{"epoch":1742385600000,"name":"StoreLatency","content":{
  "service": "MapService",
  "employees": {
    "load": {
      "count": 100,
      "totalTime_us": 4200,
      "avg_us": 42,
      "max_us": 310,
      "latency_distribution": [
        { "lo_us": 0,   "hi_us": 63,  "count": 60 },
        { "lo_us": 64,  "hi_us": 127, "count": 35 },
        { "lo_us": 256, "hi_us": 511, "count": 5  }
      ]
    },
    "store": {
      "count": 50,
      "totalTime_us": 8100,
      "avg_us": 162,
      "max_us": 450,
      "latency_distribution": [
        { "lo_us": 128, "hi_us": 255, "count": 40 },
        { "lo_us": 256, "hi_us": 511, "count": 10 }
      ]
    }
  }
}}
```

Only buckets with `count > 0` are emitted. Bucket bounds are computed from
`LatencyDistribution`: `lo(us) = (b == 0) ? 0 : 2^b`, `hi(us) = 2^(b+1) - 1`.

---

### OperationProfilerPlugin

**`name`:** `"OperationsProfiler"` | periodic

```typescript
interface OperationsProfilerContent {
  [operationClassName: string]: LatencyEntry;   // only classes with count > 0
}
interface LatencyEntry {
  count:                  number;
  totalTime_us:           number;
  avg_us:                 number;
  max_us:                 number;
  latency_distribution:   LatencyDistributionBucket[];   // same format as StoreLatencyPlugin
}
// LatencyDistributionBucket: {lo_us, hi_us, count} — see StoreLatencyPlugin
```

```json
{"epoch":1742385600000,"name":"OperationsProfiler","content":{
  "com.hazelcast.map.impl.operation.PutOperation": {
    "count": 500,
    "totalTime_us": 12500,
    "avg_us": 25,
    "max_us": 310,
    "latency_distribution": [
      { "lo_us": 16,  "hi_us": 31,  "count": 420 },
      { "lo_us": 32,  "hi_us": 63,  "count": 75  },
      { "lo_us": 256, "hi_us": 511, "count": 5   }
    ]
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
{"epoch":1742385600000,"name":"OperationThreadSamples","content":{
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
{"epoch":1742385600000,"name":"HazelcastInstance","content":{
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
  Pending:     PendingSection;      // always present; entries absent when no slow invocations
  History:     HistorySection;      // always present; entries absent when no occurrences recorded
  SlowHistory: HistorySection;      // always present; entries absent when no slow occurrences
}

interface PendingSection {
  entries?: (PendingEntry | TextEntry)[];   // absent when no slow invocations in this run
}

interface PendingEntry {
  operation: string;   // operation class name or descriptor from OperationDescriptors.toOperationDesc()
                       // e.g. "GetOperation", "Backup[PutOperation]"
  duration:  number;   // ms since first invocation time
  unit:      "ms";
}

// Emitted when the slow-invocation count exceeds the configured max (default 100)
interface TextEntry {
  text: "max number of invocations to print reached.";
}

interface HistorySection {
  entries?: HistoryEntry[];   // absent when no occurrences accumulated since startup
}

interface HistoryEntry {
  operation: string;   // same descriptor format as PendingEntry.operation
  samples:   number;   // cumulative count since startup (not reset between runs)
}
```

```json
{"epoch":1710849600000,"name":"Invocations","content":{
  "Pending": {
    "entries": [
      { "operation": "PutOperation", "duration": 12000, "unit": "ms" },
      { "operation": "Backup[GetOperation]", "duration": 8500, "unit": "ms" }
    ]
  },
  "History": {
    "entries": [
      { "operation": "PutOperation", "samples": 250 },
      { "operation": "GetOperation", "samples": 120 }
    ]
  },
  "SlowHistory": {
    "entries": [
      { "operation": "PutOperation", "samples": 3 }
    ]
  }
}}
```

> **STANDARD vs JSON difference:** STANDARD writes each pending invocation as a
> multi-line block with every `Invocation.toString()` field expanded. JSON emits
> only the operation descriptor and duration — the composite `toString()` string
> is not used. History/SlowHistory use `{operation, samples}` pairs rather than
> single-key `{className: count}` objects.

---

## Implementation

### JSON subsystem class inventory

| Class | Package | Role |
|-------|---------|------|
| `DiagnosticsLogFormat` | `diagnostics` | Enum: `STANDARD` (default), `JSON` |
| `DiagnosticsConfig` | `diagnostics` | Holds `logFormat` field; serialized via `IdentifiedDataSerializable` |
| `JsonDiagnosticsPlugin` | `diagnostics.json` | Abstract base for all JSON plugins; owns scheduling constants and property helpers |
| `JsonEntryWriter` | `diagnostics.json` | Zero-allocation NDJSON writer; one `startEntry`/`endEntry` pair = one line |
| `JsonDiagnosticsLog` | `diagnostics.json` | `DiagnosticsLog` implementation for JSON mode; owns a single-thread scheduler, manages all `JsonDiagnosticsPlugin` instances, handles rolling files (`.jsonl`) |
| `Json*Plugin` | `diagnostics.json` | One class per plugin/event type; implements `run(JsonEntryWriter)` |
| `JsonStoreLatencyPlugin` | `diagnostics.json` | Extends `StoreLatencyPlugin` so store wrappers can find it via `Diagnostics.getPlugin(StoreLatencyPlugin.class)`; JSON output via `runJson(JsonEntryWriter)` instead of the standard `run(DiagnosticsLogWriter)` |

---

## Wiring into existing Hazelcast diagnostics

### Classes modified outside `diagnostics.json`

Four classes in the existing diagnostics infrastructure were modified to integrate
the JSON subsystem. The changes are deliberately minimal.

#### `DiagnosticsLogFormat` (new file, `diagnostics` package)

New enum with two values: `STANDARD` (unchanged default) and `JSON`.
Adding a new enum rather than a boolean avoids polluting `DiagnosticsConfig`
with a flag whose name would need to change if a third format were ever added.

#### `DiagnosticsConfig` (`diagnostics` package)

Added one field: `DiagnosticsLogFormat logFormat` with default `STANDARD`.
Getter, setter, `equals`/`hashCode`, `toString`, and `IdentifiedDataSerializable`
serialization were updated accordingly. This allows the setting to be propagated
in a clustered environment where config is distributed.

#### `Diagnostics` (`diagnostics` package)

Three additions:

1. **`registerJsonPlugin(JsonDiagnosticsPlugin)`** — delegates to
   `JsonDiagnosticsLog.registerPlugin()` when the active log is a
   `JsonDiagnosticsLog`; no-op otherwise.  Separating registration from
   scheduler start means all plugins can be constructed and wired up before the
   first run fires.

2. **`startJsonLog()`** — calls `JsonDiagnosticsLog.start()` after all plugins
   have been registered.  The split avoids the race condition that existed in an
   earlier draft where `start()` was called inside the constructor before any
   plugins were added.

3. **`getPlugin(Class<P>)` subtype scan** — the existing `pluginsMap` keyed by
   exact class means `getPlugin(StoreLatencyPlugin.class)` would return `null`
   when a `JsonStoreLatencyPlugin` (a subclass) was registered.  A fallback loop
   over `pluginsMap.values()` using `isInstance` was added so callers using the
   base class key still find the right instance.

4. **`JsonDiagnosticsLog` instantiation** — in the factory method that creates
   the `DiagnosticsLog`, `logFormat == JSON` now constructs a `JsonDiagnosticsLog`
   instead of the standard `DiagnosticsLogFile`.

#### `DefaultNodeExtension` (`instance.impl` package)

`registerPlugins(Diagnostics)` now checks the configured format at the start:
if `JSON`, it calls `registerJsonPlugins()` (a new private method) and returns
early, bypassing all the STANDARD plugin registrations.

`registerJsonPlugins()` constructs and registers every `Json*Plugin` via
`diagnostics.registerJsonPlugin()`, then registers `JsonStoreLatencyPlugin` via
the standard `diagnostics.register()` (so it is discoverable by store wrappers),
and finally calls `diagnostics.startJsonLog()` to begin scheduling.

---

### Rationale for a separate `diagnostics.json` package

The JSON output could have been implemented by modifying the existing
`DiagnosticsPlugin` classes and the `DiagnosticsLogWriter` interface. This
approach was explicitly rejected for the following reasons:

**Minimum footprint in shared code.** The standard diagnostics system is used
in production by every Hazelcast deployment.  Modifying the per-plugin `run()`
methods — even carefully — risks regressions in the STANDARD output that are
difficult to detect until production. A separate parallel hierarchy means the
standard code path is structurally untouched: the compiler enforces this.

**Future direction: removing STANDARD format client-side.** The long-term intent
is to standardise on JSON and move STANDARD rendering to a client-side converter
(i.e. a tool that ingests NDJSON and pretty-prints the familiar indented-text
format). When that conversion exists, the STANDARD `DiagnosticsPlugin` classes
and the STANDARD writer can be removed wholesale without touching the JSON path.
The separate package makes that future cleanup a straight deletion rather than
a surgical extraction from shared code.

**Clean schema ownership.** Each `Json*Plugin` owns its output schema end-to-end
without inheriting any of the quirks of the STANDARD format (comma-grouped
numbers, mixed-type `entries` arrays, `date-time` string duplication of epoch).
The JSON schema documented in this file is the sole specification; there is no
ambiguity about which writer quirk applies.

**Testability.** The `JsonEntryWriter` is a pure synchronous in-memory writer
that can be constructed with a `StringWriter` in any unit test.  JSON plugins
can be tested in isolation without a running `HazelcastInstance` for most cases,
and schema validation (via `DiagnosticsSchemaValidator`) is applied to every
plugin in its own test class.

---

## Design rationale and change history

This section documents the design decisions taken when adding JSON output
support, and explains why the choices were made the way they were — particularly
with respect to backward compatibility and log ingestion tooling.

### Context: what existed before

Hazelcast diagnostics originally produced a single format — referred to here as
STANDARD — which is a line-oriented, human-readable text format. Each plugin
execution produces a multi-line block structured as nested `key[value]` sections:

```
19-03-2026 14:30:00 BuildInfo[
                          Build=20260319
                          BuildNumber=20,260,319
                          Revision=abc1234
                          ...
                        ]
```

This format is easy to read in a terminal but difficult to ingest with log
aggregators: it cannot be parsed without a custom multi-line combiner rule, keys
with spaces or special characters require escaping, numbers are formatted for
humans (comma grouping), and there is no stable field schema.

### Objective

Provide a machine-readable output mode that:

1. Requires no changes to existing deployments using STANDARD format.
2. Produces output directly consumable by jq, Loki, Elasticsearch/OpenSearch,
   Splunk, and any other tool that understands NDJSON.
3. Preserves all information that the STANDARD format carries.
4. Allows existing STANDARD log files to be converted to JSON retroactively.

### Decision 1 — opt-in via a new enum, default unchanged

A new `DiagnosticsLogFormat` enum (`STANDARD` | `JSON`) was introduced. The
default is `STANDARD`. Existing deployments are unaffected: nothing in the
startup path changes unless the operator explicitly sets the format.

Configuration is exposed in two ways so both users of the programmatic API
and the legacy property-based configuration can use it:

```java
// programmatic
config.getDiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);

// system property (legacy)
hazelcast.diagnostics.log.format=JSON
```

`DiagnosticsConfig` stores the field and serializes it via
`IdentifiedDataSerializable`, so the setting is propagated correctly in a
clustered environment where the config is distributed.

### Decision 2 — extend the writer interface with default methods only

The pre-existing `DiagnosticsLogWriter` interface is implemented by every plugin
and by test stubs throughout the codebase. Adding abstract methods would have
required updating every implementation. Instead, all new capabilities were added
as `default` methods:

| New default method | Purpose |
|---|---|
| `getFormat()` | returns `STANDARD`; JSON writer overrides to return `JSON` |
| `writeStructuredEntry(Object... kvPairs)` | emits a key/value object into the `entries` array; falls back to `writeEntry("k=v ...")` on STANDARD |
| `startArrayItemSection(String key)` / `endArrayItemSection()` | opens/closes a named-array item; delegates to `startSection`/`endSection` on STANDARD |
| `startSection(String name, long timeMillis)` | timestamped section for `MetricsPlugin`; delegates to `startSection(name)` on STANDARD |

This means the STANDARD writer (`DiagnosticsLogWriterImpl`), all existing test
doubles, and all plugins that do not need format-specific behaviour required
**zero changes**. Backward compatibility is structurally guaranteed by the
compiler: a class that already compiles against the old interface will compile
and run correctly against the new one.

### Decision 3 — format-aware branching at call sites, not inside the writer

Some plugins produce human-formatted data (comma-grouped numbers, `%`-suffixed
strings, redundant date-time copies of already-present epoch values) that is
readable in STANDARD output but meaningless noise in JSON. Rather than trying
to detect and strip these inside the writer, the affected plugins branch on
`writer.getFormat()` at the relevant call sites. The STANDARD path is
byte-for-byte unchanged; the JSON path emits clean typed values.

This is an acknowledged design trade-off: the plugins gain a format awareness
they did not have before, but the alternative — a writer that silently discards
or transforms data depending on format — would be harder to reason about and
harder to test.

### Decision 4 — `epoch` always present in JSON output

The pre-existing `includeEpochTime` flag controls whether STANDARD output
includes the millisecond epoch in the header line. This flag was deliberately
not honoured in JSON mode. Reasons:

- Log aggregators require a machine-precision UTC timestamp to order and
  deduplicate events. `epoch` is timezone-independent and millisecond-precise.
- Making `epoch` optional in JSON output would produce schema-incompliant
  records whenever a user had previously disabled epoch in STANDARD config
  and then switched to JSON without noticing the interaction.
- The `includeEpochTime` constructor parameter is still accepted by
  `DiagnosticsLogWriterJsonImpl` for API compatibility, but is silently
  ignored.

### Decision 5 — `entries` items are always JSON objects

In STANDARD format, `writeEntry(String)` appends a raw string to the current
section. In a naïve JSON translation this would produce `"entries":["value"]`
— a mixed array where some items are strings and others (from
`writeStructuredEntry`) are objects. This would require every consumer to
implement type-checking per array element.

Instead, plain strings are wrapped as `{"text":"<value>"}`, making the rule
uniform: **every item in every `entries` array is a JSON object**. Consumers
can unconditionally apply object field extraction on array elements. The `text`
key is a stable sentinel that distinguishes wrapped plain text from structured
entries.

### Decision 6 — compact JSON (no whitespace)

JSON output contains no spaces between tokens. Each plugin execution produces
exactly one line. This is a deliberate optimisation for log ingestion:

- Line-oriented readers (Promtail, Fluentd, Filebeat) split on newline. A
  single self-contained JSON object per line means no multi-line combiner
  rules are needed.
- Whitespace in JSON adds bytes but carries no information for machine
  consumers.
- Smaller lines mean more events per I/O page and lower network cost when
  shipping logs.

### Decision 7 — human-readable metric key format

`MetricsPlugin` originally wrote the internal `MetricDescriptor.metricString()`
representation as the JSON key. That representation is:

```
[metric=jvm.memory.heap.used,unit=bytes,...]
```

This is machine-parseable but not query-friendly: square brackets and commas in
key names require escaping in most query languages, and the format is not
documented as stable API. For JSON mode a new key format was introduced:

```
[prefix.]metric[discriminator=value][tag=value]*(unit)
```

Examples: `jvm.memory.heap.used(bytes)`, `map.size[instance=myMap]`,
`os.cpu.load`.

This format can be used directly as a Prometheus/Grafana label pattern or as a
LogQL field name. The old `metricString()` format is preserved for STANDARD
output and is not changed.

### Decision 8 — `OverloadedConnections` uses a named array

In STANDARD format `OverloadedConnectionsPlugin` writes each overloaded
connection as a subsection keyed by `connection.toString()`. When the same
connection has both a normal and a priority queue above threshold, the same key
appears twice — valid in the STANDARD line format (it is not a key-value store)
but illegal in JSON (duplicate object keys).

The JSON path uses a `"connection": [...]` array via the new
`startArrayItemSection`/`endArrayItemSection` API. This eliminates duplicate
keys while keeping the same information. The STANDARD path is unchanged.

---

## Performance

### Execution model — why absolute overhead is bounded

Before comparing the two formats, it is important to understand *when*
diagnostics code runs. Every plugin executes on a dedicated background scheduler
thread (`ScheduledExecutorService`), not on any partition, operation, or I/O
thread. A plugin's `run()` method is called periodically — typically every 1–60
seconds depending on plugin configuration — and writes to a buffered
`PrintWriter` backed by the diagnostics log file. **No diagnostic write ever
appears on the hot path of a Hazelcast operation.**

This means that even a measurable overhead in the writer (say, 50 µs more per
plugin execution) is completely invisible to application latency. The section
below analyses the differences anyway, because they are relevant to very
high-frequency plugins (`MetricsPlugin` can fire thousands of times per second
when the metric registry is large) and to the integrity of the STANDARD format
guarantee.

---

### Impact on STANDARD format (the guarantee: zero regression)

The implementation deliberately introduces no changes to the STANDARD code path.
`DiagnosticsLogWriterImpl` is an unmodified class — no methods were altered,
removed, or wrapped. The only additions to the codebase that touch the STANDARD
path are:

1. **New interface default methods** (`getFormat`, `writeStructuredEntry`,
   `startArrayItemSection`, `endArrayItemSection`). Default methods have no
   overhead when the concrete type overrides them, and `DiagnosticsLogWriterImpl`
   overrides all of them. A call to `writer.getFormat()` on a
   `DiagnosticsLogWriterImpl` is a virtual dispatch to the concrete override that
   returns the constant `DiagnosticsLogFormat.STANDARD` — a single-field load.

2. **`getFormat() == JSON` branches in plugins** (approximately 20 call sites).
   In STANDARD mode the `writer` reference always points to a
   `DiagnosticsLogWriterImpl` instance. After the JVM warms up (typically a few
   hundred invocations), the C2 JIT compiler:

    - **Devirtualizes** `getFormat()` at each call site because the call site is
      *monomorphic* — only one concrete type (`DiagnosticsLogWriterImpl`) has
      ever been observed there. C2 guards on the concrete type and inlines the
      method body, replacing the virtual dispatch with a direct load of
      `DiagnosticsLogFormat.STANDARD`.
    - **Folds the branch** `if (STANDARD == JSON)` to compile-time `false`
      because both sides of the comparison are now compile-time constants in the
      inlined body.
    - **Dead-code-eliminates** the JSON branch entirely, producing native code
      identical to what the plugin would have generated if the branch had never
      been written.

   The net result: after JIT warm-up, STANDARD-mode plugins run the same native
   instruction sequence as before. The branches exist in bytecode but not in the
   compiled machine code.

3. **Format check in MetricsPlugin** — `MetricsPlugin` calls `getFormat()` once
   per metric collected. With a large metric registry this call executes very
   frequently. The monomorphic-devirtualization argument above applies fully: in
   STANDARD mode it compiles to a single compare-and-branch instruction that is
   always predicted taken (never entering the JSON path) and eventually
   eliminated by the compiler as unreachable.

**Summary:** switching from the baseline to the current code with
`DiagnosticsLogFormat.STANDARD` (the default) produces no measurable change in
diagnostics throughput or application latency.

---

### JSON format vs STANDARD: concrete differences

When `DiagnosticsLogFormat.JSON` is configured, the writer in use is
`DiagnosticsLogWriterJsonImpl`. The two implementations share the same
zero-allocation philosophy in all hot-path write operations.

#### String escaping

Both writers use a character-by-character switch loop that writes directly to
the `PrintWriter` with no intermediate `String` or buffer. No allocation occurs
regardless of whether the input contains special characters.

`DiagnosticsLogWriterImpl.writeEscaped` escapes `\`, `[`, `]`, `=`, `\n`, `\r`.
`DiagnosticsLogWriterJsonImpl.printEscaped` escapes `"`, `\`, `\b`, `\f`, `\n`,
`\r`, `\t` — the standard JSON set.

#### Long integer formatting

Both writers use a hand-coded digit-extraction loop into a pre-allocated
`char[]` field, then flush the buffer in a single `PrintWriter.write(char[], int, int)`
call. No `String` object is created.

`DiagnosticsLogWriterImpl.writeLong` adds comma grouping (`1,048,576`) for
human readability. `DiagnosticsLogWriterJsonImpl.printLong` omits it, producing
plain decimal (`1048576`) as required by JSON. The JSON variant is therefore
slightly simpler and marginally faster.

#### Timestamp formatting

Both writers reuse a `Calendar` and `Date` pair as instance fields. The
formatted timestamp is assembled into a pre-allocated `char[]` buffer and
flushed in one write. No `String`, `Instant`, or formatter object is allocated.

`DiagnosticsLogWriterImpl` writes digits individually via its `write(int)` →
`StringBuilder` → `char[]` chain. `DiagnosticsLogWriterJsonImpl.printDateTime`
precomputes all six fields (day, month, year, hour, minute, second) into
`char[19]` and flushes the whole buffer in one call, avoiding the per-digit
`StringBuilder` round-trip.

#### Double formatting

Neither writer has a zero-allocation path for `double` values — this is a JDK
limitation; there is no public API before Java 21 for converting a `double` to
decimal characters without allocating. Both writers use the same approach: a
reused `StringBuilder` is appended to, its contents are copied into a reused
`char[]` via `getChars`, and the buffer is flushed. One `StringBuilder` internal
buffer resize may occur if the formatted double is longer than the pre-allocated
capacity, but this is rare and amortised.

#### State management overhead

The JSON writer maintains additional state that the STANDARD writer does not:
`entryArrayOpen[]`, `namedArrayOpen[]`, `namedArrayKey[]` arrays and a
`firstInSection` flag. These three arrays are each 8 elements (`MAX_SECTION_LEVELS`),
fitting entirely in a single cache line. The extra branches in `writeKey()` and
`writeEntry()` to check and update these flags are predictable (they follow the
same pattern every time a section is entered and exited) and add only a handful
of instructions per key written.

#### Output volume

For most plugins JSON output is comparable in byte count to STANDARD. The JSON
format adds quote characters and colons but removes the 26-space indent prefix
on every field line. For deeply nested output (e.g. `SlowOperations` with stack
traces) the JSON output is often *smaller* than STANDARD because the indentation
whitespace is eliminated. For `MetricsPlugin`, each JSON line is roughly:

```
{"epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used(bytes)":1048576,"jvm.memory.heap.used(percent)":68.4,"os.cpu.load":null}}
```

versus the STANDARD equivalent (one section per metric):

```
19-03-2026 12:00:00 Metric[
                          jvm.memory.heap.used\=1,048,576]
```

The JSON line is longer per entry in this case due to the key/value repetition
in the envelope, but it is a single line requiring no multi-line parser.

---

### Summary table

| Aspect | STANDARD (`DiagnosticsLogWriterImpl`) | JSON (`DiagnosticsLogWriterJsonImpl`) |
|---|---|---|
| Hot-path impact | None — writer runs on scheduler thread | None — same |
| STANDARD regression after this change | **Zero** — writer class unchanged; format branches devirtualized and dead-code-eliminated by C2 | N/A |
| `getFormat()` branch cost (STANDARD, post-warm-up) | Eliminated by JIT (monomorphic devirtualization + constant folding) | Field return; minimal |
| String escaping allocations | Zero — character-by-character switch, direct write | Zero — same approach, JSON escape set |
| Long formatting allocations | Zero — hand-coded digit loop into reused `char[]` | Zero — same approach, no comma grouping |
| Timestamp formatting | Zero-alloc — reused `Calendar`/`Date`, direct `char[]` write | Zero-alloc — same approach, single-call `char[19]` flush |
| Double formatting | Low — reused `StringBuilder` + `char[]` flush | Low — same approach |
| Extra state per write | None | Predictable boolean-array checks in `writeKey`/`writeEntry` |
| Output size | Larger for flat sections (indentation whitespace) | Smaller for nested content; larger envelope overhead per `MetricsPlugin` line |

The practical conclusion is: **JSON mode has the same allocation profile as
STANDARD mode on the diagnostics scheduler thread** for all primitive and string
values. The only remaining difference is the additional state-tracking branches
in `writeKey`/`writeEntry` and the `double` formatting path, both of which are
shared limitations. There is no remaining candidate for a targeted
zero-allocation rewrite.

---

