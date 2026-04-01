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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * Test utility that validates diagnostics JSON lines against
 * {@code diaglogs.schema.json} (JSON Schema draft 2020-12).
 *
 * <p>Acquire a singleton via {@link #get()} and call {@link #assertValid(String)}
 * from every JSON plugin test to enforce schema compliance.
 *
 * <p>The schema is loaded once from the classpath when the singleton is first
 * accessed; subsequent calls reuse the compiled schema for efficiency.
 */
public final class DiagnosticsSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/diaglogs.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile DiagnosticsSchemaValidator instance;

    private final JsonSchema schema;

    private DiagnosticsSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream is = DiagnosticsSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Schema not found on classpath: " + SCHEMA_RESOURCE
                        + ". Copy diaglogs.schema.json to src/test/resources/");
            }
            this.schema = factory.getSchema(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load diagnostics JSON schema", e);
        }
    }

    /**
     * Returns the singleton validator, loading the schema on first call.
     */
    public static DiagnosticsSchemaValidator get() {
        if (instance == null) {
            synchronized (DiagnosticsSchemaValidator.class) {
                if (instance == null) {
                    instance = new DiagnosticsSchemaValidator();
                }
            }
        }
        return instance;
    }

    /**
     * Asserts that {@code jsonLine} is valid according to the diagnostics schema.
     * Fails the JUnit test with a descriptive message listing all violations if
     * the line does not validate.
     *
     * @param jsonLine a single NDJSON line produced by a JSON diagnostics plugin
     */
    public void assertValid(String jsonLine) {
        Set<ValidationMessage> errors = validate(jsonLine);
        assertTrue("JSON schema validation failed for line:\n  " + jsonLine
                + "\nViolations:\n  " + formatErrors(errors), errors.isEmpty());
    }

    /**
     * Validates {@code jsonLine} and returns the (possibly empty) set of violations.
     * Does not throw; callers that need assertion semantics should use {@link #assertValid}.
     */
    public Set<ValidationMessage> validate(String jsonLine) {
        try {
            JsonNode node = MAPPER.readTree(jsonLine);
            return schema.validate(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not valid JSON: " + jsonLine, e);
        }
    }

    private static String formatErrors(Set<ValidationMessage> errors) {
        StringBuilder sb = new StringBuilder();
        for (ValidationMessage msg : errors) {
            sb.append("  - ").append(msg).append('\n');
        }
        return sb.toString();
    }
}
