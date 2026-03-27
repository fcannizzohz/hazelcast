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
        // single entry — must produce a one-element JSON array of objects
        writer.writeEntry("foobar");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"name\":\"SomeSection\""));
        assertTrue(actual.contains("\"boolean\":true"));
        assertTrue(actual.contains("\"long\":10"));
        assertTrue(actual.contains("\"SubSection\":{\"integer\":10}"));
        assertTrue(actual.contains("\"string\":\"foo\""));
        assertTrue(actual.contains("\"double\":11.5"));
        // entries items are always objects — plain strings wrapped as {"text":"..."}
        assertTrue(actual.contains("\"entries\":[{\"text\":\"foobar\"}]"));
        assertFalse(actual.contains("\"entries\":\"foobar\""));
        assertFalse(actual.contains("\"entries\":[\"foobar\"]"));
    }

    @Test
    public void testEscaping() {
        writer.startSection("Section");
        writer.writeKeyValueEntry("key", "value with \"quotes\" and \\backslashes\nand newlines");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"value with \\\"quotes\\\" and \\\\backslashes\\nand newlines\""));
    }

    // --- Change 1: epoch always present in JSON ---

    @Test
    public void epochAlwaysPresent_whenIncludeEpochTimeFalse() {
        // Even with includeEpochTime=false the JSON writer must always emit epoch
        writer = new DiagnosticsLogWriterJsonImpl(false, null);
        writer.init(new PrintWriter(out));
        writer.startSection("Section", 123456789L);
        writer.endSection();

        String actual = out.toString();
        assertTrue("epoch must be present even when includeEpochTime=false",
                actual.contains("\"epoch\":123456789"));
    }

    @Test
    public void epochAlwaysPresent_whenIncludeEpochTimeTrue() {
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
        // structured object followed by plain-text object in the same array
        assertTrue(actual.contains(
                "\"entries\":[{\"exceptionClass\":\"java.lang.NullPointerException\",\"message\":\"oops\"},"
                + "{\"text\":\"at com.hazelcast.Foo.bar(Foo.java:10)\"}]"));
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

    // --- writeEntry tests (Change 2: items always objects) ---

    @Test
    public void writeEntry_producesTextWrappedObject() {
        writer.startSection("Section");
        writer.writeEntry("foobar");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"text\":\"foobar\"}]"));
        assertFalse(actual.contains("\"entries\":[\"foobar\"]"));
    }

    @Test
    public void writeEntry_multipleCallsProduceSingleArrayOfObjects() {
        writer.startSection("Section");
        writer.writeEntry("first");
        writer.writeEntry("second");
        writer.writeEntry("third");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains(
                "\"entries\":[{\"text\":\"first\"},{\"text\":\"second\"},{\"text\":\"third\"}]"));
        // no duplicate keys
        int firstIdx = actual.indexOf("\"entries\"");
        assertTrue("duplicate 'entries' key found", actual.indexOf("\"entries\"", firstIdx + 1) == -1);
    }

    @Test
    public void writeEntry_followedByKeyValue_closesArrayFirst() {
        writer.startSection("ConnectionAdded");
        writer.writeEntry("connection-info");
        writer.writeKeyValueEntry("type", "MEMBER");
        writer.writeKeyValueEntry("isAlive", true);
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"text\":\"connection-info\"}],\"type\":\"MEMBER\",\"isAlive\":true"));
    }

    @Test
    public void writeEntry_followedByNestedSection_closesArrayFirst() {
        writer.startSection("Outer");
        writer.writeEntry("outer-entry");
        writer.startSection("Inner");
        writer.writeKeyValueEntry("k", "v");
        writer.endSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"text\":\"outer-entry\"}],\"Inner\":{\"k\":\"v\"}"));
    }

    @Test
    public void writeEntry_escapingInsideArray() {
        writer.startSection("Section");
        writer.writeEntry("line with \"quotes\" and\nnewline");
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"entries\":[{\"text\":\"line with \\\"quotes\\\" and\\nnewline\"}]"));
    }

    // --- writeKeyValueEntryAsDateTime ---

    @Test
    public void writeKeyValueEntryAsDateTime_producesFormattedQuotedString() {
        writer.startSection("Section");
        writer.writeKeyValueEntryAsDateTime("startedAt", 1710812712000L);
        writer.endSection();

        String actual = out.toString();
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
        for (int i = 0; i < 9; i++) {
            writer.startSection("Level" + i);
        }
        writer.writeKeyValueEntry("k", "v");
        for (int i = 0; i < 9; i++) {
            writer.endSection();
        }
        assertTrue("output must contain a JSON object line", out.toString().contains("{"));
    }

    @Test
    public void endSection_withoutMatchingStart_doesNotThrowUnderflowException() {
        writer.endSection();
    }

    // --- startArrayItemSection / endArrayItemSection (Change 5) ---

    @Test
    public void startArrayItemSection_singleItem_producesNamedArray() {
        writer.startSection("OverloadedConnections");
        writer.startArrayItemSection("connection");
        writer.writeKeyValueEntry("from", "127.0.0.1:5701");
        writer.writeKeyValueEntry("to", "127.0.0.1:5702");
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"connection\":[{\"from\":\"127.0.0.1:5701\",\"to\":\"127.0.0.1:5702\"}]"));
    }

    @Test
    public void startArrayItemSection_multipleItems_allInSameArray() {
        writer.startSection("OverloadedConnections");
        writer.startArrayItemSection("connection");
        writer.writeKeyValueEntry("from", "addr1");
        writer.writeKeyValueEntry("packetCount", 100L);
        writer.endArrayItemSection();
        writer.startArrayItemSection("connection");
        writer.writeKeyValueEntry("from", "addr2");
        writer.writeKeyValueEntry("urgentPacketCount", 50L);
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"connection\":["));
        assertTrue(actual.contains("{\"from\":\"addr1\",\"packetCount\":100}"));
        assertTrue(actual.contains("{\"from\":\"addr2\",\"urgentPacketCount\":50}"));
        // only one "connection" key
        int first = actual.indexOf("\"connection\"");
        assertTrue(actual.indexOf("\"connection\"", first + 1) == -1);
    }

    @Test
    public void startArrayItemSection_withNestedSection_fullyNested() {
        writer.startSection("Root");
        writer.startArrayItemSection("connection");
        writer.writeKeyValueEntry("from", "addr1");
        writer.startSection("samples");
        writer.writeStructuredEntry("type", "PutOp", "count", 5L);
        writer.endSection();
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"connection\":[{\"from\":\"addr1\",\"samples\":{\"entries\":[{\"type\":\"PutOp\",\"count\":5}]}}]"));
    }

    @Test
    public void startArrayItemSection_arrayClosedWhenParentSectionEnds() {
        writer.startSection("Root");
        writer.startArrayItemSection("items");
        writer.writeKeyValueEntry("k", "v");
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString();
        // the array must be closed before the outer }
        assertTrue(actual.contains("\"items\":[{\"k\":\"v\"}]}"));
    }

    @Test
    public void startArrayItemSection_keyValueBeforeArray_separatedByComma() {
        writer.startSection("Root");
        writer.writeKeyValueEntry("before", "val");
        writer.startArrayItemSection("items");
        writer.writeKeyValueEntry("k", "v");
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString();
        assertTrue(actual.contains("\"before\":\"val\",\"items\":[{\"k\":\"v\"}]"));
    }

    // --- Consolidated metrics (MetricsPlugin JSON pattern) ---

    @Test
    public void metrics_multipleTypesInOneBatch_produceSingleFlatJsonLine() {
        // Simulates how MetricsPlugin.run() wraps all metrics in one startSection/endSection
        // in JSON mode, so the entire collection cycle becomes a single flat JSON object.
        long ts = 1710849600000L;
        writer.startSection("Metric", ts);
        writer.writeKeyValueEntry("jvm.memory.heap.used(bytes)", 1048576L);
        writer.writeKeyValueEntry("jvm.memory.heap.used(percent)", 68.4);
        writer.writeKeyValueEntry("os.cpu.load", "NA");
        writer.writeKeyValueEntry("map.size[instance=myMap]", 42L);
        writer.endSection();

        String actual = out.toString().trim();
        // Must be a single line (no embedded newlines)
        assertFalse("consolidated metrics must be a single JSON line", actual.contains("\n"));
        // All metrics must be flat key-value pairs in content — no "entries" array
        assertTrue(actual.contains("\"jvm.memory.heap.used(bytes)\":1048576"));
        assertTrue(actual.contains("\"jvm.memory.heap.used(percent)\":68.4"));
        assertTrue(actual.contains("\"os.cpu.load\":\"NA\""));
        assertTrue(actual.contains("\"map.size[instance=myMap]\":42"));
        assertFalse("metrics must not be wrapped in an entries array", actual.contains("\"entries\""));
        // Envelope
        assertTrue(actual.contains("\"epoch\":" + ts));
        assertTrue(actual.contains("\"name\":\"Metric\""));
    }

    // --- Heartbeat plugins (members array pattern) ---

    @Test
    public void heartbeat_singleMember_producesAddressFieldInMembersArray() {
        // Simulates OperationHeartbeatPlugin / MemberHeartbeatPlugin JSON mode:
        // each deviating member becomes an item in a "members" array with "address" as a field.
        writer.startSection("OperationHeartbeat");
        writer.startArrayItemSection("members");
        writer.writeKeyValueEntry("address", "192.168.1.11:5701");
        writer.writeKeyValueEntry("deviation(%)", 66.66667);
        writer.writeKeyValueEntry("noHeartbeat(ms)", 25000L);
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString().trim();
        assertTrue(actual.contains("\"members\":[{\"address\":\"192.168.1.11:5701\""));
        assertTrue(actual.contains("\"deviation(%)\":66.66667"));
        assertTrue(actual.contains("\"noHeartbeat(ms)\":25000"));
        assertFalse("address must not be embedded in a key", actual.contains("\"member192.168.1.11:5701\""));
    }

    @Test
    public void heartbeat_multipleMembers_producesArrayWithTwoItems() {
        writer.startSection("MemberHeartbeats");
        writer.startArrayItemSection("members");
        writer.writeKeyValueEntry("address", "192.168.1.11:5701");
        writer.writeKeyValueEntry("deviation(%)", 120.0);
        writer.endArrayItemSection();
        writer.startArrayItemSection("members");
        writer.writeKeyValueEntry("address", "192.168.1.12:5701");
        writer.writeKeyValueEntry("deviation(%)", 200.0);
        writer.endArrayItemSection();
        writer.endSection();

        String actual = out.toString().trim();
        assertTrue(actual.contains("\"members\":[{"));
        assertTrue(actual.contains("\"address\":\"192.168.1.11:5701\""));
        assertTrue(actual.contains("\"address\":\"192.168.1.12:5701\""));
        // Two items in the array — look for two opening braces after "members":[
        int membersIdx = actual.indexOf("\"members\":[");
        assertTrue(membersIdx >= 0);
        String membersArray = actual.substring(membersIdx);
        assertEquals("must have exactly two items", 2,
                membersArray.split("\\{\"address\"").length - 1);
    }
}
