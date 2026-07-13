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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the retry-loop / cycle-scheduler deadlock (see
 * docs/superpowers/specs/2026-07-13-ephemeral-retry-deadlock-issue.md).
 *
 * <p>Unlike {@link MqttGatewayEphemeralRetryTest} (which stubs {@code scheduleRetry}/{@code scheduleNext}
 * to run synchronously), this test drives the REAL {@code cycleScheduler} pool, the REAL blocking/
 * non-blocking permit acquisition in {@code runCycle}, and the REAL retry scheduling. It reproduces the
 * failure-burst deadlock: more gateways than permits all start at once and every connect fails for a
 * while, so retrying cycles hold every permit while fresh cycles pile onto the same small pool.
 *
 * <p>Only connect success/failure and publish are stubbed. Pre-fix (blocking
 * {@code acquireUninterruptibly}) the pool threads park on the permit, the retry tasks that would
 * release permits never get a thread, and the latch await below times out. Post-fix (non-blocking
 * {@code tryAcquire} + reschedule) cycling keeps progressing and every permit is returned.
 */
class MqttGatewayEphemeralDeadlockRegressionTest extends MqttGatewayEphemeralAPITest {

    static final ObjectMapper testMapper = new ObjectMapper();

    private static final int POOL_THREADS = 2;
    private static final int PERMIT_CAP = 2;
    private static final int GATEWAYS = 8;      // > PERMIT_CAP so cycles must contend for permits
    private static final int CONNECT_FAILURES = 20; // burst of failures before connects start succeeding

    final AtomicInteger connectFailuresRemaining = new AtomicInteger(CONNECT_FAILURES);
    CountDownLatch cyclesCompleted;

    @Override
    protected MqttClient createClient(String token) {
        MqttClient c = mock(MqttClient.class);
        when(c.publish(anyString(), any(), any())).thenAnswer(inv ->
                ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)); // publish always succeeds
        return c;
    }

    @Override
    protected Future<MqttConnectResult> connectAsync(MqttClient client) {
        if (connectFailuresRemaining.getAndDecrement() > 0) {
            return ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("connect reset"));
        }
        MqttConnectResult ok = mock(MqttConnectResult.class);
        when(ok.isSuccess()).thenReturn(true);
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(ok);
    }

    @Override
    protected void scheduleNext(GatewayTarget target) {
        // Count each completed cycle; do NOT re-arm — one completed cycle per gateway bounds the test.
        cyclesCompleted.countDown();
    }

    @AfterEach
    void tearDown() {
        running = false;
        if (cycleScheduler != null) {
            cycleScheduler.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    void failureBurstDoesNotDeadlockThePoolAndCyclingResumes() throws Exception {
        gatewayStartIdx = 0;
        gatewayEndIdx = GATEWAYS;
        deviceStartIdx = 0;
        deviceEndIdx = GATEWAYS; // one device per gateway keeps batches trivial
        telemetryTest = true;
        scheduleRandom = new Random(1L);
        maxRetries = 1000;        // never exhaust retries during the burst -> every cycle eventually succeeds
        retryBackoffMinMs = 1;
        retryBackoffMaxMs = 2;
        retryDeadlineMs = 0;
        permitWaitMs = 5;         // re-queue quickly when no permit is free
        connectPermits = new Semaphore(PERMIT_CAP);
        cyclesCompleted = new CountDownLatch(GATEWAYS);

        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextNodeMessage(anyString(), anyBoolean())).thenAnswer(inv -> {
            ObjectNode node = testMapper.createObjectNode();
            node.putArray(inv.getArgument(0)).addObject().put("ts", 1L);
            return new NodeMsg(node, false);
        });
        tsMsgGenerator = gen;

        cycleScheduler = Executors.newScheduledThreadPool(POOL_THREADS);
        running = true;

        List<GatewayTarget> all = buildTargets();
        for (GatewayTarget target : all) {
            cycleScheduler.schedule(() -> runCycle(target), 0, TimeUnit.MILLISECONDS); // initial wave, all at t=0
        }

        // Pre-fix this await never returns (deadlocked pool); post-fix all cycles complete quickly.
        boolean allDone = cyclesCompleted.await(10, TimeUnit.SECONDS);
        assertThat(allDone)
                .as("all %d gateways must complete a cycle despite the connect-failure burst", GATEWAYS)
                .isTrue();

        // Every permit taken by a (retrying) cycle must be returned once cycling settles.
        assertThat(connectPermits.availablePermits())
                .as("no permit may be permanently held")
                .isEqualTo(PERMIT_CAP);
    }
}
