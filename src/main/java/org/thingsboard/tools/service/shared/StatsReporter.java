/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.shared;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single periodic stats reporter. Each active mode registers only the {@link StatsBlock}s relevant to
 * it; the reporter logs every registered block once per interval, always in {@link StatsBlock} enum
 * order, on the shared log scheduler (never the test metronome). Enabled/interval come from
 * {@code stats.log.*}. One failing source never stops the others or the schedule.
 */
@Slf4j
public class StatsReporter {

    private final ScheduledExecutorService scheduler;
    private final boolean enabled;
    private final int intervalSec;
    private final Map<StatsBlock, StatsSource> sources = new EnumMap<>(StatsBlock.class);
    private volatile ScheduledFuture<?> future;

    public StatsReporter(ScheduledExecutorService scheduler, boolean enabled, int intervalSec) {
        this.scheduler = scheduler;
        this.enabled = enabled;
        this.intervalSec = intervalSec;
    }

    public synchronized void register(StatsBlock block, StatsSource source) {
        sources.put(block, source);
    }

    public synchronized void start() {
        if (future != null) {
            return;
        }
        if (!enabled) {
            log.info("Stats logging disabled (stats.log.enabled=false)");
            return;
        }
        if (intervalSec <= 0) {
            log.info("Stats logging disabled (stats.log.intervalSec <= 0)");
            return;
        }
        if (sources.isEmpty()) {
            log.info("Stats logging: no active sources for this run");
            return;
        }
        future = scheduler.scheduleAtFixedRate(this::report, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    /** Emit one report immediately (used for a final flush at shutdown). */
    public void reportOnce() {
        report();
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(true);
            future = null;
        }
    }

    private void report() {
        collect().forEach(log::info);
    }

    /** Render each registered block in enum order; a source that throws is skipped for this tick. */
    List<String> collect() {
        List<String> lines = new ArrayList<>();
        for (StatsBlock block : StatsBlock.values()) {
            StatsSource source = sources.get(block);
            if (source == null) {
                continue;
            }
            try {
                lines.add(source.reportAndReset(intervalSec));
            } catch (Exception e) {
                log.warn("Failed to render {} stats block", block, e);
            }
        }
        return lines;
    }
}
