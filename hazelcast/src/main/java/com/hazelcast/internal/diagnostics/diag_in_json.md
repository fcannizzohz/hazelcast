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

**`epoch` presence:** Always emitted. The JSON writer emits it directly;
`DiagnosticsLogConverter` derives it from the STANDARD header timestamp when the
source log was written without epoch (second precision, millis always `000`).

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
All metrics from the collection cycle are flat key-value pairs directly inside
`content` — no `"entries"` array, no wrapper objects.  This makes each line a
self-contained snapshot and keeps Loki field extraction simple (each metric is
directly accessible as a label after the `json` pipeline stage).

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
{"epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used(bytes)":1048576,"jvm.memory.heap.used(percent)":68.4,"os.cpu.load":"NA","map.size[instance=myMap]":42}}
```

> **STANDARD vs JSON difference:** STANDARD uses the raw `[metric=...,unit=...]`
> bracket notation and emits one section per metric. JSON uses the parsed key
> format and consolidates all metrics from one cycle into a single flat object.

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
{"epoch":1742385600000,"name":"EventQueues","content":{
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
{"epoch":1742385600000,"name":"SlowOperations","content":{
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
{"epoch":1742385600000,"name":"Lifecycle","content":{"entries":[{"state":"STARTED"}]}}
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
{"epoch":1710849600000,"name":"ConnectionRemoved","content":{"entries":[{"connection":"Connection[192.168.1.11:5701->192.168.1.10:5701]"}],"type":"MEMBER","isAlive":false,"closeReason":"Connection closed by peer","CloseCause":{"entries":[{"exceptionClass":"java.io.EOFException","message":"Connection reset"},{"text":"at java.io.DataInputStream.readFully(DataInputStream.java:197)"},{"text":"at com.hazelcast.internal.nio.IOUtil.readFully(IOUtil.java:88)"}]}}}
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
{"epoch":1742385600000,"name":"ClusterVersionChanged","content":{"entries":[{"version":"5.6"}]}}
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
{"epoch":1742385600000,"name":"MigrationState","content":{
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
{"epoch":1742385600000,"name":"MigrationCompleted","content":{
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
  members: MemberHeartbeatEntry[];  // one entry per member exceeding the deviation threshold
}
interface MemberHeartbeatEntry {
  address:             string;   // member address, e.g. "192.168.1.11:5701"
  "deviation(%)":      number;   // float: percentage over expected interval
  "noHeartbeat(ms)":   number;   // ms since last heartbeat
  "lastHeartbeat(ms)": number;   // epoch ms of last heartbeat
  "now(ms)":           number;   // epoch ms at check time
}
```

```json
{"epoch":1742385600000,"name":"OperationHeartbeat","content":{"members":[{"address":"192.168.1.11:5701","deviation(%)":66.66667,"noHeartbeat(ms)":25000,"lastHeartbeat(ms)":1710849575000,"now(ms)":1710849600000}]}}
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
  | jq '.content.members[] | {address, deviation: .["deviation(%)"]}'

# Only members whose deviation exceeds 100 %
grep '"name":"OperationHeartbeat"' diag.log \
  | jq '.content.members[] | select(.["deviation(%)"] > 100) | {address, deviation: .["deviation(%)"]}'

# Worst deviation across all events (single number)
grep '"name":"OperationHeartbeat"' diag.log \
  | jq '.content.members[].["deviation(%)"]' \
  | jq -s 'max'

# Timeline: epoch + address + deviation for all events, sorted by epoch
grep '"name":"OperationHeartbeat"' diag.log \
  | jq -s '[.[] | .epoch as $e | .content.members[] | {epoch: $e, address, deviation: .["deviation(%)"]}] | sort_by(.epoch)[]'
```

---

### MemberHeartbeatPlugin

**`name`:** `"MemberHeartbeats"` | periodic | **suppressed when no deviation exceeds threshold**

```typescript
interface MemberHeartbeatsContent {
  members: MemberHeartbeatEntry[];  // one entry per member exceeding the deviation threshold
}
interface MemberHeartbeatEntry {
  address:             string;
  "deviation(%)":      number;
  "noHeartbeat(ms)":   number;
  "lastHeartbeat(ms)": number;
  "now(ms)":           number;
}
```

Same shape as `OperationHeartbeat`; different data source and threshold.

```json
{"epoch":1742385600000,"name":"MemberHeartbeats","content":{"members":[{"address":"192.168.1.11:5701","deviation(%)":120.0,"noHeartbeat(ms)":11000,"lastHeartbeat(ms)":1710849589000,"now(ms)":1710849600000}]}}
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
{"epoch":1742385600000,"name":"NetworkingImbalance","content":{
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
{"epoch":1710849600000,"name":"OverloadedConnections","content":{"connection":[{"from":"/192.168.1.10:5701","to":"/192.168.1.11:5701","packetCount":15000,"sampleCount":950,"samples":{"entries":[{"connectionType":"com.hazelcast.map.impl.operation.PutOperation","sampleCount":700,"percentage":0.736},{"connectionType":"com.hazelcast.map.impl.operation.GetOperation","sampleCount":250,"percentage":0.263}]}},{"from":"/192.168.1.10:5701","to":"/192.168.1.11:5701","urgentPacketCount":200,"sampleCount":50,"samples":{}}]}}
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
{"epoch":1742385600000,"name":"MapService","content":{
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
{"epoch":1742385600000,"name":"OperationsProfiler","content":{
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
{"epoch":1710849600000,"name":"Invocations","content":{"Pending":{"entries":[{"description":"BasicInvocation{op=PutOperation, ...}","duration":12000,"unit":"ms"},{"text":"max number of invocations to print reached."}]},"History":{"entries":[{"operation":"com.hazelcast.map.impl.operation.PutOperation","samples":50}]},"SlowHistory":{"entries":[{"operation":"com.hazelcast.map.impl.operation.PutOperation","samples":2}]}}}
```

<!-- expanded for reference:
{"epoch":1710849600000,"name":"Invocations","content":{
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
| `OperationHeartbeatPlugin` | `run` | JSON uses `"members":[{"address":...}]` array; STANDARD uses `"member<addr>"` section key and also appends `lastHeartbeat(date-time)` / `now(date-time)` |
| `MemberHeartbeatPlugin` | `render` | Same as `OperationHeartbeatPlugin` above |
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

---

## Design rationale and change history

This section documents the design decisions taken when adding JSON output
support, and explains why the choices were made the way they were — particularly
with respect to backward compatibility, log ingestion tooling, and the
[`DiagnosticsLogConverter`](#diagnosticslogconverter).

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
2. Produces output directly consumable by Loki, Elasticsearch/OpenSearch,
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
hazelcast.diagnostics.format=JSON
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

## DiagnosticsLogConverter

`DiagnosticsLogConverter` is a utility class that translates between the two
formats. It is **not** used in the hot path during normal cluster operation; its
role is offline conversion and tooling integration.

### STANDARD → JSON (`parseStandard` + `toJson`)

`parseStandard` parses a STANDARD-format multi-line block into an intermediate
`DiagnosticEntry` POJO. `toJson` serializes that POJO to a compact JSON string
following the same schema as live JSON writer output.

**Epoch derivation.** If the source STANDARD entry was written without
`includeEpochTime=true`, the epoch field is absent from the header. Rather than
emitting schema-incompliant JSON with a missing `epoch`, the converter derives
the epoch from the STANDARD header timestamp by parsing `dd-MM-yyyy HH:mm:ss`
against the JVM default timezone. The derived value has millisecond precision
of `000` (second-boundary), which is the best that can be done without the
original millisecond value. The result is always schema-compliant.

### JSON → STANDARD (`parseJson` + `toStandard`)

`parseJson` reads a compact JSON line back into a `DiagnosticEntry`. Because
JSON output no longer carries a `time` field, `parseJson` derives the `time`
string from `epoch` (formatting it as `dd-MM-yyyy HH:mm:ss` in the JVM default
timezone) so that `toStandard` can reconstruct a valid STANDARD header.
`toStandard` then rebuilds the multi-line block with comma-grouped numbers,
escaped special characters, and the correct indent depth. Round-trips are fully
lossless for all data in the `content` object.

### Use cases

| Scenario | How to use |
|---|---|
| Migrate existing STANDARD log files to JSON for ingestion | `parseStandard` each block → `toJson` → write lines to new file |
| Convert live JSON stream back to STANDARD for human inspection | `parseJson` each line → `toStandard` → write blocks to file |
| Validate that a JSON output is schema-compliant | validate each line against `diaglogs.schema.json` |
| Feed historical logs into a tool that only accepts NDJSON | convert with the STANDARD→JSON path, then ship |

---

## Log ingestion with Loki and Grafana

The JSON output format was designed with Loki (and compatible push-model
aggregators) as a primary target. This section shows a concrete integration.

### Why this schema suits log aggregation

Three properties make the output directly ingestible:

1. **One JSON object per line (NDJSON).** Loki, Fluentd, Filebeat, and
   Promtail all work natively with newline-delimited JSON. No multi-line
   combiner rules, no custom parsers for nested indented blocks.

2. **`epoch` is always present and is a Unix millisecond integer.** Loki
   requires a monotonically increasing timestamp per stream. The `epoch` field
   maps directly to Loki's timestamp with no parsing or timezone conversion.
   There is no separate human-readable timestamp field — `epoch` is the single
   source of truth, and Loki / Grafana can display it in any timezone.

3. **`name` is a stable low-cardinality discriminator.** There are roughly 25
   known `name` values (one per plugin/event type). Using `name` as a Loki
   stream label keeps cardinality bounded and enables efficient stream
   selection: `{job="hazelcast", name="SlowOperations"}`.

### Promtail / Grafana Alloy pipeline

The following Promtail `scrape_configs` snippet tails a diagnostics log file
and ships it to Loki with correct timestamps and labels:

```yaml
scrape_configs:
  - job_name: hazelcast_diagnostics
    static_configs:
      - targets: [localhost]
        labels:
          job: hazelcast
          host: __hostname__
          __path__: /var/log/hazelcast/diagnostics*.log

    pipeline_stages:
      # 1. Parse the JSON line into fields
      - json:
          expressions:
            epoch:   epoch
            name:    name
            time:    time

      # 2. Use epoch (Unix ms) as the Loki timestamp — precise and timezone-free
      - timestamp:
          source: epoch
          format: UnixMs

      # 3. Promote 'name' to a stream label for efficient log stream selection
      - labels:
          name:

      # 4. Drop the redundant 'time' field from the log line (optional)
      - labeldrop:
          - time
```

With Grafana Alloy replace the `scrape_configs` block with the equivalent
`loki.source.file` / `loki.process` River pipeline:

```hcl
loki.source.file "hazelcast_diagnostics" {
  targets = [{__path__ = "/var/log/hazelcast/diagnostics*.log", job = "hazelcast"}]
  forward_to = [loki.process.diag.receiver]
}

loki.process "diag" {
  forward_to = [loki.write.default.receiver]

  stage.json {
    expressions = {epoch = "epoch", name = "name"}
  }
  stage.timestamp {
    source = "epoch"
    format = "UnixMs"
  }
  stage.labels {
    values = {name = ""}
  }
}
```

### Example Grafana LogQL queries

Once the pipeline is in place, these LogQL expressions cover the most common
diagnostics use cases:

```logql
# All slow operations in the last hour
{job="hazelcast", name="SlowOperations"}

# Heap memory usage over time (MetricsPlugin)
{job="hazelcast", name="Metric"}
  | json
  | label_format metric=`content_jvm_memory_heap_used_bytes`

# JVM heap used, extracted as a metric for a time-series panel
sum by (host) (
  last_over_time(
    {job="hazelcast", name="Metric"}
      | json
      | unwrap content_jvm_memory_heap_used_bytes [1m]
  )
)

# All connection removals that had a close cause
{job="hazelcast", name="ConnectionRemoved"}
  | json
  | content_CloseCause != ""

# Overloaded connections with more than 10 000 packets
{job="hazelcast", name="OverloadedConnections"}
  | json
  | line_format `{{.content}}`

# Member added/removed events for a cluster topology view
{job="hazelcast", name=~"MemberAdded|MemberRemoved"}
```

> **Field naming in LogQL.** Loki's `| json` stage extracts nested JSON fields
> using underscore-joined paths. `content.jvm.memory.heap.used(bytes)` becomes
> `content_jvm_memory_heap_used_bytes_` (parentheses and dots are replaced with
> underscores). Use `line_format` or `label_format` to handle these names
> programmatically if the exact key name varies.

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
{"epoch":1710849600000,"name":"Metric","content":{"jvm.memory.heap.used(bytes)":1048576,"jvm.memory.heap.used(percent)":68.4,"os.cpu.load":"NA"}}
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
