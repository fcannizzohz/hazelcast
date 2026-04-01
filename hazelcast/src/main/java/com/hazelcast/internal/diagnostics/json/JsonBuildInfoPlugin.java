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

import com.hazelcast.instance.BuildInfo;
import com.hazelcast.instance.BuildInfoProvider;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.properties.HazelcastProperties;

/**
 * JSON diagnostics plugin that emits Hazelcast build information once at startup.
 *
 * <p>Produces a single NDJSON line with {@code "name":"BuildInfo"}.
 * Field names and types match the {@code buildInfoContent} definition in
 * {@code diaglogs.schema.json}.
 *
 * @since 6.0
 */
public class JsonBuildInfoPlugin extends JsonDiagnosticsPlugin {

    private final BuildInfo buildInfo = BuildInfoProvider.getBuildInfo();

    public JsonBuildInfoPlugin(ILogger logger, HazelcastProperties properties) {
        super(logger, properties);
    }

    @Override
    public long getPeriodMillis() {
        return RUN_ONCE_PERIOD_MS;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "BuildInfo");
        writer.writeString("Build", buildInfo.getBuild());
        writer.writeLong("BuildNumber", buildInfo.getBuildNumber());
        writer.writeString("Revision", buildInfo.getRevision());
        BuildInfo upstream = buildInfo.getUpstreamBuildInfo();
        if (upstream != null) {
            writer.writeString("UpstreamRevision", upstream.getRevision());
        }
        writer.writeString("Version", buildInfo.getVersion());
        // SerialVersion is a string per the schema (matches the standard plugin's String.valueOf usage)
        writer.writeString("SerialVersion", String.valueOf(buildInfo.getSerializationVersion()));
        writer.writeBoolean("Enterprise", buildInfo.isEnterprise());
        writer.endEntry();
    }
}
