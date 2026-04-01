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

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.sort;

/**
 * JSON diagnostics plugin that emits JVM and OS system properties once at startup.
 *
 * <p>Mirrors the filtering logic of the standard
 * {@link com.hazelcast.internal.diagnostics.SystemPropertiesPlugin}:
 * only {@code java.*} (excluding {@code java.awt.*}), {@code hazelcast.*},
 * {@code sun.*}, and {@code os.*} properties are included, plus the JVM
 * input arguments as {@code java.vm.args}.
 *
 * @since 6.0
 */
public class JsonSystemPropertiesPlugin extends JsonDiagnosticsPlugin {

    static final String JVM_ARGS_KEY = "java.vm.args";

    private String inputArgs;

    public JsonSystemPropertiesPlugin(ILogger logger, HazelcastProperties properties) {
        super(logger, properties);
    }

    @Override
    public void onStart() {
        inputArgs = collectInputArgs();
    }

    @Override
    public long getPeriodMillis() {
        return RUN_ONCE_PERIOD_MS;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        List<String> keys = new ArrayList<>();
        for (Object k : System.getProperties().keySet()) {
            keys.add((String) k);
        }
        keys.add(JVM_ARGS_KEY);
        sort(keys);

        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "SystemProperties");
        for (String key : keys) {
            if (isIgnored(key)) {
                continue;
            }
            String value = JVM_ARGS_KEY.equals(key) ? inputArgs : System.getProperty(key);
            writer.writeString(key, value);
        }
        writer.endEntry();
    }

    private static boolean isIgnored(String key) {
        if (key.startsWith("java.awt")) {
            return true;
        }
        return !key.startsWith("java")
                && !key.startsWith("hazelcast")
                && !key.startsWith("sun")
                && !key.startsWith("os");
    }

    private static String collectInputArgs() {
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMxBean.getInputArguments();
        StringBuilder sb = new StringBuilder();
        for (String arg : arguments) {
            sb.append(arg);
            sb.append(' ');
        }
        return sb.toString();
    }
}
