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

import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.CharArrayWriter;
import java.io.PrintWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class DiagnosticsLogWriterJsonImplTest extends HazelcastTestSupport {

    private DiagnosticsLogWriterJsonImpl writer;
    private CharArrayWriter out = new CharArrayWriter();

    @Before
    public void setupLogWriter() {
        writer = new DiagnosticsLogWriterJsonImpl(false, null);
        writer.init(new PrintWriter(out));
    }

    @Test
    public void writeKeyValueEntry_nullValue() {
        writer.startSection("SomeSection");
        writer.writeKeyValueEntry("s", (String) null);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"name\":\"SomeSection\""));
        assertTrue(actual.contains("\"s\":\"null\""));
        assertTrue(actual.endsWith("}" + System.lineSeparator()));
    }

    @Test
    public void test() {
        writer.startSection("SomeSection");
        writer.writeKeyValueEntry("boolean", true);
        writer.writeKeyValueEntry("long", 10L);
        writer.startSection("SubSection");
        writer.writeKeyValueEntry("integer", 10);
        writer.endSection();
        writer.writeKeyValueEntry("string", "foo");
        writer.writeKeyValueEntry("double", 11.5d);
        // single entry — must produce a one-element JSON array, not a plain string
        writer.writeEntry("foobar");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"name\":\"SomeSection\""));
        assertTrue(actual.contains("\"boolean\":true"));
        assertTrue(actual.contains("\"long\":10"));
        assertTrue(actual.contains("\"SubSection\":{\"integer\":10}"));
        assertTrue(actual.contains("\"string\":\"foo\""));
        assertTrue(actual.contains("\"double\":11.5"));
        assertTrue(actual.contains("\"entries\":[\"foobar\"]"));
        // must not produce the old bare-string form
        assertFalse(actual.contains("\"entries\":\"foobar\""));
    }

    @Test
    public void testEscaping() {
        writer.startSection("Section");
        writer.writeKeyValueEntry("key", "value with \"quotes\" and \\backslashes\nand newlines");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"value with \\\"quotes\\\" and \\\\backslashes\\nand newlines\""));
    }

    @Test
    public void testEpochTime() {
        writer = new DiagnosticsLogWriterJsonImpl(true, null);
        writer.init(new PrintWriter(out));
        writer.startSection("Section", 123456789L);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"epoch\":123456789"));
    }

    // --- writeStructuredEntry tests ---

    @Test
    public void writeStructuredEntry_producesJsonObject() {
        writer.startSection("Section");
        writer.writeStructuredEntry("operation", "PutOperation", "samples", 4L);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"operation\":\"PutOperation\",\"samples\":4}]"));
    }

    @Test
    public void writeStructuredEntry_multipleCallsMergedIntoOneArray() {
        writer.startSection("History");
        writer.writeStructuredEntry("operation", "PutOperation", "samples", 3L);
        writer.writeStructuredEntry("operation", "GetOperation", "samples", 1L);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains(
                "\"entries\":[{\"operation\":\"PutOperation\",\"samples\":3},{\"operation\":\"GetOperation\",\"samples\":1}]"));
        // only one "entries" key
        int first = actual.indexOf("\"entries\"");
        assertTrue(actual.indexOf("\"entries\"", first + 1) == -1);
    }

    @Test
    public void writeStructuredEntry_mixedWithPlainWriteEntry_sharedArray() {
        writer.startSection("CloseCause");
        writer.writeStructuredEntry("exceptionClass", "java.lang.NullPointerException", "message", "oops");
        writer.writeEntry("at com.hazelcast.Foo.bar(Foo.java:10)");
        writer.endSection();

        String actual = out.toString();
        // object entry followed by string entry in the same array
        assertTrue(actual.contains(
                "\"entries\":[{\"exceptionClass\":\"java.lang.NullPointerException\",\"message\":\"oops\"},"
                + "\"at com.hazelcast.Foo.bar(Foo.java:10)\"]"));
    }

    @Test
    public void writeStructuredEntry_followedByKeyValue_closesArrayFirst() {
        writer.startSection("Section");
        writer.writeStructuredEntry("k", "v");
        writer.writeKeyValueEntry("after", "val");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"k\":\"v\"}],\"after\":\"val\""));
    }

    @Test
    public void writeStructuredEntry_numberAndBooleanTypes() {
        writer.startSection("Section");
        writer.writeStructuredEntry("duration", 1500L, "percentage", 30.5d, "slow", true);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"duration\":1500"));
        assertTrue(actual.contains("\"percentage\":30.5"));
        assertTrue(actual.contains("\"slow\":true"));
    }

    @Test
    public void writeStructuredEntry_nullValue_renderedAsNull() {
        writer.startSection("Section");
        writer.writeStructuredEntry("message", null);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"message\":null"));
    }

    @Test
    public void getFormat_returnsJson() {
        assertEquals(DiagnosticsLogFormat.JSON, writer.getFormat());
    }

    // --- writeEntry bug-fix tests ---

    /**
     * Multiple writeEntry calls in the same section must produce a single
     * "entries" JSON array, not duplicate keys.
     */
    @Test
    public void writeEntry_multipleCallsProduceSingleArray() {
        writer.startSection("Section");
        writer.writeEntry("first");
        writer.writeEntry("second");
        writer.writeEntry("third");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[\"first\",\"second\",\"third\"]"));
        // no duplicate keys
        int firstIdx = actual.indexOf("\"entries\"");
        int secondIdx = actual.indexOf("\"entries\"", firstIdx + 1);
        assertTrue("duplicate 'entries' key found", secondIdx == -1);
    }

    /**
     * A writeKeyValueEntry after writeEntry must close the array first,
     * producing valid JSON (models the ConnectionAdded section pattern).
     */
    @Test
    public void writeEntry_followedByKeyValue_closesArrayFirst() {
        writer.startSection("ConnectionAdded");
        writer.writeEntry("connection-info");
        writer.writeKeyValueEntry("type", "MEMBER");
        writer.writeKeyValueEntry("isAlive", true);
        writer.endSection();

        String actual = out.toString();
        // entry array must be closed before the next key
        assertTrue(actual.contains("\"entries\":[\"connection-info\"],\"type\":\"MEMBER\",\"isAlive\":true"));
    }

    /**
     * A nested startSection after writeEntry must close the array first.
     */
    @Test
    public void writeEntry_followedByNestedSection_closesArrayFirst() {
        writer.startSection("Outer");
        writer.writeEntry("outer-entry");
        writer.startSection("Inner");
        writer.writeKeyValueEntry("k", "v");
        writer.endSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[\"outer-entry\"],\"Inner\":{\"k\":\"v\"}"));
    }

    /**
     * writeEntry with escaping characters must still produce a valid array.
     */
    @Test
    public void writeEntry_escapingInsideArray() {
        writer.startSection("Section");
        writer.writeEntry("line with \"quotes\" and\nnewline");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[\"line with \\\"quotes\\\" and\\nnewline\"]"));
    }

    // --- writeKeyValueEntryAsDateTime ---

    @Test
    public void writeKeyValueEntryAsDateTime_producesFormattedQuotedString() {
        writer.startSection("Section");
        writer.writeKeyValueEntryAsDateTime("startedAt", 1710812712000L);
        writer.endSection();

        String actual = out.toString();
        // value must be a quoted string, not a raw number
        assertFalse("datetime value must not be a bare number", actual.contains("\"startedAt\":1710812712000"));
        assertTrue("datetime value must be a quoted string", actual.contains("\"startedAt\":\""));
    }

    // --- multiple root-level sections ---

    @Test
    public void multipleSectionsAtRootLevel_eachProducesOwnLine() {
        writer.startSection("S1");
        writer.writeKeyValueEntry("a", "1");
        writer.endSection();

        writer.startSection("S2");
        writer.writeKeyValueEntry("b", "2");
        writer.endSection();

        String actual = out.toString();
        String[] lines = actual.split(System.lineSeparator());
        assertEquals("each root section must produce exactly one JSON line", 2, lines.length);
        assertTrue(lines[0].contains("\"name\":\"S1\""));
        assertTrue(lines[1].contains("\"name\":\"S2\""));
    }

    // --- init() resets state ---

    @Test
    public void init_resetsStateBetweenUses() {
        writer.startSection("S1");
        writer.writeKeyValueEntry("a", "b");
        writer.endSection();

        CharArrayWriter out2 = new CharArrayWriter();
        writer.init(new PrintWriter(out2));
        writer.startSection("S2");
        writer.writeKeyValueEntry("c", "d");
        writer.endSection();

        assertFalse("second output must not contain S1", out2.toString().contains("\"name\":\"S1\""));
        assertTrue("second output must contain S2", out2.toString().contains("\"name\":\"S2\""));
    }

    // --- section level boundary protection ---

    @Test
    public void startSection_beyondMaxDepth_doesNotThrowArrayIndexException() {
        // Nest beyond the limit of MAX_SECTION_LEVELS (8)
        for (int i = 0; i < 9; i++) {
            writer.startSection("Level" + i);
        }
        writer.writeKeyValueEntry("k", "v");
        for (int i = 0; i < 9; i++) {
            writer.endSection();
        }
        // A root-level JSON line must still have been produced
        assertTrue("output must contain a JSON object line", out.toString().contains("{"));
    }

    @Test
    public void endSection_withoutMatchingStart_doesNotThrowUnderflowException() {
        // Should not throw — just trigger the underflow warning path
        writer.endSection();
        // No exception means the guard worked
    }
}
