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
public class DiagnosticsLogWriterFactoryTest extends HazelcastTestSupport {

    @Test
    public void create_withJsonFormat_returnsJsonWriterWithJsonFormat() {
        DiagnosticsLogWriter writer = DiagnosticsLogWriterFactory.create(DiagnosticsLogFormat.JSON, false, null);

        assertTrue("Expected DiagnosticsLogWriterJsonImpl", writer instanceof DiagnosticsLogWriterJsonImpl);
        assertEquals(DiagnosticsLogFormat.JSON, writer.getFormat());
    }

    @Test
    public void create_withStandardFormat_returnsStandardWriterWithStandardFormat() {
        DiagnosticsLogWriter writer = DiagnosticsLogWriterFactory.create(DiagnosticsLogFormat.STANDARD, false, null);

        assertTrue("Expected DiagnosticsLogWriterImpl", writer instanceof DiagnosticsLogWriterImpl);
        assertEquals(DiagnosticsLogFormat.STANDARD, writer.getFormat());
    }

    @Test
    public void create_withJsonFormatAndEpochTrue_epochPresentInOutput() {
        DiagnosticsLogWriter writer = DiagnosticsLogWriterFactory.create(DiagnosticsLogFormat.JSON, true, null);
        CharArrayWriter out = new CharArrayWriter();
        writer.init(new PrintWriter(out));
        writer.startSection("Test", 12345L);
        writer.endSection();

        assertTrue("epoch field expected when includeEpochTime=true", out.toString().contains("\"epoch\":12345"));
    }

    @Test
    public void create_withJsonFormatAndEpochFalse_epochAbsentFromOutput() {
        DiagnosticsLogWriter writer = DiagnosticsLogWriterFactory.create(DiagnosticsLogFormat.JSON, false, null);
        CharArrayWriter out = new CharArrayWriter();
        writer.init(new PrintWriter(out));
        writer.startSection("Test", 12345L);
        writer.endSection();

        assertFalse("epoch field must be absent when includeEpochTime=false", out.toString().contains("\"epoch\""));
    }
}
