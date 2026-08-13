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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StaggeredOnboardingEngineTest {

    /** Stub lifecycle: records each onboard, tracks peak concurrency, can fail chosen indices. */
    static final class StubLifecycle implements EntityLifecycle {
        final int count;
        final int failEvery; // 0 = never fail
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();
        final AtomicInteger onboardCalls = new AtomicInteger();
        final CountDownLatch allTerminal;

        StubLifecycle(int count, int failEvery) {
            this.count = count;
            this.failEvery = failEvery;
            this.allTerminal = new CountDownLatch(count);
        }

        @Override public int entityCount() { return count; }

        @Override public void onboard(int idx) throws Exception {
            int now = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(now, Math::max);
            onboardCalls.incrementAndGet();
            try {
                Thread.sleep(5); // simulate work so concurrency is observable
                if (failEvery > 0 && idx % failEvery == 0) {
                    throw new RuntimeException("stub onboard failure for " + idx);
                }
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    @Test
    void onboardsEveryEntityExactlyOnceAndNeverExceedsConcurrencyCap() throws Exception {
        StubLifecycle stub = new StubLifecycle(200, 0);
        AtomicInteger rampOnboarded = new AtomicInteger(-1);
        AtomicInteger rampFailed = new AtomicInteger(-1);
        CountDownLatch complete = new CountDownLatch(1);

        StaggeredOnboardingEngine engine =
                new StaggeredOnboardingEngine(stub, 10, 0, 2, 42L);
        engine.start((onboarded, failed) -> {
            rampOnboarded.set(onboarded);
            rampFailed.set(failed);
            complete.countDown();
        });

        assertThat(complete.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stub.onboardCalls.get()).isEqualTo(200);
        assertThat(stub.peakInFlight.get()).isLessThanOrEqualTo(10);
        assertThat(rampOnboarded.get()).isEqualTo(200);
        assertThat(rampFailed.get()).isEqualTo(0);
        engine.stop();
    }

    @Test
    void countsFailuresAsTerminalSoRampStillCompletes() throws Exception {
        StubLifecycle stub = new StubLifecycle(50, 10); // idx 0,10,20,30,40 fail -> 5 failures
        CountDownLatch complete = new CountDownLatch(1);
        AtomicInteger rampOnboarded = new AtomicInteger();
        AtomicInteger rampFailed = new AtomicInteger();

        StaggeredOnboardingEngine engine =
                new StaggeredOnboardingEngine(stub, 8, 0, 2, 1L);
        engine.start((onboarded, failed) -> {
            rampOnboarded.set(onboarded);
            rampFailed.set(failed);
            complete.countDown();
        });

        assertThat(complete.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(rampOnboarded.get()).isEqualTo(45);
        assertThat(rampFailed.get()).isEqualTo(5);
        assertThat(engine.onboardedCount()).isEqualTo(45);
        assertThat(engine.failedCount()).isEqualTo(5);
        engine.stop();
    }

    @Test
    void firesRampCompleteImmediatelyForZeroEntities() throws Exception {
        StubLifecycle stub = new StubLifecycle(0, 0);
        CountDownLatch complete = new CountDownLatch(1);
        StaggeredOnboardingEngine engine =
                new StaggeredOnboardingEngine(stub, 4, 0, 1, 0L);
        engine.start((onboarded, failed) -> complete.countDown());
        assertThat(complete.await(2, TimeUnit.SECONDS)).isTrue();
        engine.stop();
    }

    /**
     * Guards the ramp-complete-fires-exactly-once contract with a counter, not a {@code
     * CountDownLatch(1)}: a latch's {@code countDown()} is a silent no-op once it's already at zero, so a
     * test that only awaits the latch would pass even if the callback fired twice. An {@link
     * AtomicInteger}, checked after giving a hypothetical duplicate a grace window to land, actually
     * catches a double-fire.
     */
    @Test
    void rampCompleteFiresExactlyOnce() throws Exception {
        StubLifecycle stub = new StubLifecycle(50, 0);
        AtomicInteger completeCalls = new AtomicInteger();
        CountDownLatch firstComplete = new CountDownLatch(1);

        StaggeredOnboardingEngine engine =
                new StaggeredOnboardingEngine(stub, 8, 0, 2, 7L);
        engine.start((onboarded, failed) -> {
            completeCalls.incrementAndGet();
            firstComplete.countDown();
        });

        assertThat(firstComplete.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // grace window: let any hypothetical duplicate fire land before asserting
        assertThat(completeCalls.get()).isEqualTo(1);
        engine.stop();
    }
}
