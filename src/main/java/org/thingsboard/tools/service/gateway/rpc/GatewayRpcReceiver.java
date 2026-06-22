package org.thingsboard.tools.service.gateway.rpc;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttHandler;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class GatewayRpcReceiver {

    private final String topic;
    private final MqttQoS qos;
    private final RpcMessageProcessor processor;
    private final RpcLatencyStats stats;
    private final boolean respond;

    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, boolean respond) {
        this.topic = topic;
        this.qos = qos;
        this.processor = processor;
        this.stats = stats;
        this.respond = respond;
    }

    public void attach(List<MqttClient> clients) {
        for (MqttClient client : clients) {
            client.on(topic, buildHandler(client), qos)
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("Failed to subscribe a gateway to RPC topic {}", topic, f.cause());
                        }
                    });
        }
        log.info("Subscribed {} gateways to RPC topic {} (respond={})", clients.size(), topic, respond);
    }

    MqttHandler buildHandler(MqttClient client) {
        return (receivedTopic, payload) -> {
            long now = System.currentTimeMillis();
            try {
                byte[] response = processor.process(payload, now);
                if (response != null) {
                    client.publish(topic, Unpooled.wrappedBuffer(response), qos)
                            .addListener(f -> {
                                if (f.isSuccess()) {
                                    stats.incResponsesSent();
                                } else {
                                    stats.incResponseErrors();
                                }
                            });
                }
            } catch (Exception e) {
                log.warn("Failed to handle inbound RPC", e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    public String statsSummaryAndReset(int intervalSec) {
        return stats.summaryAndReset(intervalSec);
    }
}
