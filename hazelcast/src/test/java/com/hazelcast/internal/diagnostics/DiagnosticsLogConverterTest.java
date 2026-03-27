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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link DiagnosticsLogConverter}.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Parsing: null/empty/malformed input</li>
 *   <li>Parsing: header with and without epoch</li>
 *   <li>Parsing: all value types (Long, Long with commas, Double, Boolean, null, String)</li>
 *   <li>Parsing: plain entries (writeEntry) → entries list</li>
 *   <li>Parsing: duplicate keys → list promotion</li>
 *   <li>Parsing: nested sections (deep nesting, multiple closing brackets on one line)</li>
 *   <li>Parsing: empty sections (bug fix: SectionName[] must yield empty Map, not plain entry)</li>
 *   <li>Parsing: special-character escaping roundtrip</li>
 *   <li>Parsing: writeKeyValueEntryAsDateTime value is kept as String</li>
 *   <li>JSON serialisation: typed values (Boolean, Long, Double, null) are JSON literals</li>
 *   <li>JSON serialisation: String values are JSON-escaped</li>
 *   <li>JSON serialisation: epoch field omitted when null</li>
 *   <li>JSON serialisation: empty content map</li>
 *   <li>JSON serialisation: nested maps and lists</li>
 * </ul>
 */
@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class DiagnosticsLogConverterTest extends HazelcastTestSupport {

    private DiagnosticsLogConverter converter;

    @Before
    public void setup() {
        converter = new DiagnosticsLogConverter();
    }

    // -----------------------------------------------------------------------
    // parseStandard — null / empty / malformed
    // -----------------------------------------------------------------------

    @Test
    public void testEmptyAndMalformed() {
        assertNull(converter.parseStandard(null));
        assertNull(converter.parseStandard(""));
        assertNull(converter.parseStandard("   "));
        assertNull(converter.parseStandard("Malformed line"));
    }

    // -----------------------------------------------------------------------
    // parseStandard — header (time, epoch, section name)
    // -----------------------------------------------------------------------

    @Test
    public void testParseStandard_withEpoch_parsedCorrectly() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(true, null);
        writer.init(new PrintWriter(out));
        long now = 1710812712000L;
        writer.startSection("MySection", now);
        writer.writeKeyValueEntry("k", "v");
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        assertEquals("MySection", entry.getName());
        assertEquals(now, (long) entry.getEpoch());
    }

    @Test
    public void testParseStandard_withoutEpoch_epochDerivedFromTime() {
        String standard = "19-03-2026 01:45:12 NoEpochSection[" + System.lineSeparator()
                + "                          key=value" + System.lineSeparator()
                + "]" + System.lineSeparator();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(standard);
        assertNotNull(entry);
        assertEquals("19-03-2026 01:45:12", entry.getTime());
        assertEquals("NoEpochSection", entry.getName());

        long expectedEpoch = LocalDateTime.parse("19-03-2026 01:45:12",
                DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertNotNull("epoch must be derived when absent from the STANDARD header", entry.getEpoch());
        assertEquals("derived epoch must match time string", expectedEpoch, (long) entry.getEpoch());
        assertEquals("derived epoch must have millis=000", 0L, entry.getEpoch() % 1000);
        assertEquals("value", entry.getContent().get("key"));
    }

    // -----------------------------------------------------------------------
    // parseStandard — value type parsing
    // -----------------------------------------------------------------------

    @Test
    public void testParseStandard_longValue_parsedAsLong() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("count", 42L);
        });
        assertEquals(42L, entry.getContent().get("count"));
    }

    @Test
    public void testParseStandard_negativeValue_parsedAsLong() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("delta", -99L);
        });
        assertEquals(-99L, entry.getContent().get("delta"));
    }

    @Test
    public void testParseStandard_commaFormattedLong_parsedAsLong() {
        // writeLong outputs comma-formatted numbers, e.g. 1234567 → "1,234,567"
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("bytes", 1234567L);
        });
        assertEquals(1234567L, entry.getContent().get("bytes"));
    }

    @Test
    public void testParseStandard_longMinValue_parsedAsLong() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("min", Long.MIN_VALUE);
        });
        assertEquals(Long.MIN_VALUE, entry.getContent().get("min"));
    }

    @Test
    public void testParseStandard_doubleValue_parsedAsDouble() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("ratio", 3.14);
        });
        assertEquals(3.14, (Double) entry.getContent().get("ratio"), 0.0001);
    }

    @Test
    public void testParseStandard_booleanTrue_parsedAsBoolean() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("flag", true);
        });
        assertEquals(Boolean.TRUE, entry.getContent().get("flag"));
    }

    @Test
    public void testParseStandard_booleanFalse_parsedAsBoolean() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("flag", false);
        });
        assertEquals(Boolean.FALSE, entry.getContent().get("flag"));
    }

    @Test
    public void testParseStandard_nullStringValue_parsedAsNull() {
        // writeKeyValueEntry(String, String) with null writes the literal "null"
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("key", (String) null);
        });
        assertNull("null string must be parsed back as Java null", entry.getContent().get("key"));
    }

    @Test
    public void testParseStandard_plainStringValue_parsedAsString() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("host", "192.168.1.1");
        });
        assertEquals("192.168.1.1", entry.getContent().get("host"));
    }

    @Test
    public void testParseStandard_dateTimeFormattedValue_parsedAsString() {
        // writeKeyValueEntryAsDateTime emits "dd-MM-yyyy HH:mm:ss" which is not a number
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntryAsDateTime("startTime", 1710812712000L);
        });
        Object value = entry.getContent().get("startTime");
        assertTrue("date-time value must remain a String", value instanceof String);
        String s = (String) value;
        // epoch 1710812712000 = 19-03-2024 in most timezones; verify format not year
        assertTrue("must contain dash date separator", s.contains("-"));
        assertTrue("must contain colon time separator", s.contains(":"));
    }

    // -----------------------------------------------------------------------
    // parseStandard — plain entries (writeEntry → entries list)
    // -----------------------------------------------------------------------

    @Test
    public void testParseStandard_plainEntry_appearsInEntriesList() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeEntry("plain text");
        });
        List<Object> entries = (List<Object>) entry.getContent().get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals("plain text", entries.get(0));
    }

    @Test
    public void testParseStandard_multiplePlainEntries_allInEntriesList() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeEntry("first");
            w.writeEntry("second");
            w.writeEntry("third");
        });
        List<Object> entries = (List<Object>) entry.getContent().get("entries");
        assertNotNull(entries);
        assertEquals(3, entries.size());
        assertEquals("first", entries.get(0));
        assertEquals("second", entries.get(1));
        assertEquals("third", entries.get(2));
    }

    @Test
    public void testParseStandard_plainEntryWithSpecialChars_unescaped() {
        // writeEntry escapes [ ] = \
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeEntry("a=b [c] \\d");
        });
        List<Object> entries = (List<Object>) entry.getContent().get("entries");
        assertNotNull(entries);
        assertEquals("a=b [c] \\d", entries.get(0));
    }

    @Test
    public void testParseStandard_mixedKeyValueAndPlainEntries() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("count", 5L);
            w.writeEntry("line1");
            w.writeKeyValueEntry("flag", true);
            w.writeEntry("line2");
        });
        Map<String, Object> content = entry.getContent();
        assertEquals(5L, content.get("count"));
        assertEquals(Boolean.TRUE, content.get("flag"));
        List<Object> entries = (List<Object>) content.get("entries");
        assertNotNull(entries);
        assertEquals(2, entries.size());
        assertEquals("line1", entries.get(0));
        assertEquals("line2", entries.get(1));
    }

    // -----------------------------------------------------------------------
    // parseStandard — duplicate keys → list promotion
    // -----------------------------------------------------------------------

    @Test
    public void testMultipleItemsInLists() {
        String standard = "19-03-2026 01:45:12 1710812712000 MySection[" + System.lineSeparator()
                + "                          item=1" + System.lineSeparator()
                + "                          item=2" + System.lineSeparator()
                + "                          item=3" + System.lineSeparator()
                + "                          other=val" + System.lineSeparator()
                + "]";

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(standard);
        assertNotNull(entry);
        Object item = entry.getContent().get("item");
        assertTrue(item instanceof List);
        List<Long> list = (List<Long>) item;
        assertEquals(3, list.size());
        assertEquals(1L, (long) list.get(0));
        assertEquals(2L, (long) list.get(1));
        assertEquals(3L, (long) list.get(2));
    }

    @Test
    public void testParseStandard_duplicateStringKeys_promotedToList() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("member", "127.0.0.1:5701");
            w.writeKeyValueEntry("member", "127.0.0.1:5702");
        });
        Object members = entry.getContent().get("member");
        assertTrue(members instanceof List);
        List<String> list = (List<String>) members;
        assertEquals(2, list.size());
        assertEquals("127.0.0.1:5701", list.get(0));
        assertEquals("127.0.0.1:5702", list.get(1));
    }

    // -----------------------------------------------------------------------
    // parseStandard — nested sections
    // -----------------------------------------------------------------------

    @Test
    public void testDeeplyNestedSections() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Level1");
        writer.startSection("Level2");
        writer.startSection("Level3");
        writer.startSection("Level4");
        writer.writeKeyValueEntry("key", "value");
        writer.endSection();
        writer.endSection();
        writer.endSection();
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);

        Map<String, Object> l2 = (Map<String, Object>) entry.getContent().get("Level2");
        Map<String, Object> l3 = (Map<String, Object>) l2.get("Level3");
        Map<String, Object> l4 = (Map<String, Object>) l3.get("Level4");
        assertEquals("value", l4.get("key"));
    }

    @Test
    public void testParseStandard_multipleConsecutiveClosingBrackets() {
        // When all sub-sections close at once the writer puts multiple ] on one line
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Root");
        writer.startSection("A");
        writer.startSection("B");
        writer.writeKeyValueEntry("leaf", "val");
        writer.endSection(); // close B
        writer.endSection(); // close A — B's ] and A's ] end up on same line as "val"
        writer.endSection(); // close Root

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        Map<String, Object> a = (Map<String, Object>) entry.getContent().get("A");
        Map<String, Object> b = (Map<String, Object>) a.get("B");
        assertEquals("val", b.get("leaf"));
    }

    @Test
    public void testEmptyLinesBetweenContent() {
        String standard = "19-03-2026 01:45:12 1710812712000 MySection[" + System.lineSeparator()
                + System.lineSeparator()
                + "                          key1=value1" + System.lineSeparator()
                + System.lineSeparator()
                + "                          SubSection[" + System.lineSeparator()
                + "                                  nestedKey=123" + System.lineSeparator()
                + "                          ]" + System.lineSeparator()
                + "]" + System.lineSeparator();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(standard);
        assertNotNull(entry);
        assertEquals("value1", entry.getContent().get("key1"));
        Map<String, Object> sub = (Map<String, Object>) entry.getContent().get("SubSection");
        assertEquals(123L, sub.get("nestedKey"));
    }

    // -----------------------------------------------------------------------
    // parseStandard — empty sections (bug-fix coverage)
    // -----------------------------------------------------------------------

    @Test
    public void testParseStandard_emptySection_parsedAsEmptyMap() {
        // startSection immediately followed by endSection produces "SectionName[]" on one line
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Root");
        writer.startSection("EmptySection");
        writer.endSection(); // EmptySection closes
        writer.writeKeyValueEntry("after", "value");
        writer.endSection(); // Root closes

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);

        // EmptySection must be a Map (possibly empty), not the string "EmptySection["
        Object emptySection = entry.getContent().get("EmptySection");
        assertNotNull("EmptySection must be present", emptySection);
        assertTrue("EmptySection must be a Map, not: " + emptySection, emptySection instanceof Map);
        assertEquals(0, ((Map<?, ?>) emptySection).size());

        // Sibling key after empty section must still be parsed
        assertEquals("value", entry.getContent().get("after"));
    }

    @Test
    public void testParseStandard_emptySectionClosingParentOnSameLine_parsedCorrectly() {
        // When root has only an empty child, the child's ] and root's ] appear on the same line
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Root");
        writer.startSection("OnlyChild");
        writer.endSection(); // OnlyChild — produces "OnlyChild[]]" on one line
        writer.endSection(); // Root

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        Object child = entry.getContent().get("OnlyChild");
        assertNotNull("OnlyChild must be present", child);
        assertTrue("OnlyChild must be a Map", child instanceof Map);
    }

    @Test
    public void testParseStandard_multipleEmptySections_allParsedAsMaps() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Root");
        writer.startSection("Empty1");
        writer.endSection();
        writer.startSection("Empty2");
        writer.endSection();
        writer.writeKeyValueEntry("real", 1L);
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        assertTrue(entry.getContent().get("Empty1") instanceof Map);
        assertTrue(entry.getContent().get("Empty2") instanceof Map);
        assertEquals(1L, entry.getContent().get("real"));
    }

    @Test
    public void testParseStandard_emptySectionWithEscapedNameChars_nameUnescaped() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Root");
        writer.startSection("Section[With]Brackets");
        writer.endSection();
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        Object section = entry.getContent().get("Section[With]Brackets");
        assertNotNull(section);
        assertTrue(section instanceof Map);
    }

    // -----------------------------------------------------------------------
    // parseStandard — special-character escaping roundtrip
    // -----------------------------------------------------------------------

    @Test
    public void testRoundTrip_Escaping() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("Section[With]Brackets");
        writer.writeKeyValueEntry("Key=With=Equals", "Value\\With\\Backslash");
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        assertEquals("Section[With]Brackets", entry.getName());
        assertEquals("Value\\With\\Backslash", entry.getContent().get("Key=With=Equals"));
    }

    @Test
    public void testRoundTrip_NewlineAndCarriageReturnInValue() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("msg", "line1\nline2\rend");
        });
        assertEquals("line1\nline2\rend", entry.getContent().get("msg"));
    }

    @Test
    public void testRoundTrip_StandardToJson() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(true, null);
        writer.init(new PrintWriter(out));

        long now = 1710812712000L;
        writer.startSection("MySection", now);
        writer.writeKeyValueEntry("key1", "value1");
        writer.writeKeyValueEntry("keyWithSpecialChars", "value with [ ] = \\ \n \r");
        writer.startSection("SubSection");
        writer.writeKeyValueEntry("nestedKey", 1234567L);
        writer.writeKeyValueEntry("doubleKey", 123.456);
        writer.writeEntry("simple entry with \n newline");
        writer.endSection();
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        assertEquals("MySection", entry.getName());
        assertEquals(now, (long) entry.getEpoch());

        Map<String, Object> content = entry.getContent();
        assertEquals("value1", content.get("key1"));
        assertEquals("value with [ ] = \\ \n \r", content.get("keyWithSpecialChars"));

        Map<String, Object> subSection = (Map<String, Object>) content.get("SubSection");
        assertNotNull(subSection);
        assertEquals(1234567L, subSection.get("nestedKey"));
        assertEquals(123.456, subSection.get("doubleKey"));
        List<Object> entries = (List<Object>) subSection.get("entries");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals("simple entry with \n newline", entries.get(0).toString());

        String json = converter.toJson(entry);
        assertNotNull(json);
        assertTrue(json.contains("\"nestedKey\":1234567"));
        // plain string entries must be wrapped as {"text":"..."}
        assertTrue(json.contains("\"entries\":[{\"text\":\"simple entry with \\n newline\"}]"));
    }

    @Test
    public void testSlowOperationsSimulation() {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));

        writer.startSection("SlowOperations");
        writer.startSection("MyOperation");
        writer.writeKeyValueEntry("invocations", 2);

        writer.startSection("stackTrace");
        writer.writeEntry("line1");
        writer.writeEntry("line2");
        writer.endSection();

        writer.writeKeyValueEntry("afterStack", "test");

        writer.startSection("slowInvocations");
        writer.writeKeyValueEntry("startedAt", 1000L);
        writer.writeKeyValueEntry("duration(ms)", 500L);
        writer.writeKeyValueEntry("startedAt", 2000L);
        writer.writeKeyValueEntry("duration(ms)", 600L);
        writer.endSection();

        writer.endSection();
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        assertEquals("SlowOperations", entry.getName());

        Map<String, Object> myOp = (Map<String, Object>) entry.getContent().get("MyOperation");
        assertNotNull(myOp);

        Map<String, Object> stackTrace = (Map<String, Object>) myOp.get("stackTrace");
        assertNotNull(stackTrace);
        List<String> stackLines = (List<String>) stackTrace.get("entries");
        assertEquals(2, stackLines.size());
        assertEquals("line1", stackLines.get(0));
        assertEquals("line2", stackLines.get(1));

        Map<String, Object> invocations = (Map<String, Object>) myOp.get("slowInvocations");
        assertNotNull(invocations);
        List<Long> startedAts = (List<Long>) invocations.get("startedAt");
        assertEquals(2, startedAts.size());
        assertEquals(1000L, (long) startedAts.get(0));
        assertEquals(2000L, (long) startedAts.get(1));

        String json = converter.toJson(entry);
        assertNotNull(json);
        assertTrue(json.contains("\"startedAt\":[1000,2000]"));
        // plain string entries are wrapped as {"text":"..."} objects
        assertTrue(json.contains("\"entries\":[{\"text\":\"line1\"},{\"text\":\"line2\"}]"));
    }

    // -----------------------------------------------------------------------
    // toJson — typed value serialisation
    // -----------------------------------------------------------------------

    @Test
    public void testToJson_noEpochInStandard_epochDerivedAndEmitted() {
        // parseViaWriter uses DiagnosticsLogWriterImpl without epoch, so the STANDARD output
        // has no epoch in the header. parseStandard must derive it from the time field.
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("k", "v");
        });
        assertNotNull("epoch must be derived from time when absent from STANDARD header", entry.getEpoch());
        assertEquals("derived epoch must have millis=000", 0L, entry.getEpoch() % 1000);
        String json = converter.toJson(entry);
        assertTrue("epoch must be present in JSON output", json.contains("\"epoch\":"));
        assertTrue(json.contains("\"k\":\"v\""));
    }

    @Test
    public void testToJson_longValue_isUnquotedNumeric() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("count", 42L);
        });
        String json = converter.toJson(entry);
        assertTrue("Long must appear as unquoted number", json.contains("\"count\":42"));
        assertFalse("Long must not be a quoted string", json.contains("\"count\":\""));
    }

    @Test
    public void testToJson_doubleValue_isUnquotedNumeric() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("ratio", 1.5);
        });
        String json = converter.toJson(entry);
        assertTrue("Double must appear as unquoted number", json.contains("\"ratio\":1.5"));
        assertFalse("Double must not be a quoted string", json.contains("\"ratio\":\""));
    }

    @Test
    public void testToJson_booleanTrue_isUnquotedTrue() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("active", true);
        });
        String json = converter.toJson(entry);
        assertTrue("Boolean true must be unquoted", json.contains("\"active\":true"));
        assertFalse("Boolean true must not be a quoted string", json.contains("\"active\":\""));
    }

    @Test
    public void testToJson_booleanFalse_isUnquotedFalse() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("active", false);
        });
        String json = converter.toJson(entry);
        assertTrue("Boolean false must be unquoted", json.contains("\"active\":false"));
    }

    @Test
    public void testToJson_nullValue_isNullLiteral() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("key", (String) null);
        });
        String json = converter.toJson(entry);
        assertTrue("null value must serialize as JSON null", json.contains("\"key\":null"));
        assertFalse("null value must not be a quoted string", json.contains("\"key\":\"null\""));
    }

    @Test
    public void testToJson_emptyContentMap_isEmptyJsonObject() {
        // A section with no keys produces {}
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));
        writer.startSection("Empty");
        writer.endSection();

        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull(entry);
        String json = converter.toJson(entry);
        assertTrue("Empty content must be {}", json.contains("\"content\":{}"));
    }

    @Test
    public void testToJson_stringWithJsonSpecialChars_properllyEscaped() {
        // Values containing ", \, newline, CR, tab must be JSON-escaped
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("msg", "say \"hello\"\nline2\ttabbed");
        });
        String json = converter.toJson(entry);
        assertTrue("Quote must be escaped", json.contains("\\\"hello\\\""));
        assertTrue("Newline must be escaped", json.contains("\\n"));
        assertTrue("Tab must be escaped", json.contains("\\t"));
    }

    @Test
    public void testToJson_backslashInValue_doubleEscaped() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("path", "C:\\Users\\test");
        });
        String json = converter.toJson(entry);
        assertTrue("Backslash must be double-escaped in JSON", json.contains("C:\\\\Users\\\\test"));
    }

    @Test
    public void testToJson_nestedSection_rendersAsNestedJsonObject() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.startSection("child");
            w.writeKeyValueEntry("inner", "val");
            w.endSection();
        });
        String json = converter.toJson(entry);
        assertTrue("Nested section must be a JSON object", json.contains("\"child\":{"));
        assertTrue("Nested key must be present", json.contains("\"inner\":\"val\""));
    }

    @Test
    public void testToJson_listValue_rendersAsJsonArray() {
        // Two entries with same key → List in POJO → array in JSON
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeKeyValueEntry("id", 1L);
            w.writeKeyValueEntry("id", 2L);
        });
        String json = converter.toJson(entry);
        assertTrue("Duplicate key must become JSON array", json.contains("\"id\":[1,2]"));
    }

    @Test
    public void testToJson_entriesList_rendersAsJsonArrayOfObjects() {
        DiagnosticsLogConverter.DiagnosticEntry entry = parseViaWriter(w -> {
            w.writeEntry("alpha");
            w.writeEntry("beta");
        });
        String json = converter.toJson(entry);
        // plain string entries must be wrapped as {"text":"..."} objects
        assertTrue("entries list must be a JSON array of objects",
                json.contains("\"entries\":[{\"text\":\"alpha\"},{\"text\":\"beta\"}]"));
        assertFalse(json.contains("\"entries\":[\"alpha\""));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a root section via a STANDARD writer with the given consumer,
     * then parses and returns the resulting {@link DiagnosticsLogConverter.DiagnosticEntry}.
     */
    @SuppressWarnings("unchecked")
    private DiagnosticsLogConverter.DiagnosticEntry parseViaWriter(WriterConsumer consumer) {
        CharArrayWriter out = new CharArrayWriter();
        DiagnosticsLogWriterImpl writer = new DiagnosticsLogWriterImpl(false, null);
        writer.init(new PrintWriter(out));
        writer.startSection("Root");
        consumer.write(writer);
        writer.endSection();
        DiagnosticsLogConverter.DiagnosticEntry entry = converter.parseStandard(out.toString());
        assertNotNull("parseStandard must not return null", entry);
        return entry;
    }

    @FunctionalInterface
    private interface WriterConsumer {
        void write(DiagnosticsLogWriterImpl writer);
    }
}
