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

/**
 * Implemented by any object that wants to be periodically scheduled by
 * {@link JsonDiagnosticsLog} to emit JSON diagnostics output.
 *
 * <p>The primary use case is classes that must extend an existing
 * {@link com.hazelcast.internal.diagnostics.DiagnosticsPlugin} (and therefore
 * cannot extend {@link JsonDiagnosticsPlugin}), but also need to produce
 * NDJSON output on the JSON scheduler thread.  The canonical example is
 * {@link JsonStoreLatencyPlugin}, which extends
 * {@link com.hazelcast.internal.diagnostics.StoreLatencyPlugin} so that store
 * wrappers can locate it via
 * {@link com.hazelcast.internal.diagnostics.Diagnostics#getPlugin(Class)},
 * while also implementing this interface so that
 * {@link JsonDiagnosticsLog#start()} discovers and schedules it generically.
 *
 * <p>Implementors must be registered in the standard
 * {@link com.hazelcast.internal.diagnostics.Diagnostics} plugin registry (via
 * {@link com.hazelcast.internal.diagnostics.Diagnostics#register(
 * com.hazelcast.internal.diagnostics.DiagnosticsPlugin)}) <em>before</em>
 * {@link JsonDiagnosticsLog#start()} is called.
 *
 * @since 6.0
 */
public interface JsonSchedulable {

    /**
     * Returns the scheduling period in milliseconds.
     * {@code 0} means disabled (never scheduled).
     * Negative values have unspecified behaviour and should not be returned.
     */
    long getPeriodMillis();

    /**
     * Called by the {@link JsonDiagnosticsLog} scheduler thread to emit
     * one or more NDJSON lines.
     *
     * @param writer the JSON entry writer; must not be null
     */
    void runJson(JsonEntryWriter writer);
}
