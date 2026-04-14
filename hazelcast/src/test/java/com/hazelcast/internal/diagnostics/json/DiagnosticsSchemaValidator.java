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

/**
 * Test utility that validates diagnostics JSON lines against
 * {@code diaglogs.schema.json} (JSON Schema draft 2020-12).
 *
 * <p>Acquire a singleton via {@link #get()} and call {@link #validate(String)}
 * to obtain the set of schema violations. An empty set means the line is valid.
 *
 * <pre>{@code
 * Set<ValidationMessage> errors = DiagnosticsSchemaValidator.get().validate(line);
 * assertTrue("Schema violations: " + errors, errors.isEmpty());
 * }</pre>
 *
 * <p>The schema is loaded once from the classpath on first access; subsequent
 * calls reuse the compiled schema for efficiency.
 */
public final class DiagnosticsSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/com/hazelcast/internal/diagnostics/json/diaglogs.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile DiagnosticsSchemaValidator instance;

    private final JsonSchema schema;

    private DiagnosticsSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream is = DiagnosticsSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Schema not found on classpath: " + SCHEMA_RESOURCE);
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
     * Validates {@code jsonLine} against the diagnostics schema and returns
     * the set of violations. An empty set means the line is valid.
     *
     * @param jsonLine a single NDJSON line produced by a JSON diagnostics plugin
     * @return set of validation messages; empty if the line is schema-compliant
     * @throws IllegalArgumentException if {@code jsonLine} is not valid JSON
     */
    public Set<ValidationMessage> validate(String jsonLine) {
        try {
            JsonNode node = MAPPER.readTree(jsonLine);
            return schema.validate(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not valid JSON: " + jsonLine, e);
        }
    }
}
