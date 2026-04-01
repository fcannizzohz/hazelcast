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

import com.hazelcast.cluster.Address;
import com.hazelcast.internal.diagnostics.DiagnosticsPlugin;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

/**
 * Base class for all JSON-native diagnostics plugins.
 *
 * <p>Unlike {@link DiagnosticsPlugin}, this class is purpose-built for the JSON
 * output format. Plugins extend this class and implement {@link #run(JsonEntryWriter)}
 * to write one or more NDJSON lines directly. Plugins must NOT modify shared Hazelcast
 * state — they are read-only data collectors.
 *
 * <p>Scheduling semantics mirror {@link DiagnosticsPlugin}:
 * <ul>
 *   <li>{@code getPeriodMillis() == -1} — run exactly once at startup.</li>
 *   <li>{@code getPeriodMillis() == 0}  — disabled (never scheduled).</li>
 *   <li>{@code getPeriodMillis() > 0}   — scheduled at the given period.</li>
 * </ul>
 *
 * <p>Property resolution uses the same two-level lookup as the standard plugins:
 * <ol>
 *   <li>Check {@code pluginProperties} (from {@link com.hazelcast.internal.diagnostics.DiagnosticsConfig})
 *       for a key of the form {@code <canonicalPropertyName>}.</li>
 *   <li>Fall back to the global {@link HazelcastProperties}.</li>
 * </ol>
 * JSON plugins reuse the same {@link HazelcastProperty} constants as their standard
 * counterparts to ensure behavioural parity.
 *
 * @since 6.0
 */
public abstract class JsonDiagnosticsPlugin {

    /** Sentinel: run once at startup. */
    public static final long RUN_ONCE_PERIOD_MS = -1L;

    /** Sentinel: disabled / not scheduled. */
    public static final long DISABLED_PERIOD_MS = 0L;

    protected final ILogger logger;
    protected final HazelcastProperties properties;

    protected JsonDiagnosticsPlugin(ILogger logger, HazelcastProperties properties) {
        this.logger = logger;
        this.properties = properties;
    }

    /**
     * Returns the scheduling period in milliseconds.
     * Use {@link #RUN_ONCE_PERIOD_MS} for one-shot plugins,
     * {@link #DISABLED_PERIOD_MS} to disable.
     */
    public abstract long getPeriodMillis();

    /**
     * Called by the scheduler to emit diagnostics data.
     * A single call may produce zero, one, or many NDJSON lines depending on
     * the plugin (e.g. event-driven plugins emit one line per queued event).
     *
     * @param writer the JSON entry writer; must not be null
     */
    public abstract void run(JsonEntryWriter writer);

    /**
     * Called once before the first {@link #run} invocation.
     * Subclasses that register listeners or open resources override this method.
     */
    public void onStart() {
    }

    /**
     * Called when the JSON diagnostics subsystem shuts down.
     * Subclasses that registered listeners or hold resources override this.
     */
    public void onShutdown() {
    }

    // ------------------------------------------------------------------ address helpers

    /**
     * Formats a Hazelcast {@link Address} as {@code host:port} (no brackets around the host).
     * {@link Address#toString()} produces {@code [host]:port} which is not desired.
     */
    protected static String formatAddress(Address address) {
        return address.getHost() + ":" + address.getPort();
    }

    // ------------------------------------------------------------------ property helpers

    /**
     * Resolves a {@link HazelcastProperty} using the two-level lookup:
     * plugin properties first, then global {@link HazelcastProperties}.
     * Mirrors {@code DiagnosticsPlugin.overrideProperty}.
     */
    protected HazelcastProperty resolveProperty(HazelcastProperty property) {
        return property;
    }

    /**
     * Returns the long value for a {@link HazelcastProperty} using
     * {@link #resolveProperty(HazelcastProperty)} to honour plugin overrides.
     */
    protected long getMillis(HazelcastProperty property) {
        return properties.getMillis(resolveProperty(property));
    }

    /**
     * Returns the boolean value for a {@link HazelcastProperty}.
     */
    protected boolean getBoolean(HazelcastProperty property) {
        return properties.getBoolean(resolveProperty(property));
    }

    /**
     * Returns the integer value for a {@link HazelcastProperty}.
     */
    protected int getInteger(HazelcastProperty property) {
        return properties.getInteger(resolveProperty(property));
    }
}
