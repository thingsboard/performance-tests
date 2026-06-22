package org.thingsboard.tools.service.gateway.rpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RpcLatencyStatsTest {

    @Test
    void recordsCountMeanMax() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.recordLatency(100);
        s.recordLatency(200);
        s.recordLatency(300);
        assertThat(s.getCount()).isEqualTo(3);
        assertThat(s.getMean()).isCloseTo(200.0, within(0.001));
        assertThat(s.getMax()).isCloseTo(300.0, within(0.001));
    }

    @Test
    void computesPercentiles() {
        RpcLatencyStats s = new RpcLatencyStats();
        for (int i = 1; i <= 100; i++) {
            s.recordLatency(i);
        }
        assertThat(s.getPercentile(50)).isCloseTo(50.5, within(1.0));
        assertThat(s.getPercentile(95)).isCloseTo(95.5, within(1.0));
    }

    @Test
    void countersIncrement() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incResponsesSent();
        s.incResponsesSent();
        s.incResponseErrors();
        assertThat(s.getResponsesSent()).isEqualTo(2);
        assertThat(s.getResponseErrors()).isEqualTo(1);
    }

    @Test
    void summaryAndResetClearsState() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.recordLatency(150);
        s.incResponsesSent();
        String line = s.summaryAndReset(60);
        assertThat(line).contains("latencySamples=1").contains("responsesSent=1");
        assertThat(s.getCount()).isEqualTo(0);
        assertThat(s.getResponsesSent()).isEqualTo(0);
    }

    @Test
    void summaryOnEmptyDoesNotThrow() {
        RpcLatencyStats s = new RpcLatencyStats();
        assertThat(s.summaryAndReset(60)).contains("latencySamples=0");
    }
}
