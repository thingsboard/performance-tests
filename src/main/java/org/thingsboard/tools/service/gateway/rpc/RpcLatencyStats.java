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

public class RpcLatencyStats {

    private final SynchronizedDescriptiveStatistics latency = new SynchronizedDescriptiveStatistics();
    private final AtomicLong responsesSent = new AtomicLong();
    private final AtomicLong responseErrors = new AtomicLong();

    // Cumulative counters — never reset by summaryAndReset (unlike the interval counters above).
    private final AtomicLong receivedTotal = new AtomicLong();
    private final AtomicLong responsesSentTotal = new AtomicLong();
    private final AtomicLong responseErrorsTotal = new AtomicLong();
    // Wall-clock (ms) of the most recent inbound RPC; the quiescence signal read by drain().
    private volatile long lastInboundMs = 0L;

    public void recordLatency(long latencyMs) {
        latency.addValue(latencyMs);
    }

    /** Count an inbound RPC (every message, even parse failures) and refresh the quiescence timestamp. */
    public void incReceived(long nowMs) {
        receivedTotal.incrementAndGet();
        lastInboundMs = nowMs;
    }

    public void incResponsesSent() { responsesSent.incrementAndGet(); responsesSentTotal.incrementAndGet(); }
    public void incResponseErrors() { responseErrors.incrementAndGet(); responseErrorsTotal.incrementAndGet(); }

    public long getCount() { return latency.getN(); }
    public double getMean() { return latency.getMean(); }
    public double getPercentile(double p) { return latency.getPercentile(p); }
    public double getMax() { return latency.getMax(); }
    public long getResponsesSent() { return responsesSent.get(); }
    public long getResponseErrors() { return responseErrors.get(); }
    public long getLastInboundMs() { return lastInboundMs; }
    public long getReceivedTotal() { return receivedTotal.get(); }
    public long getRespondedTotal() { return responsesSentTotal.get() + responseErrorsTotal.get(); }

    /**
     * Render a one-line summary and reset the histogram + counters for the next interval.
     * Note: {@code recordLatency} adds values without holding this monitor, so a sample landing
     * between the {@code getN()} snapshot and {@code clear()} below is dropped from the report —
     * acceptable for interval metrics on a load test (at most a few in-flight samples per interval).
     */
    public synchronized String summaryAndReset(int intervalSec) {
        long n = latency.getN();
        String line = String.format(
                "Gateway RPC stats [window %ds]: measured %d RPCs; one-way delivery latency: "
                        + "avg %.1f ms, p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms; responses sent %d, errors %d"
                        + "; totals: received %d, responded %d, errors %d",
                intervalSec, n,
                n > 0 ? latency.getMean() : 0.0,
                n > 0 ? latency.getPercentile(50) : 0.0,
                n > 0 ? latency.getPercentile(95) : 0.0,
                n > 0 ? latency.getPercentile(99) : 0.0,
                n > 0 ? latency.getMax() : 0.0,
                responsesSent.getAndSet(0), responseErrors.getAndSet(0),
                receivedTotal.get(), responsesSentTotal.get(), responseErrorsTotal.get());
        latency.clear();
        return line;
    }

    /** One-line summary emitted once when the drain phase ends. {@code responded} counts successful
     *  replies only; {@code pending} = received minus (sent + errors) = replies never published. */
    public String drainSummary(long elapsedMs, boolean quiesced) {
        long received = receivedTotal.get();
        long sent = responsesSentTotal.get();
        long errors = responseErrorsTotal.get();
        long pending = received - sent - errors;
        return String.format(
                "Gateway RPC drain complete [drained %.1fs, quiesced=%b]: received total %d, "
                        + "responded total %d, errors %d, pending %d",
                elapsedMs / 1000.0, quiesced, received, sent, errors, pending);
    }
}
