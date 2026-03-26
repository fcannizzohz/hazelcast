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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class DiagnosticsConfigTest extends HazelcastTestSupport {

    // --- equals() ---

    @Test
    public void equals_sameLogFormat_returnsTrue() {
        DiagnosticsConfig a = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        DiagnosticsConfig b = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        assertEquals(a, b);
    }

    @Test
    public void equals_differentLogFormat_returnsFalse() {
        DiagnosticsConfig a = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.STANDARD);
        DiagnosticsConfig b = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        assertNotEquals("configs with different logFormat must not be equal", a, b);
    }

    // --- hashCode() ---

    @Test
    public void hashCode_sameLogFormat_equal() {
        DiagnosticsConfig a = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        DiagnosticsConfig b = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void hashCode_differentLogFormat_notEqual() {
        DiagnosticsConfig a = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.STANDARD);
        DiagnosticsConfig b = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        assertNotEquals("configs with different logFormat must have different hashCodes", a.hashCode(), b.hashCode());
    }

    // --- toString() ---

    @Test
    public void toString_includesLogFormat() {
        DiagnosticsConfig config = new DiagnosticsConfig().setLogFormat(DiagnosticsLogFormat.JSON);
        assertTrue("toString must include logFormat", config.toString().contains("logFormat=JSON"));
    }

    @Test
    public void toString_defaultLogFormat_includesStandard() {
        DiagnosticsConfig config = new DiagnosticsConfig();
        assertTrue("toString must include default logFormat", config.toString().contains("logFormat=STANDARD"));
    }
}
