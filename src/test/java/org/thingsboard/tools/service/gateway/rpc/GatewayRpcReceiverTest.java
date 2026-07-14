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
package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import org.thingsboard.tools.service.gateway.AckedRetryConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GatewayRpcReceiverTest {

    private final EventLoopGroup loop = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    private GatewayRpcReceiver receiver(RpcLatencyStats stats) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        RpcMessageProcessor processor = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, template, stats);
        return new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, processor, stats, 0L);
    }

    @Test
    void handlerPublishesResponseAndCountsSent() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);

        MqttHandler handler = r.buildHandler(fake);
        ByteBuf in = Unpooled.wrappedBuffer(
                "{\"device\":\"GW1\",\"data\":{\"id\":5,\"params\":{\"sendTs\":1000}}}".getBytes(StandardCharsets.UTF_8));
        handler.onMessage("v1/gateway/rpc", in);

        assertThat(fake.publishedTopics).containsExactly("v1/gateway/rpc");
        assertThat(fake.publishedPayloads.get(0)).contains("\"device\":\"GW1\"").contains("\"id\":5");
        assertThat(stats.getResponsesSent()).isEqualTo(1);
        assertThat(stats.getCount()).isEqualTo(1);
    }

    @Test
    void attachSubscribesEachClient() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient a = new FakeMqttClient(loop);
        FakeMqttClient b = new FakeMqttClient(loop);
        r.attach(List.of(a, b));
        assertThat(a.subscribedTopics).containsExactly("v1/gateway/rpc");
        assertThat(b.subscribedTopics).containsExactly("v1/gateway/rpc");
    }

    @Test
    void handlerSwallowsProcessorException() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcResponseTemplate template = new RpcResponseTemplate("{}");
        RpcMessageProcessor throwing = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, template, stats) {
            @Override
            public byte[] process(byte[] payload, long nowMs) {
                throw new RuntimeException("boom");
            }
        };
        GatewayRpcReceiver r = new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, throwing, stats, 0L);
        FakeMqttClient fake = new FakeMqttClient(loop);
        MqttHandler handler = r.buildHandler(fake);
        ByteBuf in = Unpooled.wrappedBuffer("{}".getBytes(StandardCharsets.UTF_8));
        assertThatNoException().isThrownBy(() -> handler.onMessage("v1/gateway/rpc", in));
        assertThat(fake.publishedTopics).isEmpty();
    }

    @Test
    void resubscribeSubscribesTheClientAgain() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        r.attach(List.of(fake));
        assertThat(fake.subscribedTopics).containsExactly("v1/gateway/rpc");
        r.resubscribe(fake);
        assertThat(fake.subscribedTopics).containsExactly("v1/gateway/rpc", "v1/gateway/rpc");
    }

    @Test
    void resubscribeRetriesUntilSuback() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor processor = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, new RpcResponseTemplate("{}"), stats);
        GatewayRpcReceiver r = new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, processor, stats, 0L,
                false, 0L, 0, new AckedRetryConfig(5, 100, 1, 2), new Random(1L)); // fast retry for the test
        FakeMqttClient fake = new FakeMqttClient(loop);
        fake.onOutcomes.add(false); // first SUBSCRIBE fails (no SUBACK); the retry succeeds

        r.resubscribe(fake);

        long deadline = System.currentTimeMillis() + 3000L;
        while (stats.getSubscribeAcked() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(stats.getSubscribeAcked()).isEqualTo(1);
        assertThat(stats.getSubscribeFailed()).isGreaterThanOrEqualTo(1);
        assertThat(stats.getSubscribeRetried()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void drainQuiescesImmediatelyWhenNothingReceived() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        GatewayRpcReceiver.DrainResult res = r.drain(1000L, 5000L, true);
        assertThat(res.quiesced).isTrue();
        assertThat(res.elapsedMs).isLessThan(1000L);
    }

    @Test
    void drainSettlesWhenIdleAndRepliesCaughtUp() {
        RpcLatencyStats stats = new RpcLatencyStats();
        stats.incReceived(System.currentTimeMillis() - 10_000L); // inbound 10s ago
        stats.incResponsesSent();                                // reply already sent
        GatewayRpcReceiver r = receiver(stats);
        GatewayRpcReceiver.DrainResult res = r.drain(1000L, 5000L, true);
        assertThat(res.quiesced).isTrue();
    }

    @Test
    void drainReturnsCappedWhenInboundStaysActive() {
        RpcLatencyStats stats = new RpcLatencyStats();
        stats.incReceived(System.currentTimeMillis()); // just received, reply still pending
        GatewayRpcReceiver r = receiver(stats);
        GatewayRpcReceiver.DrainResult res = r.drain(10_000L, 300L, true);
        assertThat(res.quiesced).isFalse();
        assertThat(res.elapsedMs).isGreaterThanOrEqualTo(300L);
    }

    @Test
    void drainIgnoresPendingWhenRespondFalse() {
        RpcLatencyStats stats = new RpcLatencyStats();
        stats.incReceived(System.currentTimeMillis() - 10_000L); // idle, but no reply sent
        GatewayRpcReceiver r = receiver(stats);
        GatewayRpcReceiver.DrainResult res = r.drain(1000L, 5000L, false);
        assertThat(res.quiesced).isTrue();
    }

    @Test
    void resolveDrainMaxMsExplicitOverride() {
        assertThat(GatewayRpcReceiver.resolveDrainMaxMs(20, true, 10000, 0, 5)).isEqualTo(20_000L);
    }

    @Test
    void resolveDrainMaxMsDerivesFromSenderTimeout() {
        assertThat(GatewayRpcReceiver.resolveDrainMaxMs(0, true, 10000, 2000, 5)).isEqualTo(17_000L);
    }

    @Test
    void resolveDrainMaxMsFallsBackWhenSenderOff() {
        assertThat(GatewayRpcReceiver.resolveDrainMaxMs(0, false, 10000, 0, 5)).isEqualTo(30_000L);
    }

    @Test
    void resolveDrainMaxMsFlooredToQuiet() {
        assertThat(GatewayRpcReceiver.resolveDrainMaxMs(0, true, 100, 0, 60)).isEqualTo(60_000L);
    }

    /** Minimal MqttClient test double: on(3-arg), publish(3-arg), getEventLoop and isConnected are
     *  functional. {@code onOutcomes} controls per-attempt subscribe success (default success). */
    static class FakeMqttClient implements MqttClient {
        final List<String> subscribedTopics = new ArrayList<>();
        final List<String> publishedTopics = new ArrayList<>();
        final List<String> publishedPayloads = new ArrayList<>();
        final Deque<Boolean> onOutcomes = new ArrayDeque<>();
        private final EventLoopGroup eventLoop;

        FakeMqttClient(EventLoopGroup eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public Future<Void> on(String topic, MqttHandler handler, MqttQoS qos) {
            subscribedTopics.add(topic);
            boolean ok = onOutcomes.isEmpty() || onOutcomes.poll();
            return ok ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)
                    : ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("suback fail"));
        }

        @Override
        public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos) {
            publishedTopics.add(topic);
            publishedPayloads.add(payload.toString(StandardCharsets.UTF_8));
            return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
        }

        @Override public boolean isConnected() { return true; }
        @Override public EventLoopGroup getEventLoop() { return eventLoop; }

        // --- unused interface methods ---
        @Override public Promise<MqttConnectResult> connect(String host) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> connect(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> reconnect() { throw new UnsupportedOperationException(); }
        @Override public void setEventLoop(EventLoopGroup group) { throw new UnsupportedOperationException(); }
        @Override public ListeningExecutor getHandlerExecutor() { throw new UnsupportedOperationException(); }
        @Override public Future<Void> on(String topic, MqttHandler handler) { throw new UnsupportedOperationException(); }
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
