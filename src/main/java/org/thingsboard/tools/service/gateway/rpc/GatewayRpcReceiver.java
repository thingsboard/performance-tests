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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttHandler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GatewayRpcReceiver {

    private final String topic;
    private final MqttQoS qos;
    private final RpcMessageProcessor processor;
    private final RpcLatencyStats stats;
    private final long responseDelayMs;

    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, long responseDelayMs) {
        this.topic = topic;
        this.qos = qos;
        this.processor = processor;
        this.stats = stats;
        this.responseDelayMs = responseDelayMs;
    }

    public void attach(List<MqttClient> clients) {
        for (MqttClient client : clients) {
            subscribe(client);
        }
        log.info("Subscribed {} gateways to RPC topic {}", clients.size(), topic);
    }

    /** Re-issue the RPC-topic subscription for one client after it reconnects (subscription is lost on
     *  channel close with cleanSession=true). */
    public void resubscribe(MqttClient client) {
        subscribe(client);
    }

    private void subscribe(MqttClient client) {
        client.on(topic, buildHandler(client), qos)
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        log.error("Failed to subscribe a gateway to RPC topic {}", topic, f.cause());
                    }
                });
    }

    MqttHandler buildHandler(MqttClient client) {
        return (receivedTopic, payload) -> {
            long now = System.currentTimeMillis();
            try {
                byte[] response = processor.process(payload, now);
                if (response != null) {
                    if (responseDelayMs > 0) {
                        // Defer the response to exercise the delayed-reply case. Scheduled on the
                        // client's netty event loop, so no extra threads and no blocking of the
                        // inbound handler.
                        client.getEventLoop().schedule(
                                () -> publishResponse(client, response), responseDelayMs, TimeUnit.MILLISECONDS);
                    } else {
                        publishResponse(client, response);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to handle inbound RPC", e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    private void publishResponse(MqttClient client, byte[] response) {
        client.publish(topic, Unpooled.wrappedBuffer(response), qos)
                .addListener(f -> {
                    if (f.isSuccess()) {
                        stats.incResponsesSent();
                    } else {
                        stats.incResponseErrors();
                    }
                });
    }

    public String statsSummaryAndReset(int intervalSec) {
        return stats.summaryAndReset(intervalSec);
    }
}
