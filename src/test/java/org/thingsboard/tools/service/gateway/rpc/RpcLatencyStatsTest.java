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
    void ackAndPublishCountersIncrement() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incAckedFirstTry();
        s.incAckedFirstTry();
        s.incAckedAfterRetry();
        s.incUndelivered();
        s.incBufferedForRetry();
        assertThat(s.getAckedFirstTry()).isEqualTo(2);
        assertThat(s.getAckedAfterRetry()).isEqualTo(1);
        assertThat(s.getUndelivered()).isEqualTo(1);
        assertThat(s.getBufferedForRetry()).isEqualTo(1);
    }

    @Test
    void incReceivedStampsTotalAndLastInbound() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(12345L);
        s.incReceived(67890L);
        assertThat(s.getReceivedTotal()).isEqualTo(2);
        assertThat(s.getReceived()).isEqualTo(2);
        assertThat(s.getLastInboundMs()).isEqualTo(67890L);
    }

    @Test
    void inSummaryShowsReceivedUniqueRedeliveredAndResetsWindow() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incRedelivered(); // one of the three was a server redelivery
        s.recordLatency(150);
        assertThat(s.inSummary(10)).isEqualTo(
                "RPC In [window 10s]: received=3 (unique=2, redelivered=1); "
                        + "latency(1-way srv->gw) avg=150.0 p50=150.0 p95=150.0 p99=150.0 max=150.0 ms");
        // window counters reset; totals persist
        assertThat(s.getReceived()).isZero();
        assertThat(s.getReceivedTotal()).isEqualTo(3);
        assertThat(s.getCount()).isZero();
    }

    @Test
    void inSummaryOnEmptyDoesNotThrow() {
        assertThat(new RpcLatencyStats().inSummary(10)).contains("received=0 (unique=0, redelivered=0)");
    }

    @Test
    void subscriptionSummaryReportsHealthWithGaugeUnconfirmedAndResetsWindow() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incSubscribeAcked();
        s.incSubscribeFailed();
        assertThat(s.subscriptionSummary(10, 3)).isEqualTo( // unconfirmed is a live gauge passed in
                "RPC Subscription [window 10s]: acked=1, failed=1, unconfirmed=3");
        assertThat(s.getSubscribeAcked()).isZero(); // window counters reset
    }

    @Test
    void outSummaryShowsReplyLifecycleWindowAndTotals() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incAckedFirstTry();
        s.incAckedAfterRetry();
        s.incRedeliveryReplied();
        s.incRedeliveryReplied();
        s.incBufferedForRetry();
        s.incUndelivered();
        assertThat(s.outSummary(10, 4)).isEqualTo(
                "RPC Out [window 10s]: firstTry=1, afterRetry=1, redeliveryReplied=2, bufferedForRetry=1, "
                        + "undelivered=1 | totals: replyAcked=2, pending=4, undelivered=1, redeliveryReplied=2");
        // window counters reset; totals persist
        assertThat(s.getAckedFirstTry()).isZero();
        assertThat(s.getBufferedForRetry()).isZero();
        assertThat(s.getRedeliveryReplied()).isZero();
        assertThat(s.outSummary(10, 0)).isEqualTo(
                "RPC Out [window 10s]: firstTry=0, afterRetry=0, redeliveryReplied=0, bufferedForRetry=0, "
                        + "undelivered=0 | totals: replyAcked=2, pending=0, undelivered=1, redeliveryReplied=2");
    }

    @Test
    void redeliveryRepliedIsItsOwnCounterAndNotFoldedIntoReplyAcked() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incAckedFirstTry();          // one genuine reply ack
        s.incRedeliveryReplied();      // three best-effort re-replies to server redeliveries
        s.incRedeliveryReplied();
        s.incRedeliveryReplied();
        assertThat(s.getRedeliveryReplied()).isEqualTo(3);
        // replyAcked = firstTry + afterRetry only; redelivery re-replies never inflate it
        assertThat(s.outSummary(10, 0)).contains("replyAcked=1").contains("redeliveryReplied=3");
    }

    @Test
    void drainSummaryRendersPendingFromArg() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incAckedFirstTry();
        String ok = s.drainSummary(6200L, true, 1);
        assertThat(ok).isEqualTo(
                "Gateway RPC drain complete [drained 6.2s, quiesced=true]: received total 2, "
                        + "replyAcked 1, undelivered 0, pending 1");
        assertThat(s.drainSummary(15000L, false, 0)).contains("drained 15.0s").contains("quiesced=false");
    }
}
