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

import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Runs an idempotent MQTT op that returns a broker-ack {@link Future} (e.g. a QoS-1 publish → PUBACK),
 * tracking each attempt with a scheduled timeout and retrying with jittered-exponential backoff until
 * confirmed or the attempt cap is hit. Success is the broker ack, never a QoS-0-style "flushed to
 * socket". Non-blocking: all waiting/retrying happens on the supplied scheduler (the client's netty
 * event loop in production), so a caller on an event loop is never parked. Current sole user is the
 * gateway device announce; resubscribe intentionally does NOT use this (retrying a subscribe can
 * register a duplicate handler — it is observe-only, see {@code GatewayRpcReceiver}).
 *
 * <p>The timeout guard is essential because publishing while the client's channel is momentarily
 * {@code null} returns a future that never completes — a bare await would hang; here the timeout fires
 * and drives a retry. Each attempt has its own {@code settled} guard, so a late ack arriving after a
 * timeout is ignored (at worst a harmless duplicate publish; QoS 1 + first-response-wins make it safe).
 */
public final class AckedRetry {

    public interface Callbacks {
        void onAcked();
        void onAttemptFailed();
        void onRetry();
        void onUnconfirmed();
    }

    private AckedRetry() {
    }

    public static void run(ScheduledExecutorService scheduler, Supplier<Future<Void>> op,
                           AckedRetryConfig cfg, Random rng, Callbacks cb) {
        runAttempt(scheduler, op, 1, cfg, rng, cb);
    }

    private static void runAttempt(ScheduledExecutorService scheduler, Supplier<Future<Void>> op,
                                   int attempt, AckedRetryConfig cfg, Random rng, Callbacks cb) {
        AtomicBoolean settled = new AtomicBoolean();
        Future<Void> f;
        try {
            f = op.get();
        } catch (Exception e) {
            fail(settled, null, scheduler, op, attempt, cfg, rng, cb);
            return;
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = scheduler.schedule(
                    () -> fail(settled, null, scheduler, op, attempt, cfg, rng, cb),
                    cfg.ackTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            // Scheduler shutting down (test window closed): give up cleanly rather than leak.
            if (settled.compareAndSet(false, true)) {
                cb.onAttemptFailed();
                cb.onUnconfirmed();
            }
            return;
        }
        f.addListener(fut -> {
            if (fut.isSuccess()) {
                if (settled.compareAndSet(false, true)) {
                    timeout.cancel(false);
                    cb.onAcked();
                }
            } else {
                fail(settled, timeout, scheduler, op, attempt, cfg, rng, cb);
            }
        });
    }

    private static void fail(AtomicBoolean settled, ScheduledFuture<?> timeout,
                             ScheduledExecutorService scheduler, Supplier<Future<Void>> op,
                             int attempt, AckedRetryConfig cfg, Random rng, Callbacks cb) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        if (timeout != null) {
            timeout.cancel(false);
        }
        cb.onAttemptFailed();
        if (attempt >= cfg.maxAttempts()) {
            cb.onUnconfirmed();
            return;
        }
        cb.onRetry();
        long backoff = EphemeralRetryBackoff.resolveMillis(attempt, cfg.backoffMinMs(), cfg.backoffMaxMs(), rng);
        try {
            scheduler.schedule(() -> runAttempt(scheduler, op, attempt + 1, cfg, rng, cb),
                    backoff, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            cb.onUnconfirmed(); // scheduler shutting down: give up, do not leak
        }
    }
}
