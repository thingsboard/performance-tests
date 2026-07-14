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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GatewayRpcReceiver {

    private final String topic;
    private final MqttQoS qos;
    private final RpcMessageProcessor processor;
    private final RpcLatencyStats stats;
    private final long responseDelayMs;

    // Reply retry-on-reconnect: a reply whose publish fails (channel dropped mid-reply) is buffered
    // per client and re-published when that client reconnects, up to the RPC's server-side expiry.
    private final boolean retryEnabled;
    private final long replyTtlMs;
    private final int maxBufferedPerClient;
    private final Map<MqttClient, ClientRetryBuffer> retryBuffers = new ConcurrentHashMap<>();

    private static final long DRAIN_POLL_MS = 500L;

    /** Legacy constructor: reply retry disabled (a failed reply is immediately counted lost). */
    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, long responseDelayMs) {
        this(topic, qos, processor, stats, responseDelayMs, false, 0L, 0);
    }

    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, long responseDelayMs,
                              boolean retryEnabled, long replyTtlMs, int maxBufferedPerClient) {
        this.topic = topic;
        this.qos = qos;
        this.processor = processor;
        this.stats = stats;
        this.responseDelayMs = responseDelayMs;
        this.retryEnabled = retryEnabled;
        this.replyTtlMs = replyTtlMs;
        this.maxBufferedPerClient = maxBufferedPerClient;
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
                    BufferedReply reply = new BufferedReply(response, now + replyTtlMs);
                    if (responseDelayMs > 0) {
                        // Defer the response to exercise the delayed-reply case. Scheduled on the
                        // client's netty event loop, so no extra threads and no blocking of the
                        // inbound handler.
                        client.getEventLoop().schedule(
                                () -> publishReply(client, reply), responseDelayMs, TimeUnit.MILLISECONDS);
                    } else {
                        publishReply(client, reply);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to handle inbound RPC", e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    /** Publish (or re-publish) one reply. First-try success counts sent, a retry success counts
     *  recovered; any failure routes to {@link #enqueueForRetry} (buffer or lost). */
    private void publishReply(MqttClient client, BufferedReply reply) {
        reply.attempts++;
        client.publish(topic, Unpooled.wrappedBuffer(reply.payload), qos)
                .addListener(f -> {
                    if (f.isSuccess()) {
                        if (reply.attempts == 1) {
                            stats.incResponsesSent();
                        } else {
                            stats.incRecovered();
                        }
                    } else {
                        enqueueForRetry(client, reply);
                    }
                });
    }

    /** Buffer a failed reply for retry on reconnect, unless retry is off, it has expired, or the
     *  per-client buffer is full — in which case it is terminally lost. */
    private void enqueueForRetry(MqttClient client, BufferedReply reply) {
        if (!retryEnabled || System.currentTimeMillis() >= reply.expiryMs) {
            stats.incLost();
            return;
        }
        ClientRetryBuffer buf = retryBuffers.computeIfAbsent(client, c -> new ClientRetryBuffer(maxBufferedPerClient));
        if (buf.offer(reply)) {
            stats.incRetryQueued();
        } else {
            stats.incLost();
        }
    }

    /** Re-publish a reconnected client's buffered replies; drop + count lost any already past expiry.
     *  Hook this into the client's reconnect action. */
    public void flushReplies(MqttClient client) {
        ClientRetryBuffer buf = retryBuffers.get(client);
        if (buf == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (BufferedReply reply : buf.drainAll()) {
            if (now >= reply.expiryMs) {
                stats.incLost();
            } else {
                publishReply(client, reply);
            }
        }
    }

    /** After the drain phase: any replies still buffered (client never reconnected in time) are lost. */
    public void finalizeLostReplies() {
        for (ClientRetryBuffer buf : retryBuffers.values()) {
            for (BufferedReply ignored : buf.drainAll()) {
                stats.incLost();
            }
        }
    }

    /** A reply awaiting (re)publish. {@code expiryMs} = receipt time + the RPC's server-side timeout. */
    private static final class BufferedReply {
        final byte[] payload;
        final long expiryMs;
        int attempts;

        BufferedReply(byte[] payload, long expiryMs) {
            this.payload = payload;
            this.expiryMs = expiryMs;
        }
    }

    /** Per-client bounded buffer. Its own monitor guards O(1) offer / full-drain — short critical
     *  sections safe to run on a netty event loop; also read from the drain thread at finalize. */
    static final class ClientRetryBuffer {
        private final ArrayDeque<BufferedReply> q = new ArrayDeque<>();
        private final int cap;

        ClientRetryBuffer(int cap) {
            this.cap = cap;
        }

        synchronized boolean offer(BufferedReply r) {
            if (q.size() >= cap) {
                return false;
            }
            q.addLast(r);
            return true;
        }

        synchronized List<BufferedReply> drainAll() {
            List<BufferedReply> out = new ArrayList<>(q);
            q.clear();
            return out;
        }
    }

    public String statsSummaryAndReset(int intervalSec) {
        return stats.summaryAndReset(intervalSec);
    }

    /**
     * After the load window ends and the burst sender is stopped, wait for the last burst's in-flight
     * RPCs to settle. Observation only — responses flow asynchronously via the publish listener; this
     * loop just watches the shared stats. Exits early ({@code quiesced=true}) once inbound has been idle
     * for {@code quietMs} and, when {@code respond} is true, every received RPC has been answered; or at
     * {@code maxMs} ({@code quiesced=false}) so it can never hang.
     */
    public DrainResult drain(long quietMs, long maxMs, boolean respond) {
        long start = System.currentTimeMillis();
        long deadline = start + maxMs;
        while (true) {
            long now = System.currentTimeMillis();
            long idle = now - stats.getLastInboundMs();
            long pending = stats.getReceivedTotal() - stats.getRespondedTotal();
            if (idle >= quietMs && (!respond || pending <= 0)) {
                return new DrainResult(true, now - start);
            }
            if (now >= deadline) {
                return new DrainResult(false, now - start);
            }
            long sleep = Math.min(DRAIN_POLL_MS, deadline - now);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new DrainResult(false, System.currentTimeMillis() - start);
                }
            }
        }
    }

    public String drainSummary(long elapsedMs, boolean quiesced) {
        return stats.drainSummary(elapsedMs, quiesced);
    }

    /**
     * Resolve the drain hard-cap in milliseconds. {@code overrideSec > 0} wins. Otherwise, with the
     * in-tool sender on, derive from the RPC timeout (an RPC cannot arrive after its server-side
     * expiration = timeout): {@code senderTimeoutMs + responseDelayMs + 5s margin}. Sender off → fixed
     * 30s fallback. Always floored to {@code >= quietSec}.
     */
    public static long resolveDrainMaxMs(long overrideSec, boolean senderEnabled, long senderTimeoutMs,
                                  long responseDelayMs, long quietSec) {
        long maxMs;
        if (overrideSec > 0) {
            maxMs = overrideSec * 1000L;
        } else if (senderEnabled) {
            maxMs = senderTimeoutMs + responseDelayMs + 5000L;
        } else {
            maxMs = 30_000L;
        }
        return Math.max(maxMs, quietSec * 1000L);
    }

    public static final class DrainResult {
        public final boolean quiesced;
        public final long elapsedMs;

        DrainResult(boolean quiesced, long elapsedMs) {
            this.quiesced = quiesced;
            this.elapsedMs = elapsedMs;
        }
    }
}
