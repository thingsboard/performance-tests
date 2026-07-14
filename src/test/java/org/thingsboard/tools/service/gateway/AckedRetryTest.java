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
package org.thingsboard.tools.service.gateway;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AckedRetryTest {

    /** Callbacks capture; the latch releases on either terminal (acked / unconfirmed). */
    static final class Counts implements AckedRetry.Callbacks {
        final AtomicInteger acked = new AtomicInteger();
        final AtomicInteger attemptFailed = new AtomicInteger();
        final AtomicInteger retried = new AtomicInteger();
        final AtomicInteger unconfirmed = new AtomicInteger();
        final CountDownLatch terminal = new CountDownLatch(1);

        public void onAcked() { acked.incrementAndGet(); terminal.countDown(); }
        public void onAttemptFailed() { attemptFailed.incrementAndGet(); }
        public void onRetry() { retried.incrementAndGet(); }
        public void onUnconfirmed() { unconfirmed.incrementAndGet(); terminal.countDown(); }

        void awaitTerminal(int sec) throws InterruptedException {
            assertThat(terminal.await(sec, TimeUnit.SECONDS)).as("reached a terminal outcome").isTrue();
        }
    }

    private static Supplier<Future<Void>> succeeds() {
        return () -> ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
    }

    @Test
    void acksOnFirstSuccess() throws Exception {
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        Counts c = new Counts();
        AckedRetry.run(sch, succeeds(), new AckedRetryConfig(5, 200, 1, 2), new Random(1L), c);
        c.awaitTerminal(2);
        assertThat(c.acked).hasValue(1);
        assertThat(c.unconfirmed).hasValue(0);
        assertThat(c.attemptFailed).hasValue(0);
        sch.shutdownNow();
    }

    @Test
    void retriesThenAcks() throws Exception {
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger n = new AtomicInteger();
        Counts c = new Counts();
        AckedRetry.run(sch, () -> n.getAndIncrement() < 2
                        ? ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("x"))
                        : ImmediateEventExecutor.INSTANCE.newSucceededFuture(null),
                new AckedRetryConfig(5, 200, 1, 2), new Random(1L), c);
        c.awaitTerminal(2);
        assertThat(c.acked).hasValue(1);
        assertThat(c.attemptFailed).hasValue(2);
        assertThat(c.retried).hasValue(2);
        assertThat(c.unconfirmed).hasValue(0);
        sch.shutdownNow();
    }

    @Test
    void neverCompletingFutureTimesOutAndEndsUnconfirmedWithoutHanging() throws Exception {
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        Counts c = new Counts();
        // A promise that is never completed models publishing while the channel is null.
        AckedRetry.run(sch, () -> ImmediateEventExecutor.INSTANCE.newPromise(),
                new AckedRetryConfig(3, 50, 1, 2), new Random(1L), c);
        c.awaitTerminal(3); // must not hang: each attempt times out and drives a retry
        assertThat(c.unconfirmed).hasValue(1);
        assertThat(c.attemptFailed).hasValue(3);
        assertThat(c.acked).hasValue(0);
        sch.shutdownNow();
    }
}
