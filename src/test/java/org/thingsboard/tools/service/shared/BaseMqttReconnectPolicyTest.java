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

import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.mqtt.DeviceClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BaseMqttReconnectPolicyTest {

    /** Minimal concrete BaseMqttAPITest; createClient works without Spring (no event loop needed). */
    static class ReconnectingApi extends BaseMqttAPITest {
        @Override protected String getWarmUpTopic() { return "t"; }
        @Override protected byte[] getData(String deviceName) { return new byte[0]; }
        @Override protected void runApiTestIteration(int i, AtomicInteger s, AtomicInteger f, CountDownLatch l) { }
        @Override protected String getTestTopic() { return "t"; }
        @Override protected void logSuccessTestMessage(int iteration, DeviceClient client) { }
        @Override protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) { }
    }

    /** Ephemeral-like subclass: reconnect disabled. */
    static class NonReconnectingApi extends ReconnectingApi {
        @Override protected boolean autoReconnect() { return false; }
    }

    @Test
    void defaultClientsAutoReconnect() {
        MqttClient client = new ReconnectingApi().createClient("token");
        assertThat(client.getClientConfig().isReconnect()).isTrue();
    }

    @Test
    void ephemeralClientsDoNotAutoReconnect() {
        MqttClient client = new NonReconnectingApi().createClient("token");
        assertThat(client.getClientConfig().isReconnect()).isFalse();
    }

    @Test
    void disablingReconnectDoesNotAlterDelayResolution() {
        // both min/max unset => resolver returns 0 => setReconnectDelay never called => library default 1s
        MqttClient client = new NonReconnectingApi().createClient("token");
        assertThat(client.getClientConfig().getReconnectDelay()).isEqualTo(1L);
    }
}
