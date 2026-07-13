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
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retry-loop unit tests. The test IS the SUT; connect results are dequeued per attempt, publishes
 * succeed/fail per a flag, and scheduleRetry runs the attempt synchronously (recursively) so the
 * whole bounded loop executes with no timer or broker. scheduleNext is stubbed to a counter.
 */
class MqttGatewayEphemeralRetryTest extends MqttGatewayEphemeralAPITest {

    static final ObjectMapper testMapper = new ObjectMapper();

    final Deque<Future<MqttConnectResult>> connectQueue = new ArrayDeque<>();
    boolean publishSucceeds = true;
    int createdClients;
    int retryScheduleCalls;
    int rescheduleCalls;
    MqttClient lastClient;

    @Override
    protected MqttClient createClient(String token) {
        MqttClient c = mock(MqttClient.class);
        when(c.publish(anyString(), any(), any())).thenReturn(
                publishSucceeds
                        ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)
                        : ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("pub fail")));
        createdClients++;
        lastClient = c;
        return c;
    }

    @Override
    protected Future<MqttConnectResult> connectAsync(MqttClient client) {
        return connectQueue.isEmpty() ? succeededConnect() : connectQueue.poll();
    }

    @Override
    protected boolean scheduleRetry(GatewayTarget target, Runnable attempt, long backoffMillis) {
        retryScheduleCalls++;
        attempt.run(); // synchronous — no real timer
        return true;
    }

    @Override
    protected void scheduleNext(GatewayTarget target) {
        rescheduleCalls++;
    }

    private void configure() {
        gatewayStartIdx = 0;
        gatewayEndIdx = 1;
        deviceStartIdx = 0;
        deviceEndIdx = 2;
        telemetryTest = true;
        scheduleRandom = new Random(1L);
        retryBackoffMinMs = 1;
        retryBackoffMaxMs = 2;
        retryDeadlineMs = 0;
        connectPermits = new Semaphore(8);
        MessageGenerator gen = mock(MessageGenerator.class);
        when(gen.getNextNodeMessage(anyString(), anyBoolean())).thenAnswer(inv -> {
            ObjectNode node = testMapper.createObjectNode();
            node.putArray(inv.getArgument(0)).addObject().put("ts", 1L);
            return new NodeMsg(node, false);
        });
        tsMsgGenerator = gen;
    }

    private static Future<MqttConnectResult> succeededConnect() {
        MqttConnectResult ok = mock(MqttConnectResult.class);
        when(ok.isSuccess()).thenReturn(true);
        return ImmediateEventExecutor.INSTANCE.newSucceededFuture(ok);
    }

    private static Future<MqttConnectResult> failedConnect() {
        return ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("connect refused"));
    }

    @Test
    void recoversAfterTransientConnectFailures() {
        configure();
        maxRetries = 3;
        connectQueue.add(failedConnect()); // attempt 1 fails
        connectQueue.add(failedConnect()); // attempt 2 fails
        // attempt 3 -> queue empty -> succeededConnect(), publish ok

        runCycle(buildTargets().get(0));

        assertThat(retryScheduleCalls).isEqualTo(2);
        assertThat(createdClients).isEqualTo(3);
        assertThat(rescheduleCalls).isEqualTo(1);                 // finalized exactly once
        assertThat(connectPermits.availablePermits()).isEqualTo(8); // released exactly once
        assertThat(connectionCallbacks).isEmpty();                 // every attempt client removed
        assertThat(ephemeralStats.summaryAndReset(1))
                .contains("retries=2, recovered=1, lost=0");
    }

    @Test
    void exhaustsRetriesThenCountsLostAndFinalizesOnce() {
        configure();
        maxRetries = 2;
        connectQueue.add(failedConnect());
        connectQueue.add(failedConnect());
        connectQueue.add(failedConnect()); // 1 + 2 retries all fail

        runCycle(buildTargets().get(0));

        assertThat(retryScheduleCalls).isEqualTo(2);
        assertThat(createdClients).isEqualTo(3);
        assertThat(rescheduleCalls).isEqualTo(1);
        assertThat(connectPermits.availablePermits()).isEqualTo(8);
        assertThat(connectionCallbacks).isEmpty();
        assertThat(ephemeralStats.summaryAndReset(1))
                .contains("retries=2, recovered=0, lost=1");
    }

    @Test
    void zeroMaxRetriesReproducesSingleAttempt() {
        configure();
        maxRetries = 0;
        connectQueue.add(failedConnect());

        runCycle(buildTargets().get(0));

        assertThat(retryScheduleCalls).isZero();
        assertThat(createdClients).isEqualTo(1);
        assertThat(rescheduleCalls).isEqualTo(1);
        assertThat(connectPermits.availablePermits()).isEqualTo(8);
        assertThat(ephemeralStats.summaryAndReset(1))
                .contains("retries=0, recovered=0, lost=1");
    }
}
