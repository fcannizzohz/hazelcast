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

import com.hazelcast.cache.impl.CacheEventData;
import com.hazelcast.cache.impl.CacheEventSet;
import com.hazelcast.collection.impl.collection.CollectionEvent;
import com.hazelcast.collection.impl.list.ListService;
import com.hazelcast.collection.impl.queue.QueueEvent;
import com.hazelcast.collection.impl.set.SetService;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.internal.util.ItemCounter;
import com.hazelcast.internal.util.executor.StripedExecutor;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.impl.event.EntryEventData;
import com.hazelcast.spi.impl.eventservice.impl.LocalEventDispatcher;
import com.hazelcast.spi.properties.HazelcastProperties;
import com.hazelcast.spi.properties.HazelcastProperty;

import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * JSON diagnostics plugin that samples the event queues and emits occupancy data.
 *
 * <p>Uses the same properties as the standard
 * {@link com.hazelcast.internal.diagnostics.EventQueuePlugin}.
 *
 * @since 6.0
 */
public class JsonEventQueuePlugin extends JsonDiagnosticsPlugin {

    /** Period in seconds; 0 = disabled. Mirrors {@code EventQueuePlugin.PERIOD_SECONDS}. */
    public static final HazelcastProperty PERIOD_SECONDS
            = new HazelcastProperty("hazelcast.diagnostics.event.queue.period.seconds", 0, SECONDS);

    /** Minimum queue depth before sampling is triggered. */
    public static final HazelcastProperty THRESHOLD
            = new HazelcastProperty("hazelcast.diagnostics.event.queue.threshold", 1000);

    /** Number of samples to take per run. */
    public static final HazelcastProperty SAMPLES
            = new HazelcastProperty("hazelcast.diagnostics.event.queue.samples", 100);

    private static final float PERCENT_DIVISOR = 100.0f;
    private static final double HUNDRED = 100.0;

    private final StripedExecutor eventExecutor;
    private final ItemCounter<String> occurrenceMap = new ItemCounter<>();
    private final Random random = new Random();
    private final long periodMillis;
    private final int threshold;
    private final int samples;

    public JsonEventQueuePlugin(ILogger logger, HazelcastProperties properties,
                                StripedExecutor eventExecutor) {
        super(logger, properties);
        this.eventExecutor = eventExecutor;
        this.periodMillis = getMillis(PERIOD_SECONDS);
        this.threshold = getInteger(THRESHOLD);
        this.samples = getInteger(SAMPLES);
    }

    @Override
    public long getPeriodMillis() {
        return periodMillis;
    }

    @Override
    public void run(JsonEntryWriter writer) {
        long epoch = System.currentTimeMillis();
        writer.startEntry(epoch, "EventQueues");
        int index = 1;
        for (BlockingQueue<Runnable> queue : eventExecutor.getTaskQueues()) {
            renderWorker(writer, queue, index++);
        }
        writer.endEntry();
    }

    private void renderWorker(JsonEntryWriter writer, BlockingQueue<Runnable> queue, int index) {
        occurrenceMap.reset();
        ArrayList<Runnable> events = new ArrayList<>(queue);
        int eventCount = events.size();
        if (eventCount < threshold) {
            return;
        }

        int sampleCount = min(samples, eventCount);
        int actualSampleCount = 0;
        while (actualSampleCount < sampleCount) {
            actualSampleCount += sampleRunnable(events.get(random.nextInt(eventCount)));
        }
        if (actualSampleCount == 0) {
            return;
        }

        String workerKey = "worker=" + index;
        writer.startObject(workerKey);
        writer.writeLong("eventCount", eventCount);
        writer.writeLong("sampleCount", actualSampleCount);
        writer.startObject("samples");
        writer.startArray("entries");
        for (String key : occurrenceMap.keySet()) {
            long count = occurrenceMap.get(key);
            if (count == 0) {
                continue;
            }
            double percentage = HUNDRED * count / actualSampleCount;
            writer.startArrayItem();
            writeEventKey(writer, key);
            writer.writeLong("sampleCount", count);
            writer.writeDouble("percentage_pc", percentage);
            writer.endArrayItem();
        }
        writer.endArray();
        writer.endObject();
        writer.endObject();
    }

    private static void writeEventKey(JsonEntryWriter writer, String key) {
        // Structured events use the format "serviceType\0dataStructureName\0eventType";
        // fallback entries (unknown runnable/event class names) are plain strings.
        String[] parts = key.split("\0", -1);
        if (parts.length == 3) {
            writer.writeString("serviceType", parts[0]);
            writer.writeString("dataStructureName", parts[1]);
            writer.writeString("eventType", parts[2]);
        } else {
            writer.writeString("eventType", key);
        }
    }

    private int sampleRunnable(Runnable runnable) {
        if (runnable instanceof LocalEventDispatcher eventDispatcher) {
            return sampleLocalDispatcher(eventDispatcher);
        }
        occurrenceMap.add(runnable.getClass().getName(), 1);
        return 1;
    }

    private int sampleLocalDispatcher(LocalEventDispatcher dispatcher) {
        Object event = dispatcher.getEvent();
        if (event instanceof EntryEventData entryEventData) {
            EntryEventType type = EntryEventType.getByType(entryEventData.getEventType());
            occurrenceMap.add("IMap\0" + entryEventData.getMapName() + "\0" + type, 1);
            return 1;
        } else if (event instanceof CacheEventSet cacheEventSet) {
            Set<CacheEventData> cacheEvents = cacheEventSet.getEvents();
            for (CacheEventData ced : cacheEvents) {
                occurrenceMap.add("ICache\0" + ced.getName() + "\0" + ced.getCacheEventType(), 1);
            }
            return cacheEvents.size();
        } else if (event instanceof QueueEvent queueEvent) {
            occurrenceMap.add("IQueue\0" + queueEvent.getName() + "\0" + queueEvent.getEventType(), 1);
            return 1;
        } else if (event instanceof CollectionEvent collectionEvent) {
            String serviceName = dispatcher.getServiceName();
            if (SetService.SERVICE_NAME.equals(serviceName)) {
                serviceName = "ISet";
            } else if (ListService.SERVICE_NAME.equals(serviceName)) {
                serviceName = "IList";
            }
            occurrenceMap.add(serviceName + "\0" + collectionEvent.getName() + "\0" + collectionEvent.getEventType(), 1);
            return 1;
        }
        occurrenceMap.add(event.getClass().getSimpleName(), 1);
        return 1;
    }
}
