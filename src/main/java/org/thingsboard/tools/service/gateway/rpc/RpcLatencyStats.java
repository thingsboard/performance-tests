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
 * Gateway RPC counters, reported as three lines by MQTT direction: subscription health, inbound
 * deliveries ({@code RPC In}), and our reply publishes ({@code RPC Out}). Window counters use
 * {@code getAndSet(0)} deltas; cumulative totals persist for the run. {@code pending} is not held
 * here — it is the outstanding-set size (distinct un-acked RPCs), passed in by the receiver so
 * redeliveries never distort it.
 */
public class RpcLatencyStats {

    private final SynchronizedDescriptiveStatistics latency = new SynchronizedDescriptiveStatistics();

    // --- receive (window) ---
    private final AtomicLong received = new AtomicLong();     // raw per-delivery (incl. server redeliveries)
    private final AtomicLong redelivered = new AtomicLong();  // deliveries of an already-known (device,requestId)

    // --- ack: our reply confirmed by a broker PUBACK (window). Both feed replyAcked = ackedFirstTry + ackedAfterRetry. ---
    private final AtomicLong ackedFirstTry = new AtomicLong();   // reply acked on the first publish attempt
    private final AtomicLong ackedAfterRetry = new AtomicLong(); // reply acked on a retry (orphan/reconnect re-send)

    // --- publish trouble + redelivery re-answers (window) ---
    private final AtomicLong bufferedForRetry = new AtomicLong(); // reply not acked -> buffered to re-send (transient)
    private final AtomicLong undelivered = new AtomicLong();      // reply given up: past TTL / over-cap / drain end
    private final AtomicLong redeliveryReplied = new AtomicLong(); // best-effort reply re-published to a server redelivery

    // --- subscribe health (window): observe-only, no app retry (netty retransmits; we resubscribe per reconnect).
    // 'unconfirmed' is NOT a counter here — it is a live gauge (clients not currently SUBACK-confirmed),
    // passed into subscriptionSummary, so a slow-but-real SUBACK never becomes a false positive. ---
    private final AtomicLong subscribeAcked = new AtomicLong();       // SUBACK-confirmed (per window)
    private final AtomicLong subscribeFailed = new AtomicLong();      // future failed (e.g. max retransmissions)

    // --- cumulative totals (never reset) ---
    private final AtomicLong receivedTotal = new AtomicLong();
    private final AtomicLong ackedFirstTryTotal = new AtomicLong();
    private final AtomicLong ackedAfterRetryTotal = new AtomicLong();
    private final AtomicLong undeliveredTotal = new AtomicLong();
    private final AtomicLong redeliveryRepliedTotal = new AtomicLong();
    // Wall-clock (ms) of the most recent inbound RPC; the quiescence signal read by drain().
    private volatile long lastInboundMs = 0L;

    public void recordLatency(long latencyMs) {
        latency.addValue(latencyMs);
    }

    /** Count an inbound RPC (every delivery, even parse failures) and refresh the quiescence timestamp. */
    public void incReceived(long nowMs) {
        received.incrementAndGet();
        receivedTotal.incrementAndGet();
        lastInboundMs = nowMs;
    }

    /** A delivery of a request-id already seen — the server redelivering a still-pending RPC. */
    public void incRedelivered() { redelivered.incrementAndGet(); }

    /** Reply acked (PUBACK) on the first publish attempt. */
    public void incAckedFirstTry() { ackedFirstTry.incrementAndGet(); ackedFirstTryTotal.incrementAndGet(); }
    /** Reply acked (PUBACK) on a retry, after the first attempt did not confirm. */
    public void incAckedAfterRetry() { ackedAfterRetry.incrementAndGet(); ackedAfterRetryTotal.incrementAndGet(); }
    /** Reply given up (past TTL / over the retry cap / still buffered at drain end). */
    public void incUndelivered() { undelivered.incrementAndGet(); undeliveredTotal.incrementAndGet(); }
    /** Reply not acked and buffered to be re-sent (transient — later becomes ackedAfterRetry or undelivered;
     *  window-only, since a cumulative count of a transient state would double-count entries that resolve). */
    public void incBufferedForRetry() { bufferedForRetry.incrementAndGet(); }
    /** A reply re-published in answer to a server redelivery. Its own diagnostic signal — deliberately
     *  NOT folded into acked: re-answering a redelivery is not a new distinct RPC completed. */
    public void incRedeliveryReplied() { redeliveryReplied.incrementAndGet(); redeliveryRepliedTotal.incrementAndGet(); }
    public void incSubscribeAcked() { subscribeAcked.incrementAndGet(); }
    public void incSubscribeFailed() { subscribeFailed.incrementAndGet(); }

    public long getCount() { return latency.getN(); }
    public double getMean() { return latency.getMean(); }
    public double getPercentile(double p) { return latency.getPercentile(p); }
    public double getMax() { return latency.getMax(); }
    public long getReceived() { return received.get(); }
    public long getRedelivered() { return redelivered.get(); }
    public long getAckedFirstTry() { return ackedFirstTry.get(); }
    public long getAckedAfterRetry() { return ackedAfterRetry.get(); }
    public long getUndelivered() { return undelivered.get(); }
    public long getBufferedForRetry() { return bufferedForRetry.get(); }
    public long getRedeliveryReplied() { return redeliveryReplied.get(); }
    public long getLastInboundMs() { return lastInboundMs; }
    public long getReceivedTotal() { return receivedTotal.get(); }
    public long getSubscribeAcked() { return subscribeAcked.get(); }
    public long getSubscribeFailed() { return subscribeFailed.get(); }

    /** {@code v1/gateway/rpc} (re)subscribe health — the RPC delivery channel. {@code unconfirmed} is a
     *  live gauge (clients whose current subscription is not SUBACK-confirmed), supplied by the caller. */
    public String subscriptionSummary(int intervalSec, long unconfirmed) {
        return String.format(
                "RPC Subscription [window %ds]: acked=%d, failed=%d, unconfirmed=%d",
                intervalSec, subscribeAcked.getAndSet(0), subscribeFailed.getAndSet(0), unconfirmed);
    }

    /**
     * Inbound line (server → us): raw {@code received}, {@code unique} and {@code redelivered} (server
     * re-sends of a still-pending RPC; {@code unique = received − redelivered}), and one-way
     * delivery-latency percentiles. Resets the histogram. A latency sample landing between the
     * {@code getN()} snapshot and {@code clear()} is dropped — acceptable for interval metrics.
     */
    public synchronized String inSummary(int intervalSec) {
        long n = latency.getN();
        long rcv = received.getAndSet(0);
        long redel = redelivered.getAndSet(0);
        String line = String.format(
                "RPC In [window %ds]: received=%d (unique=%d, redelivered=%d); "
                        // one-way server->gateway delivery latency (receiveTs - sendTs); clock-skew
                        // dependent between the TB host and this host, NOT round-trip / server-completion.
                        + "latency(1-way srv->gw) avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f ms",
                intervalSec, rcv, rcv - redel, redel,
                n > 0 ? latency.getMean() : 0.0,
                n > 0 ? latency.getPercentile(50) : 0.0,
                n > 0 ? latency.getPercentile(95) : 0.0,
                n > 0 ? latency.getPercentile(99) : 0.0,
                n > 0 ? latency.getMax() : 0.0);
        latency.clear();
        return line;
    }

    /**
     * Outbound line (us → server): the full reply lifecycle in one place. Window: our reply publishes that
     * the broker PUBACK'd on the first attempt ({@code firstTry}) or on a retry ({@code afterRetry}),
     * best-effort re-replies to server redeliveries ({@code redeliveryReplied}), and the delivery-trouble
     * states ({@code bufferedForRetry} transient, {@code undelivered} terminal). Totals: cumulative
     * {@code replyAcked = firstTry + afterRetry} (distinct RPCs whose reply we confirmed — the PUBACK of
     * OUR reply, distinct from the RPC Subscription SUBACK), the outstanding-set {@code pending} (passed
     * in), cumulative {@code undelivered}, and cumulative {@code redeliveryReplied} (its own signal —
     * NEVER folded into {@code replyAcked}, since re-answering a redelivery is not a new distinct RPC).
     * Outcome identity: {@code unique = replyAcked + pending + undelivered}.
     */
    public String outSummary(int intervalSec, long pending) {
        return String.format(
                "RPC Out [window %ds]: firstTry=%d, afterRetry=%d, redeliveryReplied=%d, bufferedForRetry=%d, "
                        + "undelivered=%d | totals: replyAcked=%d, pending=%d, undelivered=%d, redeliveryReplied=%d",
                intervalSec, ackedFirstTry.getAndSet(0), ackedAfterRetry.getAndSet(0), redeliveryReplied.getAndSet(0),
                bufferedForRetry.getAndSet(0), undelivered.getAndSet(0),
                ackedFirstTryTotal.get() + ackedAfterRetryTotal.get(), pending, undeliveredTotal.get(),
                redeliveryRepliedTotal.get());
    }

    /** One-line summary emitted once when the drain phase ends. {@code pending} = outstanding-set size
     *  (distinct un-acked RPCs); reaches 0 on a clean run. */
    public String drainSummary(long elapsedMs, boolean quiesced, long pending) {
        return String.format(
                "Gateway RPC drain complete [drained %.1fs, quiesced=%b]: received total %d, "
                        + "replyAcked %d, undelivered %d, pending %d",
                elapsedMs / 1000.0, quiesced, receivedTotal.get(),
                ackedFirstTryTotal.get() + ackedAfterRetryTotal.get(), undeliveredTotal.get(), pending);
    }
}
