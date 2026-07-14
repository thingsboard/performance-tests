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
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttHandler;
import org.thingsboard.tools.service.gateway.rpc.RpcMessageProcessor.ProcessedRpc;
import org.thingsboard.tools.service.gateway.rpc.RpcOutstandingTracker.RpcKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class GatewayRpcReceiver {

    private final String topic;
    private final MqttQoS qos;
    private final RpcMessageProcessor processor;
    private final RpcLatencyStats stats;
    private final long responseDelayMs;

    // Reply retry-on-reconnect: a reply that is not confirmed sent (publish fails OR its future never
    // completes — the netty-mqtt orphan; see TODO below) is buffered per client and re-published when
    // that client reconnects, up to the RPC's server-side expiry.
    private final boolean retryEnabled;
    private final long replyTtlMs;
    private final int maxBufferedPerClient;
    // Deadline for a reply publish's PUBACK: if the future has not completed by then, treat it as an
    // orphan (netty-mqtt never completes a publish whose channel closed mid-flight) and re-buffer it.
    // Subscribe does NOT use this — its 'unconfirmed' is an observe-only live gauge (see subscribe()).
    private final long ackTimeoutMs;

    private final Map<MqttClient, ClientRetryBuffer> retryBuffers = new ConcurrentHashMap<>();
    // One stable inbound handler per client, reused across every (re)subscribe. netty-mqtt keys pending
    // handlers by (handler, once) record-equality, so re-subscribing with the SAME instance dedups; a
    // fresh handler per resubscribe would register twice and double-deliver every RPC.
    private final Map<MqttClient, MqttHandler> handlers = new ConcurrentHashMap<>();
    // Per-unique-RPC accounting; pending = outstanding.size(), immune to redelivery duplicates.
    private final RpcOutstandingTracker outstanding = new RpcOutstandingTracker();
    // Clients whose current v1/gateway/rpc subscription is not (yet) SUBACK-confirmed — a live gauge,
    // so a slow-but-real SUBACK is never a false positive (it self-clears whenever the SUBACK lands).
    private final java.util.Set<MqttClient> unconfirmedSubscriptions = ConcurrentHashMap.newKeySet();

    private static final long DRAIN_POLL_MS = 500L;

    /** Legacy constructor: reply retry disabled (a not-confirmed reply is immediately counted lost). */
    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, long responseDelayMs) {
        this(topic, qos, processor, stats, responseDelayMs, false, 0L, 0, 5000L);
    }

    public GatewayRpcReceiver(String topic, MqttQoS qos, RpcMessageProcessor processor,
                              RpcLatencyStats stats, long responseDelayMs,
                              boolean retryEnabled, long replyTtlMs, int maxBufferedPerClient, long ackTimeoutMs) {
        this.topic = topic;
        this.qos = qos;
        this.processor = processor;
        this.stats = stats;
        this.responseDelayMs = responseDelayMs;
        this.retryEnabled = retryEnabled;
        this.replyTtlMs = replyTtlMs;
        this.maxBufferedPerClient = maxBufferedPerClient;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public void attach(List<MqttClient> clients) {
        for (MqttClient client : clients) {
            subscribe(client);
        }
        log.info("Subscribed {} gateways to RPC topic {}", clients.size(), topic);
    }

    /** Re-issue the RPC-topic subscription for one client after it reconnects (netty-mqtt clears all
     *  subscriptions on channel close and does NOT auto-restore them). */
    public void resubscribe(MqttClient client) {
        subscribe(client);
    }

    /**
     * Subscribe (observe-only, no app-level retry). Reliability comes from netty-mqtt's own SUBSCRIBE
     * retransmission on a live channel plus our per-reconnect resubscribe; here we only confirm the
     * SUBACK and record health. A retry loop would risk registering a second subscription (double
     * delivery), so it is intentionally absent — a subscribe that does not confirm is re-issued on the
     * next reconnect. {@code unconfirmed} is tracked as a live set (not a timeout counter) so a
     * slow-but-real SUBACK never becomes a false positive.
     */
    private void subscribe(MqttClient client) {
        MqttHandler handler = handlers.computeIfAbsent(client, this::buildHandler);
        unconfirmedSubscriptions.add(client); // cleared when (if) the SUBACK arrives
        client.on(topic, handler, qos).addListener(fut -> {
            if (fut.isSuccess()) {
                unconfirmedSubscriptions.remove(client);
                stats.incSubscribeAcked();
            } else {
                stats.incSubscribeFailed(); // stays unconfirmed until a later resubscribe confirms it
                log.error("RPC subscribe failed on topic {}", topic, fut.cause());
            }
        });
    }

    MqttHandler buildHandler(MqttClient client) {
        return (receivedTopic, payload) -> {
            long now = System.currentTimeMillis();
            try {
                stats.incReceived(now); // raw, every delivery (incl. redeliveries and unparseable)
                ProcessedRpc r = processor.process(payload, now);
                if (r == null) {
                    return CompletableFuture.completedFuture(null); // malformed — cannot key/dedup
                }
                if (r.reply() != null) {
                    RpcKey key = new RpcKey(r.deviceName(), r.requestId());
                    if (!outstanding.firstReceipt(key, now)) {
                        // Server redelivery of a still-pending RPC on reconnect. The reply-retry flush
                        // re-sends it at that same reconnect, so just count it — no re-answer, no re-track.
                        stats.incDuplicate();
                        return CompletableFuture.completedFuture(null);
                    }
                    if (r.latencyMs() != null) {
                        stats.recordLatency(r.latencyMs());
                    }
                    BufferedReply reply = new BufferedReply(r.reply(), now + replyTtlMs, key);
                    if (responseDelayMs > 0) {
                        // Defer the response to exercise the delayed-reply case. On the client's netty
                        // event loop: no extra threads, inbound handler not blocked.
                        client.getEventLoop().schedule(
                                () -> publishReply(client, reply), responseDelayMs, TimeUnit.MILLISECONDS);
                    } else {
                        publishReply(client, reply);
                    }
                } else if (r.latencyMs() != null) {
                    stats.recordLatency(r.latencyMs()); // respond disabled: measure latency, nothing to track
                }
            } catch (Exception e) {
                log.warn("Failed to handle inbound RPC", e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Publish (or re-publish) one reply. Success on the broker PUBACK: first attempt counts {@code sent},
     * a retry counts {@code recovered}, and the RPC is marked answered. If the publish fails OR its
     * future never completes within {@code ackTimeoutMs} (the netty-mqtt orphan — see TODO), it is
     * treated as not-confirmed-sent and routed to the retry buffer. Exactly-once via {@code settled}.
     */
    private void publishReply(MqttClient client, BufferedReply reply) {
        reply.attempts++;
        AtomicBoolean settled = new AtomicBoolean();
        Future<Void> f = client.publish(topic, Unpooled.wrappedBuffer(reply.payload), qos);
        // TODO(netty-mqtt): a QoS-1 publish whose bytes flushed before the channel dropped is never
        // completed by netty-mqtt (lambda$connect$3 stops its retransmit + clears pendingPublishes,
        // without failing the promise). The proper fix is to tryFailure() those promises on close in
        // netty-mqtt, then this timeout is unnecessary; deferred because it requires bumping this repo
        // onto TB deps 4.3.1.x. Until then this timeout is our workaround for the orphaned reply.
        ScheduledFuture<?> timeout = client.getEventLoop().schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                onReplyNotConfirmed(client, reply);
            }
        }, ackTimeoutMs, TimeUnit.MILLISECONDS);
        f.addListener(fut -> {
            if (fut.isSuccess()) {
                // Mark answered on ANY success — even a PUBACK that lands after the timeout already
                // fired (a slow-but-real send). markAnswered is idempotent, so this clears a
                // false-pending; we only count sent/recovered + cancel the timeout if we win the CAS
                // (a timed-out reply was already buffered — its later flush would double-count).
                outstanding.markAnswered(reply.key, System.currentTimeMillis());
                if (settled.compareAndSet(false, true)) {
                    timeout.cancel(false);
                    if (reply.attempts == 1) {
                        stats.incResponsesSent();
                    } else {
                        stats.incRecovered();
                    }
                }
            } else if (settled.compareAndSet(false, true)) {
                timeout.cancel(false);
                onReplyNotConfirmed(client, reply);
            }
        });
    }

    /** A reply that did not confirm sent (publish failed or orphaned): buffer it for retry on reconnect,
     *  unless retry is off, it has expired, or the per-client buffer is full — then it is terminally lost. */
    private void onReplyNotConfirmed(MqttClient client, BufferedReply reply) {
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

    /** Log every distinct RPC still unanswered at drain, so it can be matched to the DB EXPIRED rows. */
    public void logOutstanding() {
        List<RpcKey> keys = outstanding.outstandingKeys();
        if (keys.isEmpty()) {
            return;
        }
        log.warn("Gateway RPC: {} distinct RPC(s) unanswered at drain end:", keys.size());
        for (RpcKey k : keys) {
            log.warn("  unanswered RPC: device={} requestId={}", k.deviceName(), k.requestId());
        }
    }

    /** A reply awaiting (re)publish. {@code expiryMs} = receipt time + the RPC's server-side expiry;
     *  {@code key} identifies the RPC so its success can clear the outstanding set. */
    private static final class BufferedReply {
        final byte[] payload;
        final long expiryMs;
        final RpcKey key;
        int attempts;

        BufferedReply(byte[] payload, long expiryMs, RpcKey key) {
            this.payload = payload;
            this.expiryMs = expiryMs;
            this.key = key;
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

    // --- stats sources (registered as three StatsBlocks) ---

    public String subscriptionSummary(int intervalSec) {
        return stats.subscriptionSummary(intervalSec, unconfirmedSubscriptions.size());
    }

    public String receiveSummary(int intervalSec) {
        outstanding.evictAnsweredOlderThan(System.currentTimeMillis(), replyTtlMs); // bound memory
        return stats.receiveSummary(intervalSec);
    }

    public String publishSummary(int intervalSec) {
        return stats.publishSummary(intervalSec, outstanding.outstandingCount());
    }

    /**
     * After the load window ends and the burst sender is stopped, wait for the last burst's in-flight
     * RPCs to settle. Observation only — responses flow asynchronously via the publish listener; this
     * loop watches the outstanding set. Exits early ({@code quiesced=true}) once inbound has been idle
     * for {@code quietMs} and, when {@code respond} is true, no RPC is still outstanding; or at
     * {@code maxMs} ({@code quiesced=false}) so it can never hang.
     */
    public DrainResult drain(long quietMs, long maxMs, boolean respond) {
        long start = System.currentTimeMillis();
        long deadline = start + maxMs;
        while (true) {
            long now = System.currentTimeMillis();
            long idle = now - stats.getLastInboundMs();
            long pending = respond ? outstanding.outstandingCount() : 0;
            if (idle >= quietMs && pending <= 0) {
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
        return stats.drainSummary(elapsedMs, quiesced, outstanding.outstandingCount());
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
