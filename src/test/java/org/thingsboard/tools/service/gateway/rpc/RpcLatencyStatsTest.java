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
    void outCountersIncrement() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReplyPublished();
        s.incReplyPublished();
        s.incReplyPubAcked();
        s.incReplyFailed();
        s.incRePublished();
        s.incRecovered();
        s.incLost();
        assertThat(s.getReplyPublished()).isEqualTo(2);
        assertThat(s.getReplyPubAcked()).isEqualTo(1);
        assertThat(s.getReplyFailed()).isEqualTo(1);
        assertThat(s.getRePublished()).isEqualTo(1);
        assertThat(s.getRecoveredTotal()).isEqualTo(1);
        assertThat(s.getLostTotal()).isEqualTo(1);
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
    void inSummaryShowsPublishNewRedeliveredAndResetsWindow() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incReceived(1000L);
        s.incRedelivered(); // one of the three was a server redelivery
        s.recordLatency(150);
        assertThat(s.inSummary(10)).isEqualTo(
                "RPC In [window 10s]: publish=3 (new 2, redelivered 1); "
                        + "latency(1-way srv->gw) avg=150.0 p50=150.0 p95=150.0 p99=150.0 max=150.0 ms");
        // window counters reset; totals persist
        assertThat(s.getReceived()).isZero();
        assertThat(s.getReceivedTotal()).isEqualTo(3);
        assertThat(s.getCount()).isZero();
    }

    @Test
    void inSummaryOnEmptyDoesNotThrow() {
        assertThat(new RpcLatencyStats().inSummary(10)).contains("publish=0 (new 0, redelivered 0)");
    }

    @Test
    void subscriptionSummaryReportsHealthWithGaugeUnconfirmedAndResetsWindow() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incSubscribeAcked();
        s.incSubscribeFailed();
        assertThat(s.subscriptionSummary(10, 3)).isEqualTo( // unconfirmed is a live gauge passed in
                "RPC Subscription [window 10s]: subAck=1, failed=1, unconfirmed=3");
        assertThat(s.getSubscribeAcked()).isZero(); // window counters reset
    }

    @Test
    void outSummaryShowsPublishAckFailedRePublishedAndResetsWindow() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReplyPublished();
        s.incReplyPublished();
        s.incReplyPubAcked();
        s.incReplyFailed();
        s.incRePublished();
        assertThat(s.outSummary(10)).isEqualTo(
                "RPC Out [window 10s]: publish=2, pubAck=1, failed=1, rePublished=1");
        // window counters reset
        assertThat(s.getReplyPublished()).isZero();
        assertThat(s.outSummary(10)).isEqualTo(
                "RPC Out [window 10s]: publish=0, pubAck=0, failed=0, rePublished=0");
    }

    @Test
    void totalSummariesReconcileFailedAsRecoveredPlusLost() {
        RpcLatencyStats s = new RpcLatencyStats();
        s.incReceived(1L);
        s.incReceived(1L);
        s.incReceived(1L);
        s.incRedelivered();                 // 3 received, 1 a redelivery -> new=2
        s.incReplyPublished();
        s.incReplyPublished();
        s.incReplyPublished();              // 3 reply publishes
        s.incReplyPubAcked();
        s.incReplyPubAcked();
        s.incReplyPubAcked();               // all 3 confirmed
        s.incReplyFailed();
        s.incReplyFailed();                 // 2 failed at some point ...
        s.incRecovered();                   // ... 1 recovered on a re-send ...
        s.incLost();                        // ... 1 never delivered
        assertThat(s.inTotalSummary()).isEqualTo("RPC In  [total]: publish=3 (new 2, redelivered 1)");
        assertThat(s.outTotalSummary()).isEqualTo( // failed == recovered + lost on a settled run
                "RPC Out [total]: publish=3, pubAck=3, failed=2, recovered=1, lost=1");
    }
}
