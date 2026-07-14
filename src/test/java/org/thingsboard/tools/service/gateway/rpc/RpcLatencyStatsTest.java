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
        s.incRecovered();
        s.incLost();
        s.incRetryQueued();
        assertThat(s.getResponsesSent()).isEqualTo(2);
        assertThat(s.getRecovered()).isEqualTo(1);
        assertThat(s.getLost()).isEqualTo(1);
        assertThat(s.getRetryQueued()).isEqualTo(1);
    }

    @Test
    void summaryAndResetClearsState() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.recordLatency(150);
        s.incResponsesSent();
        String line = s.summaryAndReset(60);
        assertThat(line).contains("measured 1 RPCs").contains("responses sent 1");
        assertThat(s.getCount()).isEqualTo(0);
        assertThat(s.getResponsesSent()).isEqualTo(0);
    }

    @Test
    void summaryOnEmptyDoesNotThrow() {
        RpcLatencyStats s = new RpcLatencyStats();
        assertThat(s.summaryAndReset(60)).contains("measured 0 RPCs");
    }

    @Test
    void incReceivedStampsTotalAndLastInbound() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(12345L);
        s.incReceived(67890L);
        assertThat(s.getReceivedTotal()).isEqualTo(2);
        assertThat(s.getLastInboundMs()).isEqualTo(67890L);
    }

    @Test
    void cumulativeTotalsSurviveIntervalReset() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.incResponsesSent();
        s.incRecovered();
        s.incLost();
        s.summaryAndReset(60); // clears interval counters, not cumulative
        assertThat(s.getReceivedTotal()).isEqualTo(1);
        assertThat(s.getRespondedTotal()).isEqualTo(3); // sent + recovered + lost
        assertThat(s.getResponsesSent()).isEqualTo(0);  // interval counter did reset
    }

    @Test
    void summaryIncludesRunningTotals() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.recordLatency(150);
        s.incResponsesSent();
        String line = s.summaryAndReset(10);
        assertThat(line).contains("totals: received 1, sent 1, recovered 0, lost 0, pending 0");
    }

    @Test
    void drainSummaryRendersQuiescedAndCapped() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incResponsesSent();
        String ok = s.drainSummary(6200L, true);
        assertThat(ok).contains("drained 6.2s").contains("quiesced=true")
                .contains("received total 2").contains("sent 1")
                .contains("recovered 0").contains("lost 0").contains("pending 1");
        String capped = s.drainSummary(15000L, false);
        assertThat(capped).contains("drained 15.0s").contains("quiesced=false");
    }
}
