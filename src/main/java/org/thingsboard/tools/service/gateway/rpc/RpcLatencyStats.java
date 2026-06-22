package org.thingsboard.tools.service.gateway.rpc;

import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;

import java.util.concurrent.atomic.AtomicLong;

public class RpcLatencyStats {

    private final SynchronizedDescriptiveStatistics latency = new SynchronizedDescriptiveStatistics();
    private final AtomicLong responsesSent = new AtomicLong();
    private final AtomicLong responseErrors = new AtomicLong();

    public void recordLatency(long latencyMs) {
        latency.addValue(latencyMs);
    }

    public void incResponsesSent() { responsesSent.incrementAndGet(); }
    public void incResponseErrors() { responseErrors.incrementAndGet(); }

    public long getCount() { return latency.getN(); }
    public double getMean() { return latency.getMean(); }
    public double getPercentile(double p) { return latency.getPercentile(p); }
    public double getMax() { return latency.getMax(); }
    public long getResponsesSent() { return responsesSent.get(); }
    public long getResponseErrors() { return responseErrors.get(); }

    /** Render a one-line summary and reset the histogram + counters for the next interval. */
    public synchronized String summaryAndReset(int intervalSec) {
        long n = latency.getN();
        double rate = intervalSec > 0 ? (double) n / intervalSec : 0.0;
        String line = String.format(
                "Gateway RPC stats: latencySamples=%d (%.1f/s), latency ms[mean=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f], "
                        + "responsesSent=%d, responseErrors=%d",
                n, rate,
                n > 0 ? latency.getMean() : 0.0,
                n > 0 ? latency.getPercentile(50) : 0.0,
                n > 0 ? latency.getPercentile(95) : 0.0,
                n > 0 ? latency.getPercentile(99) : 0.0,
                n > 0 ? latency.getMax() : 0.0,
                responsesSent.getAndSet(0), responseErrors.getAndSet(0));
        latency.clear();
        return line;
    }
}
