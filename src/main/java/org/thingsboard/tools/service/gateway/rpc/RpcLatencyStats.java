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
 * Gateway RPC counters, reported as three stage lines (subscription / receive / publish). Window
 * counters use {@code getAndSet(0)} deltas; cumulative totals persist for the run. {@code pending}
 * is not held here — it is the outstanding-set size (distinct unanswered RPCs), passed in by the
 * receiver so duplicates never distort it.
 */
public class RpcLatencyStats {

    private final SynchronizedDescriptiveStatistics latency = new SynchronizedDescriptiveStatistics();

    // --- receive (window) ---
    private final AtomicLong received = new AtomicLong();   // raw per-delivery (incl. server redeliveries)
    private final AtomicLong duplicate = new AtomicLong();  // deliveries of an already-known (device,requestId)

    // --- publish (window) ---
    private final AtomicLong responsesSent = new AtomicLong(); // first-try deliveries
    private final AtomicLong recovered = new AtomicLong();     // delivered on a retry after reconnect
    private final AtomicLong lost = new AtomicLong();          // expired / over-cap / never reconnected
    private final AtomicLong retryQueued = new AtomicLong();   // buffered for retry (informational, not terminal)

    // --- subscribe health (window): observe-only, no app retry (netty retransmits; we resubscribe per reconnect) ---
    private final AtomicLong subscribeAcked = new AtomicLong();       // SUBACK-confirmed
    private final AtomicLong subscribeFailed = new AtomicLong();      // future failed (e.g. max retransmissions)
    private final AtomicLong subscribeUnconfirmed = new AtomicLong(); // no SUBACK within the ack timeout (orphan)

    // --- cumulative totals (never reset) ---
    private final AtomicLong receivedTotal = new AtomicLong();
    private final AtomicLong responsesSentTotal = new AtomicLong();
    private final AtomicLong recoveredTotal = new AtomicLong();
    private final AtomicLong lostTotal = new AtomicLong();
    private final AtomicLong retryQueuedTotal = new AtomicLong();
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

    /** A delivery of a request-id already seen (server redelivery on reconnect). */
    public void incDuplicate() { duplicate.incrementAndGet(); }

    public void incResponsesSent() { responsesSent.incrementAndGet(); responsesSentTotal.incrementAndGet(); }
    public void incRecovered() { recovered.incrementAndGet(); recoveredTotal.incrementAndGet(); }
    public void incLost() { lost.incrementAndGet(); lostTotal.incrementAndGet(); }
    public void incRetryQueued() { retryQueued.incrementAndGet(); retryQueuedTotal.incrementAndGet(); }
    public void incSubscribeAcked() { subscribeAcked.incrementAndGet(); }
    public void incSubscribeFailed() { subscribeFailed.incrementAndGet(); }
    public void incSubscribeUnconfirmed() { subscribeUnconfirmed.incrementAndGet(); }

    public long getCount() { return latency.getN(); }
    public double getMean() { return latency.getMean(); }
    public double getPercentile(double p) { return latency.getPercentile(p); }
    public double getMax() { return latency.getMax(); }
    public long getReceived() { return received.get(); }
    public long getDuplicate() { return duplicate.get(); }
    public long getResponsesSent() { return responsesSent.get(); }
    public long getRecovered() { return recovered.get(); }
    public long getLost() { return lost.get(); }
    public long getRetryQueued() { return retryQueued.get(); }
    public long getLastInboundMs() { return lastInboundMs; }
    public long getReceivedTotal() { return receivedTotal.get(); }
    public long getSubscribeAcked() { return subscribeAcked.get(); }
    public long getSubscribeFailed() { return subscribeFailed.get(); }
    public long getSubscribeUnconfirmed() { return subscribeUnconfirmed.get(); }

    /** {@code v1/gateway/rpc} (re)subscribe health — the RPC delivery channel. */
    public String subscriptionSummary(int intervalSec) {
        return String.format(
                "RPC Subscription [window %ds]: acked=%d, failed=%d, unconfirmed=%d",
                intervalSec, subscribeAcked.getAndSet(0), subscribeFailed.getAndSet(0), subscribeUnconfirmed.getAndSet(0));
    }

    /**
     * Inbound-command line: raw {@code received}, {@code duplicate} (redeliveries; {@code unique =
     * received − duplicate}), and one-way delivery-latency percentiles. Resets the histogram.
     * A latency sample landing between the {@code getN()} snapshot and {@code clear()} is dropped —
     * acceptable for interval metrics.
     */
    public synchronized String receiveSummary(int intervalSec) {
        long n = latency.getN();
        long rcv = received.getAndSet(0);
        long dup = duplicate.getAndSet(0);
        String line = String.format(
                "RPC Receive [window %ds]: received=%d, duplicate=%d (unique=%d); "
                        + "latency avg=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f ms",
                intervalSec, rcv, dup, rcv - dup,
                n > 0 ? latency.getMean() : 0.0,
                n > 0 ? latency.getPercentile(50) : 0.0,
                n > 0 ? latency.getPercentile(95) : 0.0,
                n > 0 ? latency.getPercentile(99) : 0.0,
                n > 0 ? latency.getMax() : 0.0);
        latency.clear();
        return line;
    }

    /**
     * Reply-publish line: window {@code sent/recovered/lost/retryQueued}, plus cumulative
     * {@code answered} (= sent + recovered), the outstanding-set {@code pending} (passed in), and the
     * run-total {@code retryQueued} so retry activity stays legible outside the roll window.
     */
    public String publishSummary(int intervalSec, long pending) {
        return String.format(
                "RPC Publish [window %ds]: sent=%d, recovered=%d, lost=%d, retryQueued=%d "
                        + "| totals: answered=%d, pending=%d, retryQueued=%d",
                intervalSec, responsesSent.getAndSet(0), recovered.getAndSet(0), lost.getAndSet(0), retryQueued.getAndSet(0),
                responsesSentTotal.get() + recoveredTotal.get(), pending, retryQueuedTotal.get());
    }

    /** One-line summary emitted once when the drain phase ends. {@code pending} = outstanding-set size
     *  (distinct unanswered RPCs); reaches 0 on a clean run. */
    public String drainSummary(long elapsedMs, boolean quiesced, long pending) {
        return String.format(
                "Gateway RPC drain complete [drained %.1fs, quiesced=%b]: received total %d, "
                        + "answered %d, lost %d, pending %d",
                elapsedMs / 1000.0, quiesced, receivedTotal.get(),
                responsesSentTotal.get() + recoveredTotal.get(), lostTotal.get(), pending);
    }
}
