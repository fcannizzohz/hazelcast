/*
 * Copyright (c) 2008-2026, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.diagnostics;

/**
 * Selects the output format for Hazelcast Diagnostics logs.
 *
 * <p>Configured via {@link DiagnosticsConfig#setLogFormat(DiagnosticsLogFormat)}
 * or the system property {@code hazelcast.diagnostics.log.format}.
 *
 * @since 6.0
 */
public enum DiagnosticsLogFormat {

    /**
     * Human-readable, indented text format (default).
     * Output is fully backward-compatible with all existing STANDARD consumers.
     */
    STANDARD,

    /**
     * Newline-delimited JSON (NDJSON / JSON Lines).
     * <p>Each plugin execution produces one or more self-contained JSON objects,
     * one per line, with the envelope:
     * <pre>{@code {"epoch":<ms>,"name":"<plugin>","content":{...}}}</pre>
     * This format is directly consumable by log aggregators such as Loki,
     * Elasticsearch/Filebeat, and Splunk without multi-line combiner rules.
     * <p>When JSON format is active, existing {@link DiagnosticsPlugin} instances
     * are <em>not</em> scheduled; dedicated JSON plugin implementations in the
     * {@code com.hazelcast.internal.diagnostics.json} package are used instead.
     */
    JSON
}
