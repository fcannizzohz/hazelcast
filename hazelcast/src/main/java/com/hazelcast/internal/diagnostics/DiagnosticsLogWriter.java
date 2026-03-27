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
import java.io.PrintWriter;

public interface DiagnosticsLogWriter {

    void init(PrintWriter printWriter);

    void resetSectionLevel();

    /**
     * Returns the output format this writer produces.
     * Defaults to {@link DiagnosticsLogFormat#STANDARD}.
     *
     * <p>Plugins that need to emit structured data differently based on the
     * active format should check this value and branch accordingly — calling
     * {@link #writeStructuredEntry(Object...)} on the JSON path and the
     * existing hand-crafted {@link #writeEntry(String)} on the STANDARD path.
     * This is an acknowledged design compromise: it keeps the STANDARD output
     * fully unchanged while enabling machine-parseable JSON for the same data.
     */
    default DiagnosticsLogFormat getFormat() {
        return DiagnosticsLogFormat.STANDARD;
    }

    /**
     * Writes a structured entry using alternating {@code key, value, key, value, ...} pairs.
     *
     * <p>Plugins must only call this method from the JSON branch of a format check:
     * <pre>{@code
     *   if (writer.getFormat() == DiagnosticsLogFormat.JSON) {
     *       writer.writeStructuredEntry("operation", item, "samples", count);
     *   } else {
     *       writer.writeEntry(item + " samples=" + count);  // unchanged STANDARD path
     *   }
     * }</pre>
     *
     * <p>The default implementation falls back to {@link #writeEntry(String)} formatted
     * as {@code key=value} pairs, so test doubles and STANDARD writers do not need to
     * override it.
     *
     * @param kvPairs alternating key ({@code String}) / value ({@code String}, {@code long},
     *                {@code double}, {@code boolean}, or {@code null}) pairs
     */
    default void writeStructuredEntry(Object... kvPairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        writeEntry(sb.toString());
    }

    /**
     * Starts a new object within a named array at the current section level.
     * Multiple calls with the same {@code arrayKey} append further items.
     *
     * <p>In JSON format: creates or appends to {@code "arrayKey": [...]} with each call
     * opening a new item object. Items are closed by {@link #endArrayItemSection()}.
     *
     * <p>Default delegates to {@link #startSection(String)} so that STANDARD
     * writers and test stubs do not need to override it.
     *
     * @param arrayKey JSON key under which the array is written
     */
    default void startArrayItemSection(String arrayKey) {
        startSection(arrayKey);
    }

    /**
     * Ends the current array item object started by {@link #startArrayItemSection}.
     * Default delegates to {@link #endSection()}.
     */
    default void endArrayItemSection() {
        endSection();
    }

    void writeSectionKeyValue(String sectionName, long timeMillis, String key, long value);

    void writeSectionKeyValue(String sectionName, long timeMillis, String key, double value);

    void writeSectionKeyValue(String sectionName, long timeMillis, String key, String value);

    void startSection(String sectionName);

    /**
     * Starts a top-level section stamped with an explicit wall-clock time.
     * Used by {@code writeSectionKeyValue} overloads to associate a precise
     * timestamp with a single-value section without calling
     * {@link System#currentTimeMillis()} again.
     *
     * <p>Default delegates to {@link #startSection(String)}, ignoring the
     * timestamp, so implementations that do not track time per section do
     * not need to override it.
     */
    default void startSection(String sectionName, long timeMillis) {
        startSection(sectionName);
    }

    void endSection();

    void writeEntry(String s);

    void writeKeyValueEntry(String key, String value);

    void writeKeyValueEntry(String key, double value);

    void writeKeyValueEntry(String key, long value);

    void writeKeyValueEntry(String key, boolean value);

    void writeKeyValueEntryAsDateTime(String key, long epochMillis);
}
