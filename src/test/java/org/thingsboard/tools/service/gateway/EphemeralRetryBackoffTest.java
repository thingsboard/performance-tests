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
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class EphemeralRetryBackoffTest {

    /** Records the [origin,bound) it was asked for; returns a fixed value. */
    static final class CapturingRng implements RandomGenerator {
        long capturedOrigin = Long.MIN_VALUE;
        long capturedBound = Long.MIN_VALUE;
        private final long fixed;
        CapturingRng(long fixed) { this.fixed = fixed; }
        @Override public long nextLong() { return fixed; }
        @Override public long nextLong(long origin, long bound) {
            this.capturedOrigin = origin; this.capturedBound = bound; return fixed;
        }
    }

    @Test
    void firstRetryHasJitterWindowMinToDoubleMin() {
        CapturingRng rng = new CapturingRng(1500);
        long v = EphemeralRetryBackoff.resolveMillis(1, 1000, 5000, rng);
        assertThat(v).isEqualTo(1500);
        assertThat(rng.capturedOrigin).isEqualTo(1000);
        assertThat(rng.capturedBound).isEqualTo(2001); // ceiling = min(5000, 1000<<1)=2000, upper-exclusive
    }

    @Test
    void windowGrowsExponentiallyWithAttempt() {
        CapturingRng rng = new CapturingRng(0);
        EphemeralRetryBackoff.resolveMillis(2, 1000, 5000, rng); // ceiling = min(5000, 1000<<2=4000)=4000
        assertThat(rng.capturedBound).isEqualTo(4001);
        EphemeralRetryBackoff.resolveMillis(3, 1000, 5000, rng); // ceiling = min(5000, 1000<<3=8000)=5000 (capped)
        assertThat(rng.capturedBound).isEqualTo(5001);
    }

    @Test
    void windowIsCappedAtMax() {
        CapturingRng rng = new CapturingRng(0);
        EphemeralRetryBackoff.resolveMillis(10, 1000, 5000, rng); // 1000<<9 huge -> capped at 5000
        assertThat(rng.capturedBound).isEqualTo(5001);
    }

    @Test
    void everyDrawWithinMinMaxInclusive() {
        Random seeded = new Random(99L);
        for (int a = 1; a <= 8; a++) {
            for (int i = 0; i < 5000; i++) {
                long v = EphemeralRetryBackoff.resolveMillis(a, 1000, 5000, seeded);
                assertThat(v).isBetween(1000L, 5000L);
            }
        }
    }

    @Test
    void zeroOrNegativeMaxCollapsesToMin() {
        CapturingRng rng = new CapturingRng(-1);
        assertThat(EphemeralRetryBackoff.resolveMillis(3, 1000, 0, rng)).isEqualTo(1000); // rng untouched
        assertThat(rng.capturedBound).isEqualTo(Long.MIN_VALUE);
    }
}
