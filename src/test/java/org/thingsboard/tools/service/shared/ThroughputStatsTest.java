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
package org.thingsboard.tools.service.shared;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ThroughputStatsTest {

    @Test
    void reportsWindowDeltaRateAndCumulativeTotals() {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ThroughputStats s = new ThroughputStats(ok, fail);
        ok.set(39960);
        fail.set(0);
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Throughput [window 10s]: publishOk=39960, publishFail=0, ~3996 msg/s (total ok=39960, fail=0)");
    }

    @Test
    void secondWindowReportsDeltaOnlyButTotalsAccumulate() {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ThroughputStats s = new ThroughputStats(ok, fail);
        ok.set(10000);
        s.summaryAndReset(10);              // first window consumes 10000
        ok.set(30000);                       // +20000 in second window
        fail.set(5);
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Throughput [window 10s]: publishOk=20000, publishFail=5, ~2000 msg/s (total ok=30000, fail=5)");
    }
}
