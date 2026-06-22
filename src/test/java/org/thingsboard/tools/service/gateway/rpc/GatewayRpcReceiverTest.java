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

class GatewayRpcReceiverTest {

    private GatewayRpcReceiver receiver(RpcLatencyStats stats) {
        RpcResponseTemplate template = new RpcResponseTemplate(
                "{\"device\":\"${device}\",\"id\":${data.id},\"data\":{\"status\":\"ACCEPTED\",\"receivedAt\":${now}}}");
        RpcMessageProcessor processor = new RpcMessageProcessor(
                new ObjectMapper(), "data.params.sendTs", true, template, stats);
        return new GatewayRpcReceiver("v1/gateway/rpc", MqttQoS.AT_LEAST_ONCE, processor, stats, true);
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
