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
package org.thingsboard.tools.service.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EphemeralStatsTest {

    @Test
    void reportsWindowDeltasRateAndAvgCycleWall() {
        EphemeralStats s = new EphemeralStats();
        for (int i = 0; i < 5; i++) {
            s.onConnectOk();
            s.onPublishOk();
            s.onCycleComplete(200); // 5 cycles, 200ms each
        }
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Ephemeral [window 10s]: cycles=5 (~1/s), connectOk=5, connectFail=0, publishOk=5, publishFail=0, retries=0, recovered=0, lost=0, avgCycleWall=200ms");
    }

    @Test
    void countsFailuresAndZeroCyclesGivesZeroAvg() {
        EphemeralStats s = new EphemeralStats();
        s.onConnectFail();
        s.onPublishFail();
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Ephemeral [window 10s]: cycles=0 (~0/s), connectOk=0, connectFail=1, publishOk=0, publishFail=1, retries=0, recovered=0, lost=0, avgCycleWall=0ms");
    }

    @Test
    void secondWindowReportsDeltaOnly() {
        EphemeralStats s = new EphemeralStats();
        s.onCycleComplete(100);
        s.summaryAndReset(10);                 // consume first cycle
        s.onConnectOk();
        s.onCycleComplete(300);
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Ephemeral [window 10s]: cycles=1 (~0/s), connectOk=1, connectFail=0, publishOk=0, publishFail=0, retries=0, recovered=0, lost=0, avgCycleWall=300ms");
    }

    @Test
    void reportsRetryRecoveredLostDeltas() {
        EphemeralStats s = new EphemeralStats();
        s.onRetryAttempt();
        s.onRetryAttempt();
        s.onCycleRecoveredAfterRetry();
        s.onCycleFailedAfterRetries();
        s.onCycleComplete(100); // one finalized cycle so the line is realistic
        assertThat(s.summaryAndReset(10)).contains("retries=2, recovered=1, lost=1");
    }
}
