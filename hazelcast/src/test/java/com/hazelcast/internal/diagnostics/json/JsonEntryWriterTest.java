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

import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Unit tests for {@link JsonEntryWriter}.
 *
 * <p>Tests cover: envelope structure, string escaping, all numeric types,
 * nested objects, arrays, array items, null values, multiple entries per
 * writer instance, and depth-guard behaviour.
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category(QuickTest.class)
public class JsonEntryWriterTest {

    private StringWriter sw;
    private JsonEntryWriter writer;

    @Before
    public void setUp() {
        sw = new StringWriter();
        writer = new JsonEntryWriter(new PrintWriter(sw));
    }

    // ------------------------------------------------------------------ envelope

    @Test
    public void testEnvelope_emptyContent() {
        writer.startEntry(1_000_000L, "BuildInfo");
        writer.endEntry();
        assertEquals("{\"epoch\":1000000,\"name\":\"BuildInfo\",\"content\":{}}\n", sw.toString());
    }

    @Test
    public void testEnvelope_nameEscaped() {
        writer.startEntry(0L, "Has\"Quote");
        writer.endEntry();
        assertEquals("{\"epoch\":0,\"name\":\"Has\\\"Quote\",\"content\":{}}\n", sw.toString());
    }

    @Test
    public void testDepth_idleAfterEnd() {
        writer.startEntry(1L, "X");
        writer.endEntry();
        assertEquals(-1, writer.getDepth());
    }

    // ------------------------------------------------------------------ multiple entries

    @Test
    public void testMultipleEntries_sameWriter() {
        writer.startEntry(1L, "Lifecycle");
        writer.writeString("state", "STARTED");
        writer.endEntry();

        writer.startEntry(2L, "Lifecycle");
        writer.writeString("state", "SHUTTING_DOWN");
        writer.endEntry();

        String[] lines = sw.toString().split("\n");
        assertEquals(2, lines.length);
        assertEquals("{\"epoch\":1,\"name\":\"Lifecycle\",\"content\":{\"state\":\"STARTED\"}}", lines[0]);
        assertEquals("{\"epoch\":2,\"name\":\"Lifecycle\",\"content\":{\"state\":\"SHUTTING_DOWN\"}}", lines[1]);
    }

    // ------------------------------------------------------------------ string values

    @Test
    public void testWriteString_basic() {
        writer.startEntry(1L, "X");
        writer.writeString("key", "value");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"key\":\"value\"}}\n", sw.toString());
    }

    @Test
    public void testWriteString_null() {
        writer.startEntry(1L, "X");
        writer.writeString("key", null);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"key\":null}}\n", sw.toString());
    }

    @Test
    public void testWriteString_escapeDoubleQuote() {
        writer.startEntry(1L, "X");
        writer.writeString("k", "say \"hello\"");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"k\":\"say \\\"hello\\\"\"}}\n", sw.toString());
    }

    @Test
    public void testWriteString_escapeBackslash() {
        writer.startEntry(1L, "X");
        writer.writeString("k", "C:\\path");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"k\":\"C:\\\\path\"}}\n", sw.toString());
    }

    @Test
    public void testWriteString_escapeControlChars() {
        writer.startEntry(1L, "X");
        writer.writeString("k", "a\nb\tc\r");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"k\":\"a\\nb\\tc\\r\"}}\n", sw.toString());
    }

    @Test
    public void testWriteString_escapeOtherControlChar() {
        // ASCII 0x01 (SOH) must be encoded as \u0001
        writer.startEntry(1L, "X");
        writer.writeString("k", "\u0001");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"k\":\"\\u0001\"}}\n", sw.toString());
    }

    // ------------------------------------------------------------------ numeric types

    @Test
    public void testWriteLong_positive() {
        writer.startEntry(1L, "X");
        writer.writeLong("n", 42L);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"n\":42}}\n", sw.toString());
    }

    @Test
    public void testWriteLong_negative() {
        writer.startEntry(1L, "X");
        writer.writeLong("n", -99L);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"n\":-99}}\n", sw.toString());
    }

    @Test
    public void testWriteLong_zero() {
        writer.startEntry(1L, "X");
        writer.writeLong("n", 0L);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"n\":0}}\n", sw.toString());
    }

    @Test
    public void testWriteLong_maxValue() {
        writer.startEntry(1L, "X");
        writer.writeLong("n", Long.MAX_VALUE);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"n\":" + Long.MAX_VALUE + "}}\n", sw.toString());
    }

    @Test
    public void testWriteLong_minValue() {
        writer.startEntry(1L, "X");
        writer.writeLong("n", Long.MIN_VALUE);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"n\":" + Long.MIN_VALUE + "}}\n", sw.toString());
    }

    @Test
    public void testWriteDouble_basic() {
        writer.startEntry(1L, "X");
        writer.writeDouble("d", 3.14);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"d\":3.14}}\n", sw.toString());
    }

    @Test
    public void testWriteDouble_whole() {
        writer.startEntry(1L, "X");
        writer.writeDouble("d", 100.0);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"d\":100.0}}\n", sw.toString());
    }

    @Test
    public void testWriteBoolean_true() {
        writer.startEntry(1L, "X");
        writer.writeBoolean("flag", true);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"flag\":true}}\n", sw.toString());
    }

    @Test
    public void testWriteBoolean_false() {
        writer.startEntry(1L, "X");
        writer.writeBoolean("flag", false);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"flag\":false}}\n", sw.toString());
    }

    @Test
    public void testWriteNull_key() {
        writer.startEntry(1L, "X");
        writer.writeNull("absent");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"absent\":null}}\n", sw.toString());
    }

    // ------------------------------------------------------------------ multiple keys

    @Test
    public void testMultipleKeys_commasSeparated() {
        writer.startEntry(1L, "X");
        writer.writeString("a", "1");
        writer.writeLong("b", 2L);
        writer.writeBoolean("c", true);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"a\":\"1\",\"b\":2,\"c\":true}}\n",
                sw.toString());
    }

    // ------------------------------------------------------------------ nested objects

    @Test
    public void testNestedObject_single() {
        writer.startEntry(1L, "X");
        writer.startObject("inner");
        writer.writeString("k", "v");
        writer.endObject();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"inner\":{\"k\":\"v\"}}}\n", sw.toString());
    }

    @Test
    public void testNestedObject_empty() {
        writer.startEntry(1L, "X");
        writer.startObject("empty");
        writer.endObject();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"empty\":{}}}\n", sw.toString());
    }

    @Test
    public void testNestedObject_doubleNested() {
        writer.startEntry(1L, "X");
        writer.startObject("outer");
        writer.startObject("inner");
        writer.writeLong("n", 7L);
        writer.endObject();
        writer.endObject();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"outer\":{\"inner\":{\"n\":7}}}}\n",
                sw.toString());
    }

    @Test
    public void testNestedObject_keyAfterObject() {
        writer.startEntry(1L, "X");
        writer.startObject("obj");
        writer.writeString("inside", "val");
        writer.endObject();
        writer.writeString("after", "outside");
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"obj\":{\"inside\":\"val\"},\"after\":\"outside\"}}\n",
                sw.toString());
    }

    // ------------------------------------------------------------------ arrays and array items

    @Test
    public void testArray_empty() {
        writer.startEntry(1L, "X");
        writer.startArray("items");
        writer.endArray();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"items\":[]}}\n", sw.toString());
    }

    @Test
    public void testArray_singleItem() {
        writer.startEntry(1L, "X");
        writer.startArray("members");
        writer.startArrayItem();
        writer.writeString("address", "192.168.1.1:5701");
        writer.endArrayItem();
        writer.endArray();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\","
                + "\"content\":{\"members\":[{\"address\":\"192.168.1.1:5701\"}]}}\n", sw.toString());
    }

    @Test
    public void testArray_multipleItems() {
        writer.startEntry(1L, "X");
        writer.startArray("list");
        writer.startArrayItem();
        writer.writeLong("n", 1L);
        writer.endArrayItem();
        writer.startArrayItem();
        writer.writeLong("n", 2L);
        writer.endArrayItem();
        writer.endArray();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\",\"content\":{\"list\":[{\"n\":1},{\"n\":2}]}}\n",
                sw.toString());
    }

    @Test
    public void testArray_nestedObjectInsideItem() {
        writer.startEntry(1L, "X");
        writer.startArray("connections");
        writer.startArrayItem();
        writer.writeString("from", "127.0.0.1:5701");
        writer.startObject("samples");
        writer.writeLong("count", 3L);
        writer.endObject();
        writer.endArrayItem();
        writer.endArray();
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\","
                + "\"content\":{\"connections\":[{\"from\":\"127.0.0.1:5701\","
                + "\"samples\":{\"count\":3}}]}}\n", sw.toString());
    }

    @Test
    public void testArray_keyAfterArray() {
        writer.startEntry(1L, "X");
        writer.writeLong("count", 2L);
        writer.startArray("items");
        writer.startArrayItem();
        writer.writeString("x", "a");
        writer.endArrayItem();
        writer.endArray();
        writer.writeBoolean("done", true);
        writer.endEntry();
        assertEquals("{\"epoch\":1,\"name\":\"X\","
                + "\"content\":{\"count\":2,\"items\":[{\"x\":\"a\"}],\"done\":true}}\n", sw.toString());
    }

    // ------------------------------------------------------------------ error cases

    @Test
    public void testStartEntry_throwsIfAlreadyOpen() {
        writer.startEntry(1L, "A");
        assertThrows(IllegalStateException.class, () -> writer.startEntry(2L, "B"));
    }

    @Test
    public void testStartArrayItem_throwsIfNotInArray() {
        writer.startEntry(1L, "X");
        assertThrows(IllegalStateException.class, writer::startArrayItem);
    }

    // ------------------------------------------------------------------ epoch edge cases

    @Test
    public void testEpoch_zero() {
        writer.startEntry(0L, "X");
        writer.endEntry();
        assertEquals("{\"epoch\":0,\"name\":\"X\",\"content\":{}}\n", sw.toString());
    }

    @Test
    public void testEpoch_largeValue() {
        writer.startEntry(1_742_385_600_000L, "Metric");
        writer.endEntry();
        assertEquals("{\"epoch\":1742385600000,\"name\":\"Metric\",\"content\":{}}\n", sw.toString());
    }

    // ------------------------------------------------------------------ init (re-use with new writer)

    @Test
    public void testInit_resetsState() {
        writer.startEntry(1L, "A");
        writer.endEntry();

        StringWriter sw2 = new StringWriter();
        writer.init(new PrintWriter(sw2));

        writer.startEntry(2L, "B");
        writer.writeString("x", "y");
        writer.endEntry();

        // old writer unchanged, new writer has the second entry
        assertEquals("{\"epoch\":1,\"name\":\"A\",\"content\":{}}\n", sw.toString());
        assertEquals("{\"epoch\":2,\"name\":\"B\",\"content\":{\"x\":\"y\"}}\n", sw2.toString());
    }
}
