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
package org.thingsboard.tools.service.shared.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.tools.service.gateway.EphemeralSchedule;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mode-agnostic paced onboarding: schedules each entity's first onboard over a jittered window and
 * runs at most {@code maxConcurrentOnboards} onboards at once, signalling ramp-complete when every
 * entity has reached a terminal state (onboarded or failed). Reuses the ephemeral engine's
 * tryAcquire+reschedule pacing, but drives a persistent (synchronous) onboard instead of a churn cycle.
 */
@Slf4j
public class StaggeredOnboardingEngine {

    public interface RampCompleteCallback {
        void onRampComplete(int onboarded, int failed);
    }

    private final EntityLifecycle lifecycle;
    private final int maxConcurrentOnboards;
    private final long firstJitterMillis;
    private final Random rng;

    private final ScheduledExecutorService timer;
    private final ExecutorService workers;
    private final Semaphore permits;

    private final AtomicInteger onboarded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger terminal = new AtomicInteger();
    private volatile boolean running;
    private volatile RampCompleteCallback onComplete;
    // Periodic onboarding-progress line (replaces per-entity subscribe/announce log spam under STAGGERED);
    // cancelled at ramp-complete so the final "Ramp complete: ..." line closes it out.
    private volatile ScheduledFuture<?> progressTask;

    private static final long PROGRESS_LOG_INTERVAL_SEC = 10L;

    public StaggeredOnboardingEngine(EntityLifecycle lifecycle, int maxConcurrentOnboards,
                                     int firstJitterSec, int schedulerThreads, long seed) {
        this.lifecycle = lifecycle;
        this.maxConcurrentOnboards = Math.max(1, maxConcurrentOnboards);
        this.firstJitterMillis = Math.max(0, firstJitterSec) * 1000L;
        this.rng = new Random(EphemeralSchedule.scheduleSeed(seed, 0));
        this.timer = Executors.newScheduledThreadPool(Math.max(1, schedulerThreads));
        this.workers = Executors.newFixedThreadPool(this.maxConcurrentOnboards);
        this.permits = new Semaphore(this.maxConcurrentOnboards);
    }

    public void start(RampCompleteCallback cb) {
        this.onComplete = cb;
        this.running = true;
        int count = lifecycle.entityCount();
        log.info("Staggered onboarding starting: {} entities, maxConcurrent={}, firstJitter={}ms",
                count, maxConcurrentOnboards, firstJitterMillis);
        if (count <= 0) {
            fireComplete();
            return;
        }
        for (int i = 0; i < count; i++) {
            final int idx = i;
            long offset = EphemeralSchedule.firstOffsetMillis(rng, firstJitterMillis);
            timer.schedule(() -> onboardOne(idx), offset, TimeUnit.MILLISECONDS);
        }
        this.progressTask = timer.scheduleAtFixedRate(this::logProgress,
                PROGRESS_LOG_INTERVAL_SEC, PROGRESS_LOG_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void logProgress() {
        log.info("STAGGERED onboarding progress: {} / {} onboarded, {} in-flight, {} failed",
                String.format(java.util.Locale.US, "%,d", onboarded.get()),
                String.format(java.util.Locale.US, "%,d", lifecycle.entityCount()),
                inFlightCount(), failed.get());
    }

    private void onboardOne(int idx) {
        if (!running) {
            return;
        }
        if (!permits.tryAcquire()) {
            // no free slot: reschedule on the timer (never block a timer thread)
            timer.schedule(() -> onboardOne(idx), 1 + rng.nextInt(50), TimeUnit.MILLISECONDS);
            return;
        }
        workers.submit(() -> {
            try {
                lifecycle.onboard(idx);
                onboarded.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                log.warn("Onboard failed for entity {}: {}", idx, e.toString());
            } finally {
                permits.release();
                if (terminal.incrementAndGet() == lifecycle.entityCount()) {
                    fireComplete();
                }
            }
        });
    }

    private void fireComplete() {
        ScheduledFuture<?> pt = this.progressTask;
        if (pt != null) {
            pt.cancel(false);
        }
        log.info("Ramp complete: {} onboarded, {} failed", onboarded.get(), failed.get());
        RampCompleteCallback cb = this.onComplete;
        if (cb != null) {
            cb.onRampComplete(onboarded.get(), failed.get());
        }
    }

    public void stop() {
        running = false;
        timer.shutdownNow();
        workers.shutdownNow();
    }

    public int onboardedCount() { return onboarded.get(); }
    public int failedCount() { return failed.get(); }
    public int inFlightCount() { return maxConcurrentOnboards - permits.availablePermits(); }
}
