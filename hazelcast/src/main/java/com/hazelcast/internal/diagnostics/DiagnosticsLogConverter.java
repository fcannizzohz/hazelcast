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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class that converts between STANDARD and JSON diagnostics log formats.
 */
public class DiagnosticsLogConverter {

    private static final ILogger LOGGER = Logger.getLogger(DiagnosticsLogConverter.class);

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private static final String TOP_LEVEL_PATTERN_STR =
            "^(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2}) (?:(\\d+) )?(.+)\\[\\]?$";
    private static final Pattern TOP_LEVEL_PATTERN = Pattern.compile(TOP_LEVEL_PATTERN_STR);
    // Matches single-line entries: "date [epoch] Name[content]" — all on one line.
    // Used as a fallback when TOP_LEVEL_PATTERN fails (e.g. MetricsPlugin writeSectionKeyValue output).
    private static final Pattern TOP_LEVEL_INLINE_PATTERN = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2}) (?:(\\d+) )?([^\\[]+)\\[(.+)\\]$");
    private static final int INLINE_GROUP_CONTENT = 4;
    // Matches the first line of any STANDARD diagnostics entry (date/time header).
    private static final Pattern ENTRY_HEADER_PATTERN =
            Pattern.compile("^\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}:\\d{2}.*");
    private static final String INDENT = "                          ";
    private static final int INDENT_BASE = 26;
    private static final int DEPTH_INDENT_SIZE = 8;

    public static class DiagnosticEntry {
        private String time;
        private Long epoch;
        private String name;
        private Map<String, Object> content = new LinkedHashMap<>();

        public String getTime() {
            return time;
        }

        public Long getEpoch() {
            return epoch;
        }

        public String getName() {
            return name;
        }

        public Map<String, Object> getContent() {
            return content;
        }

        @Override
        public String toString() {
            return "DiagnosticEntry{time='" + time + "', epoch=" + epoch
                    + ", name='" + name + "', content=" + content + "}";
        }
    }

    public DiagnosticEntry parseStandard(String entryStr) {
        if (entryStr == null || entryStr.trim().isEmpty()) {
            return null;
        }
        String[] lines = entryStr.split("\\R");
        if (lines.length == 0) {
            return null;
        }

        Matcher matcher = TOP_LEVEL_PATTERN.matcher(lines[0]);
        if (!matcher.matches()) {
            // Fallback: try single-line format "date [epoch] Name[content]"
            Matcher inlineMatcher = TOP_LEVEL_INLINE_PATTERN.matcher(lines[0]);
            if (!inlineMatcher.matches()) {
                LOGGER.severe("Failed to parse diagnostics entry metadata (first line): " + lines[0]);
                return null;
            }
            DiagnosticEntry entry = new DiagnosticEntry();
            entry.time = inlineMatcher.group(1);
            String epochStr = inlineMatcher.group(2);
            if (epochStr != null) {
                entry.epoch = Long.parseLong(epochStr);
            } else {
                entry.epoch = deriveEpochFromTime(entry.time);
            }
            entry.name = unescape(inlineMatcher.group(3).trim());
            String inlineContent = inlineMatcher.group(INLINE_GROUP_CONTENT);
            if (!inlineContent.isEmpty()) {
                parseValueLine(inlineContent, entry.content);
            }
            return entry;
        }

        DiagnosticEntry entry = new DiagnosticEntry();
        entry.time = matcher.group(1);
        String epochStr = matcher.group(2);
        if (epochStr != null) {
            entry.epoch = Long.parseLong(epochStr);
        } else {
            entry.epoch = deriveEpochFromTime(entry.time);
        }
        entry.name = unescape(matcher.group(3));

        parseContent(lines, 1, entry.content, 0);

        return entry;
    }

    /**
     * Parses the {@code "dd-MM-yyyy HH:mm:ss"} time string into a Unix epoch millisecond value
     * using the JVM default timezone.  The parsed time has second precision, so the millisecond
     * component is always {@code 000}.  Returns {@code 0} and logs a warning on parse failure.
     */
    private static long deriveEpochFromTime(String time) {
        try {
            return LocalDateTime.parse(time, TIME_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            LOGGER.warning("Could not derive epoch from time string '" + time + "': " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Formats a Unix epoch millisecond value as a {@code "dd-MM-yyyy HH:mm:ss"} string
     * in the JVM default timezone.  Used when reconstructing the STANDARD header from a
     * JSON entry that does not carry a {@code "time"} field.
     */
    private static String formatEpochAsTime(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.systemDefault()
        ).format(TIME_FORMATTER);
    }

    private int parseContent(String[] lines, int lineIndex, Map<String, Object> content, int depth) {
        while (lineIndex < lines.length) {
            String line = lines[lineIndex];
            if (line == null || line.trim().isEmpty()) {
                lineIndex++;
                continue;
            }
            if (line.trim().equals("]")) {
                return lineIndex;
            }
            String contentLine = stripIndent(line, depth);
            if (contentLine.endsWith("[")) {
                lineIndex = parseSectionLine(lines, lineIndex, content, contentLine, depth);
            } else {
                int closingBrackets = countTrailingBrackets(contentLine);
                String temp = contentLine.substring(0, contentLine.length() - closingBrackets);
                if (endsWithUnescapedOpenBracket(temp)) {
                    // Empty section inline: e.g. "Name[]" or "Name[]]"
                    // The first ] closes the empty section itself; remaining ] close parent scopes.
                    addEmptySection(content, temp);
                    int parentCloses = closingBrackets - 1;
                    if (parentCloses > 0) {
                        return returnWithRemainingBrackets(lines, lineIndex, parentCloses);
                    }
                } else {
                    parseValueLine(temp, content);
                    if (closingBrackets > 0) {
                        return returnWithRemainingBrackets(lines, lineIndex, closingBrackets);
                    }
                }
            }
            lineIndex++;
        }
        return lineIndex;
    }

    private int parseSectionLine(String[] lines, int lineIndex,
                                 Map<String, Object> content, String contentLine, int depth) {
        String sectionName = unescape(contentLine.substring(0, contentLine.length() - 1).trim());
        Map<String, Object> subSection = new LinkedHashMap<>();
        putValue(content, sectionName, subSection);
        return parseContent(lines, lineIndex + 1, subSection, depth + 1);
    }

    private String stripIndent(String line, int depth) {
        if (!line.startsWith(INDENT)) {
            return line;
        }
        String result = line.substring(INDENT.length());
        for (int i = 0; i < depth; i++) {
            if (result.startsWith("        ")) {
                result = result.substring(DEPTH_INDENT_SIZE);
            } else {
                break;
            }
        }
        return result;
    }

    private void parseValueLine(String temp, Map<String, Object> content) {
        int eqIndex = findKeyValueSplitIndex(temp);
        if (eqIndex != -1) {
            String key = unescape(temp.substring(0, eqIndex).trim());
            String value = temp.substring(eqIndex + 1).trim();
            putValue(content, key, tryParseNumber(unescape(value)));
        } else {
            String value = temp.trim();
            if (!value.isEmpty()) {
                putValue(content, "entries", unescape(value));
            }
        }
    }

    private static int countTrailingBrackets(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == ']'; i--) {
            count++;
        }
        return count;
    }

    /**
     * Returns true when the string ends with an unescaped {@code [} character.
     * An even number of preceding backslashes means the bracket is not escaped.
     */
    private static boolean endsWithUnescapedOpenBracket(String s) {
        if (s.isEmpty() || s.charAt(s.length() - 1) != '[') {
            return false;
        }
        int backslashCount = 0;
        for (int i = s.length() - 2; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashCount++;
        }
        return backslashCount % 2 == 0;
    }

    private void addEmptySection(Map<String, Object> content, String sectionLine) {
        String sectionName = unescape(sectionLine.substring(0, sectionLine.length() - 1).trim());
        putValue(content, sectionName, new LinkedHashMap<>());
    }

    private static String repeatBrackets(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * When multiple closing brackets appear on the same line, stores the remaining brackets in
     * the line array at {@code lineIndex} so the caller's parse loop re-processes them, and
     * returns {@code lineIndex - 1}.  The caller increments its index before the next iteration,
     * so returning {@code lineIndex - 1} causes it to land on {@code lineIndex} and re-visit
     * the mutated line.  For a single closing bracket no mutation is needed; {@code lineIndex}
     * is returned unchanged (the call is already a {@code return} statement in the caller).
     */
    private static int returnWithRemainingBrackets(String[] lines, int lineIndex, int closingBrackets) {
        if (closingBrackets > 1) {
            lines[lineIndex] = repeatBrackets(closingBrackets - 1);
            return lineIndex - 1;
        }
        return lineIndex;
    }

    private void putValue(Map<String, Object> content, String key, Object value) {
        if ("entries".equals(key)) {
            Object existing = content.get(key);
            if (existing == null) {
                List<Object> list = new ArrayList<>();
                list.add(value);
                content.put(key, list);
            } else {
                ((List<Object>) existing).add(value);
            }
            return;
        }
        Object existing = content.get(key);
        if (existing == null) {
            content.put(key, value);
        } else if (existing instanceof List) {
            ((List<Object>) existing).add(value);
        } else {
            List<Object> list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            content.put(key, list);
        }
    }

    /**
     * Finds the index of the {@code =} that separates the key from the value in a
     * STANDARD-format key=value line.  Handles bracket-notation keys such as
     * {@code [metric=classloading.totalLoadedClassesCount]=11741}: when {@code temp}
     * starts with {@code [}, it finds the matching closing {@code ]} and returns the
     * index of the {@code =} that immediately follows.  Falls back to
     * {@link #findUnescaped} for plain (non-bracket) keys.
     */
    private int findKeyValueSplitIndex(String temp) {
        if (!temp.isEmpty() && temp.charAt(0) == '[') {
            return findEqAfterBracketKey(temp);
        }
        return findUnescaped(temp, '=');
    }

    /** Finds the {@code =} that follows the bracket-notation key's closing {@code ]}. */
    private static int findEqAfterBracketKey(String temp) {
        int depth = 0;
        for (int i = 0; i < temp.length(); i++) {
            char c = temp.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    boolean hasEqNext = i + 1 < temp.length() && temp.charAt(i + 1) == '=';
                    return hasEqNext ? i + 1 : -1;
                }
            }
        }
        return -1;
    }

    private int findUnescaped(String s, char target) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == target) {
                if (i == 0 || s.charAt(i - 1) != '\\') {
                    return i;
                }
                // It might be double escaped \\=
                int backslashCount = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
                    backslashCount++;
                }
                if (backslashCount % 2 == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Object tryParseNumber(String value) {
        if (value == null || value.equals("null")) {
            return null;
        }
        if (value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        try {
            // Remove commas from numbers like 1,234,567
            String cleanValue = value.replace(",", "");
            if (cleanValue.contains(".")) {
                return Double.parseDouble(cleanValue);
            } else {
                return Long.parseLong(cleanValue);
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private String unescape(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '\\': sb.append('\\'); break;
                    case '[': sb.append('['); break;
                    case ']': sb.append(']'); break;
                    case '=': sb.append('='); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(c); sb.append(next); break;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String toJson(DiagnosticEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"epoch\":").append(entry.epoch != null ? entry.epoch : 0);
        sb.append(",\"name\":\"").append(escapeJson(entry.name)).append("\"");
        sb.append(",\"content\":");
        appendMapToJson(sb, entry.content);
        sb.append("}");
        return sb.toString();
    }

    private void appendMapToJson(StringBuilder sb, Map<String, Object> map) {
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            appendValueToJson(sb, entry.getKey(), entry.getValue());
            first = false;
        }
        sb.append("}");
    }

    /**
     * Serialises a value to JSON. When {@code key} is {@code "entries"} and the
     * value is a list, plain-string items are wrapped as {@code {"text":"..."}} so
     * every element in the array is a JSON object.
     */
    private void appendValueToJson(StringBuilder sb, String key, Object value) {
        if ("entries".equals(key) && value instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) {
                    sb.append(",");
                }
                if (item instanceof String) {
                    sb.append("{\"text\":\"").append(escapeJson((String) item)).append("\"}");
                } else {
                    appendValueToJson(sb, null, item);
                }
                first = false;
            }
            sb.append("]");
        } else if (value instanceof Map) {
            appendMapToJson(sb, (Map<String, Object>) value);
        } else if (value instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) {
                    sb.append(",");
                }
                appendValueToJson(sb, null, item);
                first = false;
            }
            sb.append("]");
        } else if (value instanceof String) {
            sb.append("\"").append(escapeJson((String) value)).append("\"");
        } else if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Parses a JSON string produced by {@link #toJson(DiagnosticEntry)} back into a
     * {@link DiagnosticEntry}.  Integer JSON numbers are always deserialized as {@code Long}
     * so that subsequent {@link #toStandard} output is consistent with the original STANDARD
     * format.
     *
     * @return the parsed entry, or {@code null} if parsing fails
     */
    public DiagnosticEntry parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            DiagnosticEntry entry = new DiagnosticEntry();
            JsonNode timeNode = root.get("time");
            JsonNode epochNode = root.get("epoch");
            if (epochNode != null && !epochNode.isNull()) {
                entry.epoch = epochNode.longValue();
            }
            if (timeNode != null && !timeNode.isNull()) {
                entry.time = timeNode.asText();
            } else if (entry.epoch != null) {
                entry.time = formatEpochAsTime(entry.epoch);
            }
            entry.name = root.get("name").asText();
            entry.content = jsonNodeToMap(root.get("content"));
            return entry;
        } catch (Exception e) {
            LOGGER.severe("Failed to parse JSON diagnostics entry: " + json, e);
            return null;
        }
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            map.put(field.getKey(), jsonNodeToValue(field.getValue()));
        }
        return map;
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isObject()) {
            return jsonNodeToMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(jsonNodeToValue(item)));
            return list;
        }
        return node.asText();
    }

    /**
     * Reconstructs a STANDARD-format diagnostics string from a {@link DiagnosticEntry}.
     * This is the reverse of {@link #parseStandard(String)} and allows round-trip validation.
     * <p>
     * {@code Long} values are written with comma grouping (matching
     * {@link DiagnosticsLogWriterImpl#writeLong}), {@code Double} values use
     * {@link Double#toString}, and {@code String} values are re-escaped using the same
     * rules as {@link DiagnosticsLogWriterImpl}.
     */
    public String toStandard(DiagnosticEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.time).append(' ');
        if (entry.epoch != null) {
            sb.append(entry.epoch).append(' ');
        }
        sb.append(escapeStandard(entry.name)).append('[');
        appendStandardContent(sb, entry.content, 0);
        sb.append(']').append(System.lineSeparator());
        return sb.toString();
    }

    private void appendStandardContent(StringBuilder sb, Map<String, Object> content, int depth) {
        for (Map.Entry<String, Object> e : content.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if ("entries".equals(key) && value instanceof List) {
                for (Object item : (List<Object>) value) {
                    appendStandardIndent(sb, depth);
                    appendStandardEntryItem(sb, item);
                }
            } else if (value instanceof Map) {
                appendStandardIndent(sb, depth);
                sb.append(escapeStandard(key)).append('[');
                appendStandardContent(sb, (Map<String, Object>) value, depth + 1);
                sb.append(']');
            } else if (value instanceof List) {
                // Duplicate key promoted to a list — write each item as a separate line.
                for (Object item : (List<Object>) value) {
                    appendStandardIndent(sb, depth);
                    sb.append(escapeStandard(key)).append('=');
                    appendStandardValue(sb, item);
                }
            } else {
                appendStandardIndent(sb, depth);
                sb.append(escapeStandard(key)).append('=');
                appendStandardValue(sb, value);
            }
        }
    }

    /**
     * Renders a single item from an {@code "entries"} list in STANDARD format.
     * A {@code {"text":"..."}} map is unwrapped to its plain string value.
     * Any other map is rendered as space-separated {@code key=value} pairs.
     * Non-map items are converted to their escaped string representation.
     */
    @SuppressWarnings("unchecked")
    private void appendStandardEntryItem(StringBuilder sb, Object item) {
        if (item instanceof Map) {
            Map<String, Object> itemMap = (Map<String, Object>) item;
            if (itemMap.size() == 1 && itemMap.containsKey("text")) {
                sb.append(escapeStandard(String.valueOf(itemMap.get("text"))));
            } else {
                appendStandardStructuredEntry(sb, itemMap);
            }
        } else {
            sb.append(escapeStandard(String.valueOf(item)));
        }
    }

    private void appendStandardStructuredEntry(StringBuilder sb, Map<String, Object> itemMap) {
        boolean firstPair = true;
        for (Map.Entry<String, Object> pair : itemMap.entrySet()) {
            if (!firstPair) {
                sb.append(' ');
            }
            sb.append(escapeStandard(pair.getKey())).append('=');
            appendStandardValue(sb, pair.getValue());
            firstPair = false;
        }
    }

    private static void appendStandardIndent(StringBuilder sb, int depth) {
        sb.append(System.lineSeparator());
        int spaces = INDENT_BASE + DEPTH_INDENT_SIZE * depth;
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }

    private void appendStandardValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Long) {
            sb.append(String.format(Locale.ROOT, "%,d", (Long) value));
        } else if (value instanceof Double) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(escapeStandard(String.valueOf(value)));
        }
    }

    private String escapeStandard(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '[':  sb.append("\\[");  break;
                case ']':  sb.append("\\]");  break;
                case '=':  sb.append("\\=");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                default:   sb.append(c);      break;
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // CLI entry point
    // -------------------------------------------------------------------------

    /**
     * Converts STANDARD-format Hazelcast diagnostics log files to NDJSON.
     *
     * <p>Usage:
     * <pre>
     *   java DiagnosticsLogConverter --in=&lt;glob&gt; [--out=&lt;dir&gt;]
     * </pre>
     *
     * <ul>
     *   <li>{@code --in}  — glob pattern for input files, e.g. {@code /var/log/hz/*.log}</li>
     *   <li>{@code --out} — output directory (optional; defaults to the input directory)</li>
     * </ul>
     *
     * <p>Each matching input file produces a {@code .json} file in the output directory.
     * The output is NDJSON: one compact JSON object per line, one line per diagnostics entry.
     */
    public static void main(String[] args) throws IOException {
        String[] parsed = parseCliArgs(args);
        if (parsed == null) {
            return;
        }
        String inGlob = parsed[0];
        Path inPath = Paths.get(inGlob);
        Path inDir = inPath.getParent() != null ? inPath.getParent() : Paths.get(".");
        String fileGlob = inPath.getFileName().toString();
        Path outDir = parsed[1] != null ? Paths.get(parsed[1]) : inDir;
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }
        runConversion(inDir, fileGlob, outDir, inGlob);
    }

    /**
     * Parses CLI arguments and returns {@code String[2]}: {@code [0]} = inGlob,
     * {@code [1]} = outDir (may be null).  Returns {@code null} and prints usage on error.
     */
    private static String[] parseCliArgs(String[] args) {
        String inGlob = null;
        String outDir = null;
        final String inPrefix = "--in=";
        final String outPrefix = "--out=";
        for (String arg : args) {
            if (arg.startsWith(inPrefix)) {
                inGlob = arg.substring(inPrefix.length());
            } else if (arg.startsWith(outPrefix)) {
                outDir = arg.substring(outPrefix.length());
            } else {
                System.err.println("Unknown argument: " + arg);
                printUsage();
                return null;
            }
        }
        if (inGlob == null) {
            System.err.println("Missing required argument: --in");
            printUsage();
            return null;
        }
        return new String[]{inGlob, outDir};
    }

    private static void runConversion(Path inDir, String fileGlob,
                                      Path outDir, String inGlob) throws IOException {
        DiagnosticsLogConverter converter = new DiagnosticsLogConverter();
        int filesConverted = 0;
        int totalEntries = 0;
        int totalErrors = 0;
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(inDir, fileGlob)) {
            for (Path inputFile : dirStream) {
                String outName = toJsonOutputName(inputFile.getFileName().toString());
                int[] result = converter.convertFile(inputFile, outDir.resolve(outName));
                totalEntries += result[0];
                totalErrors += result[1];
                System.out.printf("  %s -> %s (%d entries)%n",
                        inputFile.getFileName(), outName, result[0]);
                filesConverted++;
            }
        }
        if (filesConverted == 0) {
            System.out.println("No files matched: " + inGlob);
            return;
        }
        System.out.printf("%nConverted %d file(s), %d entries total", filesConverted, totalEntries);
        if (totalErrors > 0) {
            System.out.printf(", %d parse errors (check logs for details)", totalErrors);
        }
        System.out.println();
    }

    private static void printUsage() {
        System.err.println("Usage: DiagnosticsLogConverter --in=<glob> [--out=<dir>]");
        System.err.println("  --in   glob pattern for STANDARD-format diagnostics files");
        System.err.println("  --out  output directory (default: same as input directory)");
    }

    /**
     * Converts one STANDARD-format diagnostics log file to NDJSON, writing the result
     * to {@code outputFile} (one JSON object per line).
     *
     * @return {@code int[2]}: {@code [0]} = entries converted, {@code [1]} = parse errors
     */
    int[] convertFile(Path inputFile, Path outputFile) throws IOException {
        List<String> entries = splitIntoEntries(inputFile);
        int converted = 0;
        int errors = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            for (String entry : entries) {
                if (entry.isBlank()) {
                    continue;
                }
                DiagnosticEntry parsed = parseStandard(entry);
                if (parsed == null) {
                    errors++;
                    continue;
                }
                writer.write(toJson(parsed));
                writer.newLine();
                converted++;
            }
        }
        return new int[]{converted, errors};
    }

    /**
     * Reads a STANDARD-format diagnostics log file and returns the individual entry blocks.
     * Each block starts with a {@code dd-MM-yyyy HH:mm:ss} header line.
     */
    private static List<String> splitIntoEntries(Path file) throws IOException {
        List<String> entries = new ArrayList<>();
        List<String> current = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (ENTRY_HEADER_PATTERN.matcher(line).matches()) {
                    if (!current.isEmpty()) {
                        entries.add(joinEntryLines(current));
                        current.clear();
                    }
                    current.add(line);
                } else if (!current.isEmpty()) {
                    current.add(line);
                }
            }
        }
        if (!current.isEmpty()) {
            entries.add(joinEntryLines(current));
        }
        return entries;
    }

    private static String joinEntryLines(List<String> lines) {
        int last = lines.size() - 1;
        while (last > 0 && lines.get(last).isBlank()) {
            last--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            sb.append(lines.get(i)).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static String toJsonOutputName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot) + ".jsonl";
        }
        return fileName + ".jsonl";
    }
}
