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

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GatewayRpcReceiverTest {

    private final EventLoopGroup loop = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    private RpcMessageProcessor processor() {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        return new RpcMessageProcessor(new ObjectMapper(), "data.params.sendTs", true, template);
    }

    private GatewayRpcReceiver receiver(RpcLatencyStats stats) {
        // large backoff -> the reply-retry timer stays dormant during these synchronous tests
        return new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, processor(), stats, 0L,
                true, 60_000L, 64, 5000L, 60_000L, 60_000L);
    }

    private static ByteBuf rpc(String device, int id) {
        return Unpooled.wrappedBuffer(
                ("{\"device\":\"" + device + "\",\"data\":{\"id\":" + id + ",\"params\":{\"sendTs\":1}}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void handlerPublishesResponseAndCountsSent() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);

        r.buildHandler(fake).onMessage("v1/gateway/rpc", rpc("GW1", 5));

        assertThat(fake.publishedTopics).containsExactly("v1/gateway/rpc");
        assertThat(fake.publishedPayloads.get(0)).contains("\"device\":\"GW1\"").contains("\"id\":5");
        assertThat(stats.getResponsesSent()).isEqualTo(1);
        assertThat(stats.getReceived()).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryCountedOnceAndNotReAnswered() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        MqttHandler handler = r.buildHandler(fake);

        handler.onMessage("v1/gateway/rpc", rpc("GW1", 5)); // first receipt: answered
        handler.onMessage("v1/gateway/rpc", rpc("GW1", 5)); // server redelivery of the same RPC

        assertThat(stats.getReceived()).isEqualTo(2);    // raw counts both deliveries
        assertThat(stats.getDuplicate()).isEqualTo(1);   // second recognised as a duplicate
        assertThat(stats.getResponsesSent()).isEqualTo(1); // answered exactly once
        assertThat(fake.publishedPayloads).hasSize(1);     // no re-publish for the duplicate
    }

    @Test
    void attachSubscribesEachClientAndClearsUnconfirmedOnAck() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient a = new FakeMqttClient(loop);
        FakeMqttClient b = new FakeMqttClient(loop);
        r.attach(List.of(a, b));
        assertThat(a.subscribedTopics).containsExactly("v1/gateway/rpc");
        assertThat(b.subscribedTopics).containsExactly("v1/gateway/rpc");
        // both SUBACKed -> acked=2 and the unconfirmed gauge is back to 0
        assertThat(r.subscriptionSummary(10)).isEqualTo("RPC Subscription [window 10s]: acked=2, failed=0, unconfirmed=0");
    }

    @Test
    void subscribeFailureCountsFailedAndStaysUnconfirmed() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        fake.onOutcomes.add(false); // SUBACK fails -> not confirmed
        r.resubscribe(fake);
        assertThat(r.subscriptionSummary(10)).isEqualTo("RPC Subscription [window 10s]: acked=0, failed=1, unconfirmed=1");
    }

    @Test
    void slowSubackIsNotAFalsePositive_gaugeSelfClears() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        fake.onHangs = true; // SUBACK not yet arrived (models a slow / never-completing subscribe)
        r.resubscribe(fake);
        // gauge shows the client as unconfirmed — no timeout, no false 'failed', one attempt only
        assertThat(r.subscriptionSummary(10)).isEqualTo("RPC Subscription [window 10s]: acked=0, failed=0, unconfirmed=1");
        assertThat(fake.subscribedTopics).containsExactly("v1/gateway/rpc"); // no retry loop
    }

    @Test
    void handlerSwallowsProcessorException() {
        RpcLatencyStats stats = new RpcLatencyStats();
        RpcMessageProcessor throwing = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, new RpcResponseTemplate("{}")) {
            @Override
            public ProcessedRpc process(byte[] payload, long nowMs) {
                throw new RuntimeException("boom");
            }
        };
        GatewayRpcReceiver r = new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, throwing, stats, 0L);
        FakeMqttClient fake = new FakeMqttClient(loop);
        ByteBuf in = Unpooled.wrappedBuffer("{}".getBytes(StandardCharsets.UTF_8));
        assertThatNoException().isThrownBy(() -> r.buildHandler(fake).onMessage("v1/gateway/rpc", in));
        assertThat(fake.publishedTopics).isEmpty();
    }

    @Test
    void resubscribeReusesTheSameHandlerInstance() {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        r.attach(List.of(fake));
        r.resubscribe(fake);
        assertThat(fake.subscribedTopics).containsExactly("v1/gateway/rpc", "v1/gateway/rpc");
        assertThat(fake.subscribedHandlers.get(0)).isSameAs(fake.subscribedHandlers.get(1)); // dedup-safe
    }

    @Test
    void drainQuiescesImmediatelyWhenNothingReceived() {
        GatewayRpcReceiver r = receiver(new RpcLatencyStats());
        GatewayRpcReceiver.DrainResult res = r.drain(1000L, 5000L, true);
        assertThat(res.quiesced).isTrue();
        assertThat(res.elapsedMs).isLessThan(1000L);
    }

    @Test
    void drainSettlesWhenIdleAndNothingOutstanding() {
        RpcLatencyStats stats = new RpcLatencyStats();
        stats.incReceived(System.currentTimeMillis() - 10_000L); // inbound 10s ago, but answered (nothing outstanding)
        GatewayRpcReceiver r = receiver(stats);
        assertThat(r.drain(1000L, 5000L, true).quiesced).isTrue();
    }

    @Test
    void drainReturnsCappedWhileAnRpcStaysOutstanding() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient(loop);
        fake.publishHangs = true; // reply never confirms -> RPC stays outstanding
        r.buildHandler(fake).onMessage("v1/gateway/rpc", rpc("GW1", 5));

        GatewayRpcReceiver.DrainResult res = r.drain(50L, 400L, true);
        assertThat(res.quiesced).isFalse();
        assertThat(res.elapsedMs).isGreaterThanOrEqualTo(400L);
    }

    @Test
    void drainIgnoresOutstandingWhenRespondFalse() {
        RpcLatencyStats stats = new RpcLatencyStats();
        stats.incReceived(System.currentTimeMillis() - 10_000L);
        GatewayRpcReceiver r = receiver(stats);
        assertThat(r.drain(1000L, 5000L, false).quiesced).isTrue();
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

    /** Minimal MqttClient test double: on(3-arg), publish(3-arg), getEventLoop, isConnected functional.
     *  onOutcomes controls per-call subscribe success; onHangs/publishHangs return a never-completing
     *  future (models the netty-mqtt orphan). */
    static class FakeMqttClient implements MqttClient {
        final List<String> subscribedTopics = new ArrayList<>();
        final List<MqttHandler> subscribedHandlers = new ArrayList<>();
        final List<String> publishedTopics = new ArrayList<>();
        final List<String> publishedPayloads = new ArrayList<>();
        final Deque<Boolean> onOutcomes = new ArrayDeque<>();
        boolean onHangs;
        boolean publishHangs;
        private final EventLoopGroup eventLoop;

        FakeMqttClient(EventLoopGroup eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public Future<Void> on(String topic, MqttHandler handler, MqttQoS qos) {
            subscribedTopics.add(topic);
            subscribedHandlers.add(handler);
            if (onHangs) {
                return ImmediateEventExecutor.INSTANCE.newPromise();
            }
            boolean ok = onOutcomes.isEmpty() || onOutcomes.poll();
            return ok ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)
                    : ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("suback fail"));
        }

        @Override
        public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos) {
            publishedTopics.add(topic);
            publishedPayloads.add(payload.toString(StandardCharsets.UTF_8));
            return publishHangs
                    ? ImmediateEventExecutor.INSTANCE.newPromise()
                    : ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
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
