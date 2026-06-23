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
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientCallback;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.mqtt.MqttHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class GatewayRpcReceiverTest {

    private GatewayRpcReceiver receiver(RpcLatencyStats stats) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        RpcMessageProcessor processor = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, template, stats);
        return new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, processor, stats);
    }

    @Test
    void handlerPublishesResponseAndCountsSent() throws Exception {
        RpcLatencyStats stats = new RpcLatencyStats();
        GatewayRpcReceiver r = receiver(stats);
        FakeMqttClient fake = new FakeMqttClient();

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
        FakeMqttClient a = new FakeMqttClient();
        FakeMqttClient b = new FakeMqttClient();
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
        GatewayRpcReceiver r = new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, throwing, stats);
        FakeMqttClient fake = new FakeMqttClient();
        MqttHandler handler = r.buildHandler(fake);
        ByteBuf in = Unpooled.wrappedBuffer("{}".getBytes(StandardCharsets.UTF_8));
        assertThatNoException().isThrownBy(() -> handler.onMessage("v1/gateway/rpc", in));
        assertThat(fake.publishedTopics).isEmpty();
    }

    /** Minimal MqttClient test double: only on(3-arg), publish(3-arg) and isConnected are functional. */
    static class FakeMqttClient implements MqttClient {
        final List<String> subscribedTopics = new ArrayList<>();
        final List<String> publishedTopics = new ArrayList<>();
        final List<String> publishedPayloads = new ArrayList<>();

        @Override
        public Future<Void> on(String topic, MqttHandler handler, MqttQoS qos) {
            subscribedTopics.add(topic);
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }

        @Override
        public Future<Void> publish(String topic, ByteBuf payload, MqttQoS qos) {
            publishedTopics.add(topic);
            publishedPayloads.add(payload.toString(StandardCharsets.UTF_8));
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }

        @Override public boolean isConnected() { return true; }

        // --- unused interface methods ---
        @Override public Promise<MqttConnectResult> connect(String host) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> connect(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Promise<MqttConnectResult> reconnect() { throw new UnsupportedOperationException(); }
        @Override public EventLoopGroup getEventLoop() { throw new UnsupportedOperationException(); }
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
