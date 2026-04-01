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

import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.sort;

/**
 * JSON diagnostics plugin that emits Hazelcast configuration properties once at startup.
 *
 * <p>Mirrors {@link com.hazelcast.internal.diagnostics.ConfigPropertiesPlugin}:
 * emits all keys from {@link HazelcastProperties} plus any plugin-override properties
 * from the diagnostics config, sorted alphabetically.
 *
 * @since 6.0
 */
public class JsonConfigPropertiesPlugin extends JsonDiagnosticsPlugin {

    private final Map<String, String> pluginProperties;

    public JsonConfigPropertiesPlugin(ILogger logger, HazelcastProperties properties,
                                      Map<String, String> pluginProperties) {
        super(logger, properties);
        this.pluginProperties = pluginProperties;
    }

    @Override
    public long getPeriodMillis() {
        return RUN_ONCE_PERIOD_MS;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        List<String> keys = new ArrayList<>(properties.keySet());
        sort(keys);

        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "ConfigProperties");
        for (String key : keys) {
            writer.writeString(key, properties.get(key));
        }
        for (Map.Entry<String, String> entry : pluginProperties.entrySet()) {
            writer.writeString(entry.getKey(), entry.getValue());
        }
        writer.endEntry();
    }
}
