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

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class EphemeralScheduleTest {

    @Test
    void autoCapIsRateTimesTimeoutTimesHeadroomRoundedUp() {
        // 10000 / 900 = 11.111/s * CONNECT_TIMEOUT(5) * headroom(2) = 111.11 -> 112
        assertThat(EphemeralSchedule.autoMaxConcurrentConnects(10_000, 900, 5, 2)).isEqualTo(112);
        // 65000 / 900 = 72.22/s * 5 * 2 = 722.2 -> 723
        assertThat(EphemeralSchedule.autoMaxConcurrentConnects(65_000, 900, 5, 2)).isEqualTo(723);
    }

    @Test
    void autoCapNeverBelowOne() {
        assertThat(EphemeralSchedule.autoMaxConcurrentConnects(0, 900, 5, 2)).isEqualTo(1);
        assertThat(EphemeralSchedule.autoMaxConcurrentConnects(10, 0, 5, 2)).isEqualTo(1);
    }

    @Test
    void firstOffsetIsWithinZeroInclusiveToCycleExclusive() {
        Random rnd = new Random(42);
        long cycleMs = 900_000;
        for (int i = 0; i < 1000; i++) {
            long off = EphemeralSchedule.firstOffsetMillis(rnd, cycleMs);
            assertThat(off).isGreaterThanOrEqualTo(0).isLessThan(cycleMs);
        }
    }

    @Test
    void nextDelayIsCyclePlusZeroToJitter() {
        Random rnd = new Random(7);
        long cycleMs = 900_000, jitterMs = 300_000;
        for (int i = 0; i < 1000; i++) {
            long d = EphemeralSchedule.nextDelayMillis(rnd, cycleMs, jitterMs);
            assertThat(d).isGreaterThanOrEqualTo(cycleMs).isLessThan(cycleMs + jitterMs);
        }
    }

    @Test
    void nextDelayWithZeroJitterIsExactlyCycle() {
        assertThat(EphemeralSchedule.nextDelayMillis(new Random(1), 900_000, 0)).isEqualTo(900_000);
    }

    @Test
    void scheduleSeedIsDistinctPerInstanceWhenSeedFixed() {
        long s0 = EphemeralSchedule.scheduleSeed(5, 0);
        long s1 = EphemeralSchedule.scheduleSeed(5, 1);
        long s2 = EphemeralSchedule.scheduleSeed(5, 2);
        assertThat(s0).isNotEqualTo(s1);
        assertThat(s1).isNotEqualTo(s2);
        assertThat(EphemeralSchedule.scheduleSeed(5, 1)).isEqualTo(s1); // deterministic for same inputs
    }

    @Test
    void portBudgetIsRateTimesTimeWait() {
        // 65000 / 900 = 72.22/s * 60s = 4333.3 -> 4334
        assertThat(EphemeralSchedule.timeWaitPortBudget(65_000, 900, 60)).isEqualTo(4334);
    }
}
