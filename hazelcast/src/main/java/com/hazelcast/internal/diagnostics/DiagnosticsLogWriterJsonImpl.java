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
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * A JSON implementation of the {@link DiagnosticsLogWriter}.
 * It generates a single line JSON for each plugin execution.
 *
 * <p>Multiple {@link #writeEntry(String)} calls within the same section are
 * collected under a single {@code "entries"} JSON array, so the output is always
 * valid JSON even when a plugin writes several plain entries in one section.
 * Each plain entry is wrapped as {@code {"text":"<value>"}} so every item in the
 * array is a JSON object.  Any subsequent {@link #writeKeyValueEntry} or nested
 * {@link #startSection} call automatically closes the open array first.
 *
 * <p>The envelope contains {@code "epoch"} (Unix epoch in milliseconds) and
 * {@code "name"} only; a human-readable {@code "time"} field is intentionally
 * omitted because it is redundant with {@code "epoch"} and non-ISO.
 * The {@code includeEpochTime} constructor argument is accepted for API
 * compatibility but ignored in JSON mode.
 *
 * <p><b>Allocation profile:</b> all hot-path writes use pre-allocated buffers
 * and write directly to the underlying {@link PrintWriter}.  Specifically:
 * string escaping writes character-by-character (no intermediate {@code String}
 * allocation even when escaping is needed), {@code long} values are formatted
 * into a pre-allocated {@code char[]} buffer, {@code double} values reuse a
 * pre-allocated {@code StringBuilder} + {@code char[]} pair, and the timestamp
 * is built into a pre-allocated {@code char[19]} via a reused {@code Calendar}.
 */
public class DiagnosticsLogWriterJsonImpl implements DiagnosticsLogWriter {

    // Lookup table for single-digit-to-char conversion; avoids repeated arithmetic.
    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
    private static final int DECIMAL_BASE = 10;

    // "dd-MM-yyyy HH:mm:ss" = 19 characters
    private static final int DATE_BUF_SIZE = 19;

    // max digits in a long (19) + optional minus sign = 20
    private static final int LONG_BUF_SIZE = 20;

    // generous buffer for double-to-string conversion
    private static final int NUM_BUF_SIZE = 32;

    /**
     * Maximum nesting depth supported. Matches the number of indent levels in
     * {@link DiagnosticsLogWriterImpl}.
     */
    private static final int MAX_SECTION_LEVELS = 8;

    private final ILogger logger;

    // --- zero-allocation timestamp support ---
    // Reused per-instance; safe because the writer is driven by a single scheduler thread.
    private final Calendar calendar = new GregorianCalendar(TimeZone.getDefault());
    private final Date calDate = new Date();
    private final char[] timeBuf = new char[DATE_BUF_SIZE];

    // --- zero-allocation long formatting ---
    private final char[] longBuf = new char[LONG_BUF_SIZE];

    // --- low-allocation double formatting (reused StringBuilder + char[] flush) ---
    private final StringBuilder numSb = new StringBuilder(NUM_BUF_SIZE);
    private final char[] numChars = new char[NUM_BUF_SIZE];

    private PrintWriter printWriter;
    private int sectionLevel = -1;
    private boolean firstInSection;

    /**
     * Tracks, per section level, whether an {@code "entries":[...]} array has been
     * opened but not yet closed.
     */
    private final boolean[] entryArrayOpen = new boolean[MAX_SECTION_LEVELS];

    /**
     * Tracks, per section level, whether a named array (from
     * {@link #startArrayItemSection}) is open and which key it belongs to.
     */
    private final boolean[] namedArrayOpen = new boolean[MAX_SECTION_LEVELS];
    private final String[] namedArrayKey = new String[MAX_SECTION_LEVELS];

    public DiagnosticsLogWriterJsonImpl(boolean includeEpochTime, ILogger logger) {
        // includeEpochTime is accepted for API compatibility; epoch is always emitted in JSON.
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
    @SuppressWarnings("checkstyle:magicnumber")
    public void startSection(String name, long timeMillis) {
        if (sectionLevel == -1) {
            // epoch is always present in JSON format; time is omitted (redundant given epoch)
            printWriter.print("{\"epoch\":");
            printLong(timeMillis);
            printWriter.print(",\"name\":\"");
            printWriter.print(name);
            printWriter.print("\",\"content\":{");
        } else {
            closeEntryArrayIfOpen();
            closeNamedArrayIfOpen();
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
            namedArrayOpen[sectionLevel] = false;
            namedArrayKey[sectionLevel] = null;
        }
    }

    @Override
    public void endSection() {
        closeEntryArrayIfOpen();
        closeNamedArrayIfOpen();
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

    /**
     * Writes a plain-text entry into the {@code "entries"} array.
     * The string is wrapped as {@code {"text":"<escaped value>"}} so every
     * item in the array is a JSON object.
     */
    @Override
    public void writeEntry(String s) {
        if (sectionLevel < 0 || sectionLevel >= MAX_SECTION_LEVELS) {
            return;
        }
        openOrContinueEntryArray();
        printWriter.print("{\"text\":\"");
        printEscaped(s);
        printWriter.print("\"}");
    }

    @Override
    public void writeKeyValueEntry(String key, String value) {
        writeKey(key);
        if (value == null) {
            printWriter.print("null");
        } else {
            printWriter.print("\"");
            printEscaped(value);
            printWriter.print("\"");
        }
    }

    @Override
    public void writeKeyValueEntry(String key, double value) {
        writeKey(key);
        printDouble(value);
    }

    @Override
    public void writeKeyValueEntry(String key, long value) {
        writeKey(key);
        printLong(value);
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
        printDateTime(epochMillis);
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
     * section.
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
            printEscaped(String.valueOf(kvPairs[i]));
            printWriter.print("\":");
            printJsonValue(kvPairs[i + 1]);
        }
        printWriter.print("}");
    }

    /**
     * Starts a new object within a named array at the current section level.
     * Multiple calls with the same {@code arrayKey} append further items.
     * Calling with a different key closes the previous array first.
     */
    @Override
    public void startArrayItemSection(String arrayKey) {
        if (sectionLevel < 0 || sectionLevel >= MAX_SECTION_LEVELS - 1) {
            return;
        }
        closeEntryArrayIfOpen();
        if (!namedArrayOpen[sectionLevel] || !arrayKey.equals(namedArrayKey[sectionLevel])) {
            // Close any open named array for a different key, then open this one
            closeNamedArrayIfOpen();
            if (!firstInSection) {
                printWriter.print(",");
            }
            printWriter.print("\"");
            printEscaped(arrayKey);
            printWriter.print("\":[");
            namedArrayOpen[sectionLevel] = true;
            namedArrayKey[sectionLevel] = arrayKey;
            firstInSection = false;
        } else {
            // Same key — append another item to the open array
            printWriter.print(",");
        }
        printWriter.print("{");
        sectionLevel++;
        firstInSection = true;
        entryArrayOpen[sectionLevel] = false;
        namedArrayOpen[sectionLevel] = false;
        namedArrayKey[sectionLevel] = null;
    }

    /**
     * Ends the current array item object started by {@link #startArrayItemSection}.
     */
    @Override
    public void endArrayItemSection() {
        if (sectionLevel <= 0) {
            return;
        }
        closeEntryArrayIfOpen();
        closeNamedArrayIfOpen();
        printWriter.print("}");
        sectionLevel--;
        firstInSection = false;
    }

    @Override
    public void resetSectionLevel() {
        sectionLevel = -1;
    }

    // ------------------------------------------------------------------ helpers

    private void writeKey(String key) {
        closeEntryArrayIfOpen();
        closeNamedArrayIfOpen();
        if (!firstInSection) {
            printWriter.print(",");
        }
        printWriter.print("\"");
        printEscaped(key);
        printWriter.print("\":");
        firstInSection = false;
    }

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

    private void printJsonValue(Object v) {
        if (v instanceof Long || v instanceof Integer) {
            printLong(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            printDouble(((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            printWriter.print(v);
        } else if (v == null) {
            printWriter.print("null");
        } else {
            printWriter.print("\"");
            printEscaped(String.valueOf(v));
            printWriter.print("\"");
        }
    }

    private void closeEntryArrayIfOpen() {
        if (sectionLevel >= 0 && sectionLevel < MAX_SECTION_LEVELS && entryArrayOpen[sectionLevel]) {
            printWriter.print("]");
            entryArrayOpen[sectionLevel] = false;
        }
    }

    private void closeNamedArrayIfOpen() {
        if (sectionLevel >= 0 && sectionLevel < MAX_SECTION_LEVELS && namedArrayOpen[sectionLevel]) {
            printWriter.print("]");
            namedArrayOpen[sectionLevel] = false;
            namedArrayKey[sectionLevel] = null;
        }
    }

    /**
     * Writes the JSON-escaped content of {@code s} directly to the underlying
     * {@link PrintWriter}, one character at a time.  No intermediate {@code String}
     * or buffer is allocated — the only allocation that can occur is inside
     * {@link PrintWriter} itself (which uses its own internal lock and buffer).
     * A {@code null} input writes the four characters {@code null} (unquoted).
     */
    private void printEscaped(String s) {
        if (s == null) {
            printWriter.print("null");
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  printWriter.print("\\\""); break;
                case '\\': printWriter.print("\\\\"); break;
                case '\b': printWriter.print("\\b");  break;
                case '\f': printWriter.print("\\f");  break;
                case '\n': printWriter.print("\\n");  break;
                case '\r': printWriter.print("\\r");  break;
                case '\t': printWriter.print("\\t");  break;
                default:   printWriter.write(c);      break;
            }
        }
    }

    /**
     * Formats {@code value} as a decimal integer directly into a pre-allocated
     * {@code char[]} buffer and writes it to the {@link PrintWriter} in a single
     * {@code write(char[], int, int)} call.  No {@code String} object is created.
     * Handles all {@code long} values including {@link Long#MIN_VALUE}.
     */
    private void printLong(long value) {
        if (value == Long.MIN_VALUE) {
            // Long.MIN_VALUE cannot be negated; use the pre-computed string constant.
            printWriter.print(Long.MIN_VALUE);
            return;
        }
        boolean negative = value < 0;
        if (negative) {
            value = -value;
        }
        int pos = LONG_BUF_SIZE;
        do {
            longBuf[--pos] = DIGITS[(int) (value % DECIMAL_BASE)];
            value /= DECIMAL_BASE;
        } while (value > 0);
        if (negative) {
            longBuf[--pos] = '-';
        }
        printWriter.write(longBuf, pos, LONG_BUF_SIZE - pos);
    }

    /**
     * Formats {@code value} as a decimal floating-point number using a reused
     * {@link StringBuilder} / {@code char[]} pair to avoid the {@code String}
     * allocation that {@link Double#toString} would otherwise produce.
     */
    private void printDouble(double value) {
        numSb.append(value);
        int len = numSb.length();
        numSb.getChars(0, len, numChars, 0);
        printWriter.write(numChars, 0, len);
        numSb.setLength(0);
    }

    /**
     * Writes the timestamp as {@code "dd-MM-yyyy HH:mm:ss"} into a pre-allocated
     * {@code char[19]} buffer using a reused {@link Calendar} and {@link Date}, then
     * flushes the buffer to the {@link PrintWriter} in one call.
     * No {@code String}, {@code Instant}, or formatter object is allocated.
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private void printDateTime(long timeMillis) {
        calDate.setTime(timeMillis);
        calendar.setTime(calDate);

        int day   = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH) + 1;
        int year  = calendar.get(Calendar.YEAR);
        int hour  = calendar.get(Calendar.HOUR_OF_DAY);
        int min   = calendar.get(Calendar.MINUTE);
        int sec   = calendar.get(Calendar.SECOND);

        timeBuf[0]  = DIGITS[day   / 10];
        timeBuf[1]  = DIGITS[day   % 10];
        timeBuf[2]  = '-';
        timeBuf[3]  = DIGITS[month / 10];
        timeBuf[4]  = DIGITS[month % 10];
        timeBuf[5]  = '-';
        timeBuf[6]  = DIGITS[year  / 1000];
        timeBuf[7]  = DIGITS[(year /  100) % 10];
        timeBuf[8]  = DIGITS[(year /   10) % 10];
        timeBuf[9]  = DIGITS[year          % 10];
        timeBuf[10] = ' ';
        timeBuf[11] = DIGITS[hour / 10];
        timeBuf[12] = DIGITS[hour % 10];
        timeBuf[13] = ':';
        timeBuf[14] = DIGITS[min  / 10];
        timeBuf[15] = DIGITS[min  % 10];
        timeBuf[16] = ':';
        timeBuf[17] = DIGITS[sec  / 10];
        timeBuf[18] = DIGITS[sec  % 10];

        printWriter.write(timeBuf, 0, DATE_BUF_SIZE);
    }
}
