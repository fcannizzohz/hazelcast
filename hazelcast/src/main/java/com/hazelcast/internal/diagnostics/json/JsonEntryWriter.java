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

package com.hazelcast.internal.diagnostics.json;

import javax.annotation.Nullable;
import java.io.PrintWriter;

/**
 * Zero-allocation, single-threaded JSON line writer for the diagnostics JSON subsystem.
 *
 * <p>Each {@link #startEntry}/{@link #endEntry} pair produces one complete NDJSON line:
 * <pre>{@code {"epoch":<ms>,"name":"<name>","content":{...}}\n}</pre>
 *
 * <p>A single {@link JsonDiagnosticsPlugin#run} call may invoke multiple
 * {@code startEntry}/{@code endEntry} pairs to emit more than one line
 * (e.g. {@code SystemLogPlugin} flushes one event per queued item;
 * {@code StoreLatencyPlugin} emits one line per instrumented service).
 *
 * <h3>Nesting model</h3>
 * <ul>
 *   <li>Depth {@code -1}: idle — no entry open.</li>
 *   <li>Depth {@code 0}: inside the top-level {@code "content"} object.</li>
 *   <li>Depth {@code > 0}: inside a nested object or array item.</li>
 * </ul>
 * The writer tracks whether each level is an array ({@code levelIsArray[d] = true})
 * or an object ({@code false}), and whether the next write at that level needs a
 * leading comma ({@code firstInLevel[d]}).
 *
 * <h3>Allocation profile</h3>
 * All hot-path writes are allocation-free:
 * <ul>
 *   <li>{@code long} values are formatted into a pre-allocated {@code char[20]}.</li>
 *   <li>{@code double} values use a reused {@link StringBuilder} + {@code char[32]}.</li>
 *   <li>String escaping writes character-by-character with no intermediate object.</li>
 * </ul>
 *
 * @since 6.0
 */
public final class JsonEntryWriter {

    // ---- allocation-free number formatting ----
    private static final char[] DIGITS = "0123456789".toCharArray();
    /** Maximum digits in a signed long (19 digits) plus sign character. */
    private static final int LONG_BUF_SIZE = 20;
    private static final int NUM_BUF_SIZE = 32;
    private static final int DECIMAL_BASE = 10;
    private static final int HEX_NIBBLE_SHIFT = 4;
    private static final int HEX_NIBBLE_MASK = 0xF;
    /** First printable ASCII character; control chars below this need escaping. */
    private static final int FIRST_PRINTABLE_ASCII = 0x20;

    // ---- nesting depth ----
    /** Maximum nesting depth supported by this writer. */
    private static final int MAX_DEPTH = 10;

    // ---- state ----
    private PrintWriter out;
    /** Current nesting depth; -1 means no entry is open. */
    private int depth = -1;

    /** true when the next write at this depth needs no leading comma */
    private final boolean[] firstInLevel = new boolean[MAX_DEPTH];
    /** true when this depth is an array context (items have no key) */
    private final boolean[] levelIsArray = new boolean[MAX_DEPTH];

    // ---- reusable buffers ----
    private final char[] longBuf = new char[LONG_BUF_SIZE];
    private final StringBuilder numSb = new StringBuilder(NUM_BUF_SIZE);
    private final char[] numChars = new char[NUM_BUF_SIZE];

    /**
     * Creates a writer that routes output to {@code out}.
     *
     * @param out destination; must not be null
     */
    public JsonEntryWriter(PrintWriter out) {
        this.out = out;
    }

    /**
     * Re-initialises the writer to use a different {@link PrintWriter}.
     * Resets all state; must only be called when no entry is open.
     *
     * @param out new destination; must not be null
     */
    public void init(PrintWriter out) {
        this.out = out;
        this.depth = -1;
    }

    // ------------------------------------------------------------------ entry lifecycle

    /**
     * Opens a new JSON line. Writes the envelope prefix:
     * <pre>{@code {"epoch":<epochMillis>,"name":"<name>","content":{}</pre>
     *
     * @param epochMillis Unix epoch in milliseconds (always emitted)
     * @param name        the {@code "name"} discriminator; must not be null
     * @throws IllegalStateException if an entry is already open
     */
    public void startEntry(long epochMillis, String name) {
        if (depth != -1) {
            throw new IllegalStateException("Cannot start a new entry while one is already open (depth=" + depth + ")");
        }
        out.print("{\"epoch\":");
        printLong(epochMillis);
        out.print(",\"name\":\"");
        printEscaped(name);
        out.print("\",\"content\":{");
        depth = 0;
        firstInLevel[0] = true;
        levelIsArray[0] = false;
    }

    /**
     * Closes the current entry and writes a trailing newline.
     * Closes all open nested levels first (defensive).
     */
    public void endEntry() {
        // close content object + envelope
        out.print("}}");
        out.println();
        depth = -1;
    }

    // ------------------------------------------------------------------ object key-value

    /**
     * Writes {@code "key":"value"} at the current object level.
     * {@code null} value is written as JSON {@code null} (unquoted).
     */
    public void writeString(String key, @Nullable String value) {
        writeKey(key);
        if (value == null) {
            out.print("null");
        } else {
            out.print('"');
            printEscaped(value);
            out.print('"');
        }
    }

    /** Writes {@code "key":<value>} at the current object level. */
    public void writeLong(String key, long value) {
        writeKey(key);
        printLong(value);
    }

    /** Writes {@code "key":<value>} at the current object level. */
    public void writeDouble(String key, double value) {
        writeKey(key);
        printDouble(value);
    }

    /** Writes {@code "key":true} or {@code "key":false} at the current object level. */
    public void writeBoolean(String key, boolean value) {
        writeKey(key);
        out.print(value);
    }

    /** Writes {@code "key":null} at the current object level. */
    public void writeNull(String key) {
        writeKey(key);
        out.print("null");
    }

    // ------------------------------------------------------------------ nested object

    /**
     * Opens a nested object: writes {@code ,"key":{}. Increments depth.
     *
     * @param key JSON key for this nested object
     */
    public void startObject(String key) {
        writeKey(key);
        out.print('{');
        pushLevel(false);
    }

    /**
     * Closes the current nested object with {@code "}"}. Decrements depth.
     */
    public void endObject() {
        out.print('}');
        popLevel();
    }

    // ------------------------------------------------------------------ named array

    /**
     * Opens a named array: writes {@code ,"key":[}. Increments depth.
     *
     * @param key JSON key for this array
     */
    public void startArray(String key) {
        writeKey(key);
        out.print('[');
        pushLevel(true);
    }

    /**
     * Closes the current array with {@code "]"}. Decrements depth.
     */
    public void endArray() {
        out.print(']');
        popLevel();
    }

    // ------------------------------------------------------------------ array items (objects)

    /**
     * Starts a new object item within the current array context.
     * Writes {@code {}} or {@code ,{}. Increments depth.
     * <p>The parent level must be an array (opened via {@link #startArray}).
     */
    public void startArrayItem() {
        if (depth < 0 || depth >= MAX_DEPTH - 1) {
            return;
        }
        if (!levelIsArray[depth]) {
            throw new IllegalStateException("startArrayItem called outside an array context");
        }
        if (!firstInLevel[depth]) {
            out.print(',');
        }
        out.print('{');
        firstInLevel[depth] = false;
        pushLevel(false);
    }

    /**
     * Closes the current array item object with {@code "}"}. Decrements depth.
     */
    public void endArrayItem() {
        out.print('}');
        popLevel();
    }

    // ------------------------------------------------------------------ flush

    /** Flushes the underlying {@link PrintWriter}. */
    public void flush() {
        out.flush();
    }

    // ------------------------------------------------------------------ package-private for testing

    /** Returns the current nesting depth. -1 means no entry is open. */
    int getDepth() {
        return depth;
    }

    // ------------------------------------------------------------------ helpers

    private void writeKey(String key) {
        if (depth < 0 || depth >= MAX_DEPTH) {
            return;
        }
        if (levelIsArray[depth]) {
            throw new IllegalStateException("Cannot write a keyed value inside an array context; use startArrayItem()");
        }
        if (!firstInLevel[depth]) {
            out.print(',');
        }
        out.print('"');
        printEscaped(key);
        out.print("\":");
        firstInLevel[depth] = false;
    }

    private void pushLevel(boolean isArray) {
        if (depth >= MAX_DEPTH - 1) {
            return;
        }
        depth++;
        firstInLevel[depth] = true;
        levelIsArray[depth] = isArray;
    }

    private void popLevel() {
        if (depth > 0) {
            depth--;
            firstInLevel[depth] = false;
        }
    }

    /**
     * Writes {@code s} JSON-escaped, character by character.
     * No intermediate {@code String} or buffer is allocated.
     */
    @SuppressWarnings("checkstyle:cyclomaticcomplexity")
    private void printEscaped(String s) {
        if (s == null) {
            out.print("null");
            return;
        }
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.print("\\\""); break;
                case '\\': out.print("\\\\"); break;
                case '\b': out.print("\\b");  break;
                case '\f': out.print("\\f");  break;
                case '\n': out.print("\\n");  break;
                case '\r': out.print("\\r");  break;
                case '\t': out.print("\\t");  break;
                default:
                    if (c < FIRST_PRINTABLE_ASCII) {
                        // control characters: emit unicode escape sequence
                        out.print("\\u00");
                        out.print(DIGITS[(c >> HEX_NIBBLE_SHIFT) & HEX_NIBBLE_MASK]);
                        out.print(DIGITS[c & HEX_NIBBLE_MASK]);
                    } else {
                        out.write(c);
                    }
                    break;
            }
        }
    }

    /**
     * Formats {@code value} into the pre-allocated {@code char[]} buffer
     * and writes it in a single call. No {@code String} is allocated.
     */
    private void printLong(long value) {
        if (value == Long.MIN_VALUE) {
            // Long.MIN_VALUE cannot be negated; delegate to JDK
            out.print(Long.MIN_VALUE);
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
        out.write(longBuf, pos, LONG_BUF_SIZE - pos);
    }

    /**
     * Formats {@code value} using a reused {@link StringBuilder} + {@code char[]}
     * to avoid the {@code String} allocation that {@link Double#toString} produces.
     */
    private void printDouble(double value) {
        numSb.append(value);
        int len = numSb.length();
        numSb.getChars(0, len, numChars, 0);
        out.write(numChars, 0, len);
        numSb.setLength(0);
    }
}
