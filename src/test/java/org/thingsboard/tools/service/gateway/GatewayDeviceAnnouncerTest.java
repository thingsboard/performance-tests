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

import io.netty.buffer.ByteBuf;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientCallback;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.mqtt.MqttHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayDeviceAnnouncerTest {

    private final EventLoopGroup loop = new DefaultEventLoopGroup(1);
    private static final byte[] PAYLOAD = "{\"device\":\"D1\"}".getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    private GatewayDeviceAnnouncer announcer(AnnounceStats stats, int maxAttempts, int maxConcurrent) {
        return new GatewayDeviceAnnouncer(stats, new AckedRetryConfig(maxAttempts, 200, 1, 2),
                new Random(1L), maxConcurrent, 5);
    }

    @Test
    void failedThenAckedRetriesAndConfirms() throws Exception {
        AnnounceStats stats = new AnnounceStats();
        GatewayDeviceAnnouncer a = announcer(stats, 5, 4);
        FakeClient c = new FakeClient(loop);
        c.outcomes.add(Outcome.FAIL); // first publish fails; the rest default to OK

        Future<Void> done = a.announce(c, PAYLOAD);
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(done.isSuccess()).isTrue();
        assertThat(stats.getAcked()).isEqualTo(1);
        assertThat(stats.getRetried()).isGreaterThanOrEqualTo(1);
        assertThat(stats.getUnconfirmed()).isZero();
        assertThat(c.publishCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void channelNullPublishDoesNotHangAndRetries() throws Exception {
        AnnounceStats stats = new AnnounceStats();
        GatewayDeviceAnnouncer a = announcer(stats, 5, 4);
        FakeClient c = new FakeClient(loop);
        c.outcomes.add(Outcome.HANG); // models publish while channel is null: future never completes

        Future<Void> done = a.announce(c, PAYLOAD);
        assertThat(done.await(3, TimeUnit.SECONDS)).as("must not hang").isTrue();
        assertThat(done.isSuccess()).isTrue();
        assertThat(stats.getAcked()).isEqualTo(1);
        assertThat(stats.getRetried()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void exhaustsAttemptsThenUnconfirmedAndReleasesPermit() throws Exception {
        AnnounceStats stats = new AnnounceStats();
        GatewayDeviceAnnouncer a = announcer(stats, 2, 1); // maxConcurrent=1 so a leaked permit would hang the 2nd
        FakeClient c = new FakeClient(loop);
        c.outcomes.add(Outcome.FAIL);
        c.outcomes.add(Outcome.FAIL); // both attempts fail -> unconfirmed

        Future<Void> first = a.announce(c, PAYLOAD);
        assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(first.isSuccess()).isFalse();
        assertThat(stats.getUnconfirmed()).isEqualTo(1);

        // permit must have been released: a second announce (defaults to OK) completes.
        Future<Void> second = a.announce(c, PAYLOAD);
        assertThat(second.await(3, TimeUnit.SECONDS)).as("permit was released").isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(stats.getAcked()).isEqualTo(1);
    }

    enum Outcome { OK, FAIL, HANG }

    /** Fake whose publish result is dequeued (default OK) and whose event loop drives AckedRetry timers. */
    static class FakeClient implements MqttClient {
        final Deque<Outcome> outcomes = new ArrayDeque<>();
        final EventLoopGroup eventLoop;
        volatile int publishCount;

        FakeClient(EventLoopGroup eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos) {
            publishCount++;
            Outcome o = outcomes.isEmpty() ? Outcome.OK : outcomes.poll();
            switch (o) {
                case FAIL:
                    return ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("pub fail"));
                case HANG:
                    Promise<Void> never = ImmediateEventExecutor.INSTANCE.newPromise();
                    return never; // never completed
                default:
                    return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
            }
        }

        @Override public EventLoopGroup getEventLoop() { return eventLoop; }
        @Override public boolean isConnected() { return true; }

        // --- unused interface methods ---
        @Override public Promise<MqttConnectResult> connect(String host) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> connect(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> reconnect() { throw new UnsupportedOperationException(); }
        @Override public void setEventLoop(EventLoopGroup group) { throw new UnsupportedOperationException(); }
        @Override public ListeningExecutor getHandlerExecutor() { throw new UnsupportedOperationException(); }
        @Override public Future<Void> on(String topic, MqttHandler handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> on(String topic, MqttHandler handler, MqttQoS qos) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> once(String topic, MqttHandler handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> once(String topic, MqttHandler handler, MqttQoS qos) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> off(String topic, MqttHandler handler) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> off(String topic) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> publish(String topic, ByteBuf payload) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> publish(String topic, ByteBuf payload, boolean retain) { throw new UnsupportedOperationException(); }
        @Override public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos, boolean retain) { throw new UnsupportedOperationException(); }
        @Override public MqttClientConfig getClientConfig() { throw new UnsupportedOperationException(); }
        @Override public void disconnect() { throw new UnsupportedOperationException(); }
        @Override public void setCallback(MqttClientCallback callback) { throw new UnsupportedOperationException(); }
    }
}
