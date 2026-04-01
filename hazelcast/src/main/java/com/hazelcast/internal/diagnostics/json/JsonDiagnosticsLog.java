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

import com.hazelcast.internal.diagnostics.Diagnostics;
import com.hazelcast.internal.diagnostics.DiagnosticsLog;
import com.hazelcast.internal.diagnostics.DiagnosticsOutputType;
import com.hazelcast.internal.diagnostics.DiagnosticsPlugin;
import com.hazelcast.logging.ILogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * {@link DiagnosticsLog} implementation for the JSON output format.
 *
 * <p>This class is a self-contained subsystem: it owns its own scheduler,
 * instantiates and manages all {@link JsonDiagnosticsPlugin} instances, and
 * routes output to file, stdout, or the Hazelcast logger — mirroring the
 * {@code outputType} configuration of the standard diagnostics system.
 *
 * <p>When the JSON format is active, {@link #write(DiagnosticsPlugin)} is a
 * no-op: existing {@link DiagnosticsPlugin} instances are <em>not</em> scheduled
 * by the main {@link Diagnostics} scheduler (see the guard in
 * {@link Diagnostics#register(DiagnosticsPlugin)}), so nothing calls this method
 * for normal plugins.  StoreLatencyPlugin subclasses ARE registered and their
 * {@code write()} is handled specially.
 *
 * <p><b>File rolling:</b> when {@link DiagnosticsOutputType#FILE} is configured,
 * this class manages its own rolling log files with the {@code .jsonl} extension.
 * The rolling logic mirrors {@link com.hazelcast.internal.diagnostics.DiagnosticsLogFile}
 * but is independent to avoid coupling to the STANDARD output path.
 *
 * <p><b>Thread safety:</b> the scheduler is single-threaded.  All plugin runs and
 * file-rolling operations are serialised on that thread.
 *
 * @since 6.0
 */
public class JsonDiagnosticsLog implements DiagnosticsLog {

    private static final String FILE_EXT = ".jsonl";

    private final Diagnostics diagnostics;
    private final ILogger logger;
    private final DiagnosticsOutputType outputType;

    // plugins registered with this log; populated via registerPlugin()
    private final List<JsonDiagnosticsPlugin> plugins = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    private ScheduledExecutorService scheduler;

    // Output state (used for FILE mode)
    private PrintWriter printWriter;
    private File currentFile;
    private long currentFileIndex;
    private final JsonEntryWriter entryWriter;

    public JsonDiagnosticsLog(Diagnostics diagnostics) {
        this.diagnostics = diagnostics;
        this.logger = diagnostics.logger;
        this.outputType = diagnostics.outputType != null
                ? diagnostics.outputType
                : DiagnosticsOutputType.FILE;

        // Initialise writer with a no-op sink; replaced in start()
        this.entryWriter = new JsonEntryWriter(new PrintWriter(System.out, false));
    }

    /**
     * Registers a JSON plugin. Must be called before {@link #start()}.
     *
     * @param plugin plugin to register; must not be null
     */
    public void registerPlugin(JsonDiagnosticsPlugin plugin) {
        plugins.add(plugin);
    }

    /**
     * Starts the scheduler and calls {@link JsonDiagnosticsPlugin#onStart()} on all plugins.
     * Called automatically from the constructor to align with
     * {@link Diagnostics#start()} lifecycle.
     */
    public void start() {
        try {
            openOutput();
        } catch (IOException e) {
            logger.warning("JsonDiagnosticsLog: failed to open output", e);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(new JsonSchedulerThreadFactory());

        for (JsonDiagnosticsPlugin plugin : plugins) {
            long period = plugin.getPeriodMillis();
            if (period == JsonDiagnosticsPlugin.DISABLED_PERIOD_MS) {
                continue;
            }
            try {
                plugin.onStart();
            } catch (Throwable t) {
                logger.warning("JsonDiagnosticsPlugin failed to start: " + plugin.getClass(), t);
                continue;
            }
            if (period == JsonDiagnosticsPlugin.RUN_ONCE_PERIOD_MS) {
                // run-once: schedule with zero initial delay, fire exactly once
                futures.add(scheduler.schedule(new RunPluginTask(plugin), 0, MILLISECONDS));
            } else {
                futures.add(scheduler.scheduleAtFixedRate(
                        new RunPluginTask(plugin), 0, period, MILLISECONDS));
            }
        }
    }

    @Override
    public void write(DiagnosticsPlugin plugin) {
        // No-op for standard plugins (they are not scheduled in JSON mode).
        // If this is ever called (e.g. StoreLatencyPlugin subclass via the standard
        // scheduler), we ignore it — the JSON subclass manages its own scheduling.
    }

    @Override
    public void close() {
        // cancel all futures
        for (ScheduledFuture<?> future : futures) {
            future.cancel(false);
        }
        futures.clear();

        // shutdown plugins
        for (JsonDiagnosticsPlugin plugin : plugins) {
            try {
                plugin.onShutdown();
            } catch (Throwable t) {
                logger.warning("JsonDiagnosticsPlugin failed to shutdown: " + plugin.getClass(), t);
            }
        }

        // shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        // flush and close output
        closeOutput();
    }

    // ------------------------------------------------------------------ output management

    private void openOutput() throws IOException {
        switch (outputType) {
            case FILE -> openFileOutput();
            case STDOUT -> {
                PrintWriter pw = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
                entryWriter.init(pw);
                this.printWriter = pw;
            }
            case LOGGER -> {
                // Logger output is written per-entry; printWriter is a buffer reset each flush.
                // Handled in RunPluginTask when outputType == LOGGER.
                PrintWriter pw = new PrintWriter(new java.io.StringWriter());
                entryWriter.init(pw);
                this.printWriter = pw;
            }
        }
    }

    private void openFileOutput() throws IOException {
        File dir = diagnostics.loggingDirectory;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("JsonDiagnosticsLog: cannot create log directory: " + dir);
        }
        currentFileIndex = 0;
        currentFile = newFile(currentFileIndex);
        printWriter = newPrintWriter(currentFile);
        entryWriter.init(printWriter);
        logger.info("JsonDiagnosticsLog: writing to " + currentFile.getAbsolutePath());
    }

    private File newFile(long index) {
        String name = diagnostics.baseFileNameWithTime + String.format("%03d", index) + FILE_EXT;
        return new File(diagnostics.loggingDirectory, name);
    }

    private PrintWriter newPrintWriter(File file) throws IOException {
        return new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)));
    }

    /**
     * Checks whether the current file should be rolled and, if so, rolls it.
     * Called from the scheduler thread before each plugin run.
     */
    private void maybeRoll() {
        if (outputType != DiagnosticsOutputType.FILE || currentFile == null) {
            return;
        }
        long maxBytes = (long) (diagnostics.maxRollingFileSizeMB * 1024 * 1024);
        if (currentFile.length() < maxBytes) {
            return;
        }

        // close current file
        printWriter.flush();
        printWriter.close();

        // advance index
        currentFileIndex++;
        int maxFiles = diagnostics.maxRollingFileCount;
        if (currentFileIndex >= maxFiles) {
            currentFileIndex = 0;
        }
        try {
            currentFile = newFile(currentFileIndex);
            printWriter = newPrintWriter(currentFile);
            entryWriter.init(printWriter);
            logger.fine("JsonDiagnosticsLog: rolled to " + currentFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warning("JsonDiagnosticsLog: failed to roll file", e);
        }
    }

    private void closeOutput() {
        if (printWriter != null) {
            printWriter.flush();
            printWriter.close();
            printWriter = null;
        }
    }

    // ------------------------------------------------------------------ scheduler task

    private final class RunPluginTask implements Runnable {
        private final JsonDiagnosticsPlugin plugin;

        RunPluginTask(JsonDiagnosticsPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void run() {
            try {
                maybeRoll();
                plugin.run(entryWriter);
                if (printWriter != null) {
                    printWriter.flush();
                }
            } catch (Throwable t) {
                logger.warning("JsonDiagnosticsPlugin run() failed: " + plugin.getClass(), t);
            }
        }
    }

    // ------------------------------------------------------------------ thread factory

    private static final class JsonSchedulerThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hz.diagnostics.json.scheduler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    }
}
