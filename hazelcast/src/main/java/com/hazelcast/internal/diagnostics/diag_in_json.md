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
{"time":"...","name":"OperationThreadSampler","content":{"thread-0":{"taskName":"map:put","durationMs":12}}}
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

| Plugin | Value | JSON key(s) |
|--------|-------|-------------|
| `SlowOperationPlugin.renderStackTrace` | stack trace lines | `"line"` |
| `SystemLogPlugin.render(LifecycleEvent)` | lifecycle state | `"state"` |
| `SystemLogPlugin.render(Version)` | cluster version | `"version"` |
| `SystemLogPlugin.render(MembershipEvent)` | member list entries | `"address"`, `"isThis"`, `"isMaster"` |
| `SystemLogPlugin.render(ConnectionEvent)` | connection string | `"connection"` |
| `SystemLogPlugin.renderConnectionClose` | close cause | `"exceptionClass"`, `"message"` |
| `PendingInvocationsPlugin.renderInvocations` | pending ops | `"operation"`, `"count"` |
| `EventQueuePlugin.renderSamples` | event type samples | `"eventType"`, `"sampleCount"`, `"percentage"` |
| `InvocationSamplePlugin.renderOccurrences` | invocation samples | `"operation"`, `"samples"`, `"percentage"` |

**Exception — CloseCause stack trace lines in `SystemLogPlugin.renderConnectionClose`:**
The individual `StackTraceElement` lines within a close cause continue to call
`writeEntry(String)` unchanged. They are already inside a dedicated
`"CloseCause"` section whose first entry provides the structured exception class
and message; adding a second level of wrapping for every frame would add noise
without value.

`writeStructuredEntry` is defined on the `DiagnosticsLogWriter` interface with a
default implementation that formats as `key=value` pairs and delegates to
`writeEntry`, so STANDARD writers and test doubles do not need to override it.

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

---

## Tests

| Test class | Covers |
|------------|--------|
| `DiagnosticsLogWriterJsonImplTest` | Basic JSON output, escaping, epoch field, nested sections, `writeStructuredEntry` types and mixed arrays, boundary conditions |
| `DiagnosticsLogConverterTest` | STANDARD→`DiagnosticEntry`→JSON round-trip, edge cases, null epoch handling |
| `DiagnosticsLogWriterFactoryTest` | Factory dispatch: JSON→`DiagnosticsLogWriterJsonImpl`, STANDARD→`DiagnosticsLogWriterImpl`, epoch flag propagation |
| `DiagnosticsPluginsConverterTest` | Real plugin output converted to JSON |
| `DiagnosticsConfigTest` | `equals`, `hashCode`, `toString`, serialization coverage for `logFormat` |
| `DiagnosticsTest` | Integration: `DiagnosticsConfig.logFormat` wired end-to-end |
| `SystemLogPluginTest` | JSON membership event produces `"address"`, `"isThis"`, `"isMaster"` keys |
| `InvocationPluginTest` | JSON invocation samples produce `"operation"`, `"samples"`, `"percentage"` keys |
| `EventQueuePluginTest` | JSON event samples produce `"eventType"`, `"sampleCount"`, `"percentage"` keys |