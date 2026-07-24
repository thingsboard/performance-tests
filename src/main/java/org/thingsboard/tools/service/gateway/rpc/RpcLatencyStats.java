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

import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway RPC counters, in MQTT terms, reported as three per-interval lines by direction — RPC
 * Subscription (SUBSCRIBE/SUBACK), RPC In (inbound PUBLISH), RPC Out (our reply PUBLISH + PUBACK) —
 * plus two {@code [total]} lines at end of run. Per-window counters use {@code getAndSet(0)} deltas;
 * cumulative totals persist for the run.
 *
 * <p>RPC Out lifecycle: every reply {@code publish} either gets a {@code pubAck} or {@code failed}
 * (didn't confirm) and is re-sent ({@code rePublished}); a failed reply ends the run either
 * {@code recovered} (a re-send confirmed) or {@code lost} — so {@code failed == recovered + lost} at
 * drain. The distinct-RPC pending set (used for dedup + drain quiescence) lives in
 * {@link RpcPendingTracker}; it is no longer surfaced as a stat.
 */
public class RpcLatencyStats {

    private final SynchronizedDescriptiveStatistics latency = new SynchronizedDescriptiveStatistics();

    // --- RPC In (server -> us): inbound PUBLISH ---
    private final AtomicLong received = new AtomicLong();      // window; label: publish
    private final AtomicLong redelivered = new AtomicLong();   // window; a server re-send of a still-pending command
    private final AtomicLong receivedTotal = new AtomicLong();
    private final AtomicLong redeliveredTotal = new AtomicLong();
    // Wall-clock (ms) of the most recent inbound RPC; the quiescence signal read by drain().
    private volatile long lastInboundMs = 0L;

    // --- RPC Out (us -> server): reply PUBLISH + PUBACK ---
    private final AtomicLong replyPublished = new AtomicLong(); // window; every reply PUBLISH we sent
    private final AtomicLong replyPubAcked = new AtomicLong();  // window; reply PUBLISH confirmed by PUBACK
    private final AtomicLong replyFailed = new AtomicLong();    // window; a reply's publish did not confirm (first failure)
    private final AtomicLong rePublished = new AtomicLong();    // window; a reply PUBLISH that was a re-send
    private final AtomicLong replyPublishedTotal = new AtomicLong();
    private final AtomicLong replyPubAckedTotal = new AtomicLong();
    private final AtomicLong replyFailedTotal = new AtomicLong();
    private final AtomicLong recoveredTotal = new AtomicLong(); // a previously-failed reply that a re-send delivered
    private final AtomicLong lostTotal = new AtomicLong();      // a reply given up (past TTL / over cap / drain end)

    // --- subscribe health (window): observe-only, no app retry. 'unconfirmed' is a live gauge (clients
    // not currently SUBACK-confirmed), passed into subscriptionSummary — never a false positive. ---
    private final AtomicLong subscribeAcked = new AtomicLong();
    private final AtomicLong subscribeFailed = new AtomicLong();

    public void recordLatency(long latencyMs) {
        latency.addValue(latencyMs);
    }

    /** Count a well-formed inbound RPC PUBLISH and refresh the quiescence timestamp. */
    public void incReceived(long nowMs) {
        received.incrementAndGet();
        receivedTotal.incrementAndGet();
        lastInboundMs = nowMs;
    }

    /** A delivery of a request-id already in flight — the server re-sending a still-pending command. */
    public void incRedelivered() { redelivered.incrementAndGet(); redeliveredTotal.incrementAndGet(); }

    /** One reply PUBLISH packet emitted (first send or re-send). */
    public void incReplyPublished() { replyPublished.incrementAndGet(); replyPublishedTotal.incrementAndGet(); }
    /** Our reply PUBLISH confirmed by a broker PUBACK. */
    public void incReplyPubAcked() { replyPubAcked.incrementAndGet(); replyPubAckedTotal.incrementAndGet(); }
    /** A reply's publish did not confirm — counted once per reply, on its first failure. */
    public void incReplyFailed() { replyFailed.incrementAndGet(); replyFailedTotal.incrementAndGet(); }
    /** A reply PUBLISH that was a re-send (our failed-reply retry, or answering a server re-send). Window-only. */
    public void incRePublished() { rePublished.incrementAndGet(); }
    /** A previously-failed reply that a re-send finally delivered (confirmed on attempt > 1). */
    public void incRecovered() { recoveredTotal.incrementAndGet(); }
    /** A reply given up as never delivered. */
    public void incLost() { lostTotal.incrementAndGet(); }
    public void incSubscribeAcked() { subscribeAcked.incrementAndGet(); }
    public void incSubscribeFailed() { subscribeFailed.incrementAndGet(); }

    public long getCount() { return latency.getN(); }
    public double getMean() { return latency.getMean(); }
    public double getPercentile(double p) { return latency.getPercentile(p); }
    public double getMax() { return latency.getMax(); }
    public long getReceived() { return received.get(); }
    public long getRedelivered() { return redelivered.get(); }
    public long getReplyPublished() { return replyPublished.get(); }
    public long getReplyPubAcked() { return replyPubAcked.get(); }
    public long getReplyFailed() { return replyFailed.get(); }
    public long getRePublished() { return rePublished.get(); }
    public long getRecoveredTotal() { return recoveredTotal.get(); }
    public long getLostTotal() { return lostTotal.get(); }
    public long getLastInboundMs() { return lastInboundMs; }
    public long getReceivedTotal() { return receivedTotal.get(); }
    public long getSubscribeAcked() { return subscribeAcked.get(); }
    public long getSubscribeFailed() { return subscribeFailed.get(); }

    /** One-time key, logged when the RPC receiver starts, so the field names below need no guessing. */
    public static String legend() {
        return "RPC stats key — RPC In = commands received (server->gateway); RPC Out = our replies (gateway->server).\n"
                + "  publish        MQTT PUBLISH count (In: commands in; Out: reply publishes we sent, incl. re-sends)\n"
                + "  new/redelivered   first-time command / a server re-send of a still-unanswered command\n"
                + "  pubAck         our reply PUBLISH confirmed by a broker PUBACK\n"
                + "  failed         our reply PUBLISH did not confirm (orphaned/failed) — will be re-published\n"
                + "  rePublished    a reply PUBLISH that was a re-send (our failed retry, or answering a server re-send)\n"
                + "  recovered/lost [total only] a failed reply that a re-send delivered / that was never delivered\n"
                + "  latency(1-way srv->gw)  receiveTs - sendTs; clock-skew dependent, not round-trip";
    }

    /** {@code v1/gateway/rpc} (re)subscribe health — SUBACK. {@code unconfirmed} is a live gauge supplied by the caller. */
    public String subscriptionSummary(int intervalSec, long unconfirmed) {
        return String.format(
                "RPC Subscription [window %ds]: subAck=%d, failed=%d, unconfirmed=%d",
                intervalSec, subscribeAcked.getAndSet(0), subscribeFailed.getAndSet(0), unconfirmed);
    }

    /**
     * Inbound line (server -> us): {@code publish} = inbound PUBLISHes, split {@code new} / {@code redelivered}
     * ({@code new = publish - redelivered}), and one-way delivery-latency percentiles. Resets the histogram.
     */
    public synchronized String inSummary(int intervalSec) {
        long n = latency.getN();
        long pub = received.getAndSet(0);
        long redel = redelivered.getAndSet(0);
        String line = String.format(
                "RPC In [window %ds]: publish=%d (new %d, redelivered %d); "
                        + "latency(1-way srv->gw) avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f ms",
                intervalSec, pub, pub - redel, redel,
                n > 0 ? latency.getMean() : 0.0,
                n > 0 ? latency.getPercentile(50) : 0.0,
                n > 0 ? latency.getPercentile(95) : 0.0,
                n > 0 ? latency.getPercentile(99) : 0.0,
                n > 0 ? latency.getMax() : 0.0);
        latency.clear();
        return line;
    }

    /** Outbound line (us -> server): reply {@code publish} vs {@code pubAck}, plus {@code failed} and {@code rePublished}. */
    public String outSummary(int intervalSec) {
        return String.format(
                "RPC Out [window %ds]: publish=%d, pubAck=%d, failed=%d, rePublished=%d",
                intervalSec, replyPublished.getAndSet(0), replyPubAcked.getAndSet(0),
                replyFailed.getAndSet(0), rePublished.getAndSet(0));
    }

    /** End-of-run inbound totals. */
    public String inTotalSummary() {
        long pub = receivedTotal.get();
        long redel = redeliveredTotal.get();
        return String.format("RPC In  [total]: publish=%d (new %d, redelivered %d)", pub, pub - redel, redel);
    }

    /** End-of-run outbound totals; the reconciliation: {@code failed == recovered + lost} on a settled run. */
    public String outTotalSummary() {
        return String.format(
                "RPC Out [total]: publish=%d, pubAck=%d, failed=%d, recovered=%d, lost=%d",
                replyPublishedTotal.get(), replyPubAckedTotal.get(), replyFailedTotal.get(),
                recoveredTotal.get(), lostTotal.get());
    }
}
