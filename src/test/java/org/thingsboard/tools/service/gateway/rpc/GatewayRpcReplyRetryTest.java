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

/**
 * Regression test for RPC reply retry-on-reconnect (see
 * docs/superpowers/specs/2026-07-14-rpc-reply-retry-on-reconnect-design.md). Drives the real publish
 * and flush paths with a fake client whose publish outcome is dequeued per call, so a reply that fails
 * to publish (channel dropped mid-reply) is buffered and re-published on the reconnect flush.
 */
class GatewayRpcReplyRetryTest {

    private static final String TOPIC = "v1/gateway/rpc";

    private final EventLoopGroup loop = new DefaultEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
    }

    private GatewayRpcReceiver receiver(RpcLatencyStats stats, long ttlMs, int cap) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\"}}");
        RpcMessageProcessor processor = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, template);
        // Large ack timeout: publish futures complete synchronously in these tests, so the orphan-capture
        // timeout is always cancelled before it could fire.
        return new GatewayRpcReceiver(TOPIC, MqttQoS.AT_LEAST_ONCE, processor, stats, 0L, true, ttlMs, cap, 60_000L);
    }

    private static ByteBuf rpc(int id) {
        return Unpooled.wrappedBuffer(
                ("{\"device\":\"GW1\",\"data\":{\"id\":" + id + ",\"params\":{\"sendTs\":1}}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void failedReplyIsBufferedThenRecoveredOnReconnectFlush() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats, 60_000L, 100);
        RetryFakeClient c = client();
        c.publishOutcomes.add(false); // first publish fails -> buffered

        r.buildHandler(c).onMessage(TOPIC, rpc(1));
        assertThat(stats.getRetryQueued()).isEqualTo(1);
        assertThat(stats.getResponsesSent()).isZero();

        r.flushReplies(c); // reconnect: subsequent publish succeeds -> recovered
        assertThat(stats.getRecovered()).isEqualTo(1);
        assertThat(stats.getLost()).isZero();
        assertThat(c.publishedPayloads).hasSize(2); // first (failed) + retry
    }

    @Test
    void expiredReplyIsDroppedAsLostNotRetried() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats, 0L, 100); // ttl 0 => expiry == receipt time
        RetryFakeClient c = client();
        c.publishOutcomes.add(false);

        r.buildHandler(c).onMessage(TOPIC, rpc(1));
        // ttl=0 means now >= expiry at enqueue -> lost immediately, nothing buffered.
        int publishesSoFar = c.publishedPayloads.size();
        r.flushReplies(c);
        assertThat(stats.getLost()).isEqualTo(1);
        assertThat(stats.getRecovered()).isZero();
        assertThat(stats.getRetryQueued()).isZero();
        assertThat(c.publishedPayloads).hasSize(publishesSoFar); // no retry publish
    }

    @Test
    void bufferIsBoundedAndOvercapCountedLost() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats, 60_000L, 2); // cap 2 per client
        RetryFakeClient c = client();
        for (int i = 0; i < 5; i++) {
            c.publishOutcomes.add(false); // all 5 first-publishes fail
        }

        for (int i = 0; i < 5; i++) {
            r.buildHandler(c).onMessage(TOPIC, rpc(i));
        }

        assertThat(stats.getRetryQueued()).isEqualTo(2); // only 2 buffered
        assertThat(stats.getLost()).isEqualTo(3);        // 3 over-cap dropped
    }

    @Test
    void drainActivelyRecoversBufferedReplyWithoutAReconnect() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats, 60_000L, 100);
        RetryFakeClient c = client();
        c.publishOutcomes.add(false); // first publish fails -> buffered, RPC left pending

        r.buildHandler(c).onMessage(TOPIC, rpc(1));
        assertThat(stats.getRetryQueued()).isEqualTo(1);

        // No reconnect / no explicit flush: the drain loop itself must re-send the buffered reply on
        // the (live) client and settle, instead of waiting out maxMs doing nothing.
        GatewayRpcReceiver.DrainResult res = r.drain(50L, 3000L, true);
        assertThat(res.quiesced).isTrue();
        assertThat(res.elapsedMs).isLessThan(3000L);
        assertThat(stats.getRecovered()).isEqualTo(1);
        assertThat(stats.getLost()).isZero();
    }

    @Test
    void finalizeCountsStillBufferedAsLost() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats, 60_000L, 100);
        RetryFakeClient c = client();
        c.publishOutcomes.add(false);
        r.buildHandler(c).onMessage(TOPIC, rpc(1)); // buffered, client never reconnects

        r.finalizeLostReplies();
        assertThat(stats.getLost()).isEqualTo(1);
    }

    private RetryFakeClient client() {
        return new RetryFakeClient(loop);
    }

    /** Fake whose publish result is dequeued from publishOutcomes (true=success); default success. */
    static class RetryFakeClient implements MqttClient {
        final Deque<Boolean> publishOutcomes = new ArrayDeque<>();
        final List<String> publishedPayloads = new ArrayList<>();
        private final EventLoopGroup eventLoop;

        RetryFakeClient(EventLoopGroup eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos) {
            publishedPayloads.add(payload.toString(StandardCharsets.UTF_8));
            boolean ok = publishOutcomes.isEmpty() || publishOutcomes.poll();
            return ok ? ImmediateEventExecutor.INSTANCE.newSucceededFuture(null)
                    : ImmediateEventExecutor.INSTANCE.newFailedFuture(new RuntimeException("pub fail"));
        }

        @Override
        public Future<Void> on(String topic, MqttHandler handler, MqttQoS qos) {
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
