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

import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A JSON implementation of the {@link DiagnosticsLogWriter}.
 * It generates a single line JSON for each plugin execution.
 *
 * <p>Multiple {@link #writeEntry(String)} calls within the same section are
 * collected under a single {@code "entries"} JSON array, so the output is always
 * valid JSON even when a plugin writes several plain entries in one section.
 * Any subsequent {@link #writeKeyValueEntry} or nested {@link #startSection}
 * call automatically closes the open array first.
 */
public class DiagnosticsLogWriterJsonImpl implements DiagnosticsLogWriter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * Maximum nesting depth supported. Matches the number of indent levels in
     * {@link DiagnosticsLogWriterImpl}.
     */
    private static final int MAX_SECTION_LEVELS = 8;

    private final ILogger logger;
    private final boolean includeEpochTime;

    private PrintWriter printWriter;
    private int sectionLevel = -1;
    private boolean firstInSection;

    /**
     * Tracks, per section level, whether an {@code "entries":[...]} array has been
     * opened but not yet closed. Used to collect multiple {@link #writeEntry}
     * and {@link #writeStructuredEntry} calls into a single JSON array and to
     * close that array before any key-value entry or nested section is written.
     */
    private final boolean[] entryArrayOpen = new boolean[MAX_SECTION_LEVELS];

    public DiagnosticsLogWriterJsonImpl(boolean includeEpochTime, ILogger logger) {
        this.includeEpochTime = includeEpochTime;
        this.logger = logger != null ? logger : Logger.getLogger(getClass());
    }

    @Override
    public void init(PrintWriter printWriter) {
        this.printWriter = printWriter;
        this.sectionLevel = -1;
    }

    @Override
    public void writeSectionKeyValue(String sectionName, long timeMillis, String key, long value) {
        startSection(sectionName, timeMillis);
        writeKeyValueEntry(key, value);
        endSection();
    }

    @Override
    public void writeSectionKeyValue(String sectionName, long timeMillis, String key, double value) {
        startSection(sectionName, timeMillis);
        writeKeyValueEntry(key, value);
        endSection();
    }

    @Override
    public void writeSectionKeyValue(String sectionName, long timeMillis, String key, String value) {
        startSection(sectionName, timeMillis);
        writeKeyValueEntry(key, value);
        endSection();
    }

    @Override
    public void startSection(String sectionName) {
        startSection(sectionName, System.currentTimeMillis());
    }

    @Override
    public void startSection(String name, long timeMillis) {
        if (sectionLevel == -1) {
            printWriter.print("{\"time\":\"");
            printWriter.print(DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(timeMillis)));
            printWriter.print("\"");
            if (includeEpochTime) {
                printWriter.print(",\"epoch\":");
                printWriter.print(timeMillis);
            }
            printWriter.print(",\"name\":\"");
            printWriter.print(name);
            printWriter.print("\",\"content\":{");
        } else {
            closeEntryArrayIfOpen();
            if (!firstInSection) {
                printWriter.print(",");
            }
            printWriter.print("\"");
            printWriter.print(name);
            printWriter.print("\":{");
        }
        if (sectionLevel < MAX_SECTION_LEVELS - 1) {
            sectionLevel++;
        } else {
            logger.warning("Diagnostics writer SectionLevel has overflown.", new Exception("Dumping stack trace"));
            sectionLevel = MAX_SECTION_LEVELS - 1;
        }
        firstInSection = true;
        if (sectionLevel >= 0 && sectionLevel < MAX_SECTION_LEVELS) {
            entryArrayOpen[sectionLevel] = false;
        }
    }

    @Override
    public void endSection() {
        closeEntryArrayIfOpen();
        printWriter.print("}");
        if (sectionLevel > -1) {
            sectionLevel--;
        } else {
            logger.warning("Diagnostics writer SectionLevel has underflown.", new Exception("Dumping stack trace"));
            sectionLevel = -1;
        }
        if (sectionLevel == -1) {
            printWriter.println("}");
        } else {
            firstInSection = false;
        }
    }

    @Override
    public void writeEntry(String s) {
        if (sectionLevel < 0 || sectionLevel >= MAX_SECTION_LEVELS) {
            return;
        }
        openOrContinueEntryArray();
        printWriter.print("\"");
        printWriter.print(escapeJson(s));
        printWriter.print("\"");
    }

    @Override
    public void writeKeyValueEntry(String key, String value) {
        writeKey(key);
        printWriter.print("\"");
        printWriter.print(escapeJson(value));
        printWriter.print("\"");
    }

    @Override
    public void writeKeyValueEntry(String key, double value) {
        writeKey(key);
        printWriter.print(value);
    }

    @Override
    public void writeKeyValueEntry(String key, long value) {
        writeKey(key);
        printWriter.print(value);
    }

    @Override
    public void writeKeyValueEntry(String key, boolean value) {
        writeKey(key);
        printWriter.print(value);
    }

    @Override
    public void writeKeyValueEntryAsDateTime(String key, long epochMillis) {
        writeKey(key);
        printWriter.print("\"");
        printWriter.print(DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis)));
        printWriter.print("\"");
    }

    @Override
    public DiagnosticsLogFormat getFormat() {
        return DiagnosticsLogFormat.JSON;
    }

    /**
     * Emits a JSON object into the {@code "entries"} array using the supplied
     * key/value pairs. The array is shared with {@link #writeEntry(String)}, so
     * plain-string entries and structured-object entries can coexist in the same
     * section (e.g. a list of stack-trace lines mixed with a sentinel message).
     */
    @Override
    public void writeStructuredEntry(Object... kvPairs) {
        if (sectionLevel < 0 || sectionLevel >= MAX_SECTION_LEVELS) {
            return;
        }
        openOrContinueEntryArray();
        printWriter.print("{");
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (i > 0) {
                printWriter.print(",");
            }
            printWriter.print("\"");
            printWriter.print(escapeJson(String.valueOf(kvPairs[i])));
            printWriter.print("\":");
            printJsonValue(kvPairs[i + 1]);
        }
        printWriter.print("}");
    }

    @Override
    public void resetSectionLevel() {
        sectionLevel = -1;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Writes the JSON key preamble for a key-value entry: closes any open entries
     * array, emits a comma separator if needed, then emits {@code "key":}.
     * The caller is responsible for printing the value and setting nothing else —
     * {@code firstInSection} is set to {@code false} here.
     */
    private void writeKey(String key) {
        closeEntryArrayIfOpen();
        if (!firstInSection) {
            printWriter.print(",");
        }
        printWriter.print("\"");
        printWriter.print(escapeJson(key));
        printWriter.print("\":");
        firstInSection = false;
    }

    /**
     * Opens the {@code "entries"} array at the current section level if it is not
     * already open, or appends a comma separator if it is. Must only be called
     * when {@code sectionLevel} is in bounds.
     */
    private void openOrContinueEntryArray() {
        if (!entryArrayOpen[sectionLevel]) {
            if (!firstInSection) {
                printWriter.print(",");
            }
            printWriter.print("\"entries\":[");
            entryArrayOpen[sectionLevel] = true;
            firstInSection = false;
        } else {
            printWriter.print(",");
        }
    }

    /**
     * Prints a JSON value for a typed Java object. Numeric subtypes are written
     * as JSON numbers; booleans as JSON booleans; null as {@code null}; everything
     * else as a quoted, escaped JSON string.
     */
    private void printJsonValue(Object v) {
        if (v instanceof Long || v instanceof Integer) {
            printWriter.print(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            printWriter.print(((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            printWriter.print(v);
        } else if (v == null) {
            printWriter.print("null");
        } else {
            printWriter.print("\"");
            printWriter.print(escapeJson(String.valueOf(v)));
            printWriter.print("\"");
        }
    }

    /**
     * If an {@code "entries"} array is currently open at the current section level,
     * close it. Called before writing any key-value pair or nested section to
     * ensure the JSON remains valid.
     */
    private void closeEntryArrayIfOpen() {
        if (sectionLevel >= 0 && sectionLevel < MAX_SECTION_LEVELS && entryArrayOpen[sectionLevel]) {
            printWriter.print("]");
            entryArrayOpen[sectionLevel] = false;
        }
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "null";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
