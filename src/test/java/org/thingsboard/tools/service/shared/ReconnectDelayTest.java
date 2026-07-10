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

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectDelayTest {

    /** RandomGenerator stub that records the bounds it was called with and returns a fixed value. */
    static final class CapturingRng implements RandomGenerator {
        long capturedOrigin = Long.MIN_VALUE;
        long capturedBound = Long.MIN_VALUE;
        private final long fixed;

        CapturingRng(long fixed) {
            this.fixed = fixed;
        }

        @Override
        public long nextLong() {
            return fixed;
        }

        @Override
        public long nextLong(long origin, long bound) {
            this.capturedOrigin = origin;
            this.capturedBound = bound;
            return fixed;
        }
    }

    private static final RandomGenerator UNUSED_RNG = new CapturingRng(-999);

    @Test
    void bothUnsetReturnsZeroSentinel() {
        assertThat(ReconnectDelay.resolveSec(0, 0, UNUSED_RNG)).isZero();
    }

    @Test
    void minOnlyIsConstant() {
        assertThat(ReconnectDelay.resolveSec(5, 0, UNUSED_RNG)).isEqualTo(5);
    }

    @Test
    void equalBoundsIsConstant() {
        assertThat(ReconnectDelay.resolveSec(5, 5, UNUSED_RNG)).isEqualTo(5);
    }

    @Test
    void maxBelowMinClampsToConstantMin() {
        assertThat(ReconnectDelay.resolveSec(10, 5, UNUSED_RNG)).isEqualTo(10);
    }

    @Test
    void jitterUsesUpperExclusiveBounds() {
        CapturingRng rng = new CapturingRng(42);
        long result = ReconnectDelay.resolveSec(1, 60, rng);
        assertThat(result).isEqualTo(42);
        assertThat(rng.capturedOrigin).isEqualTo(1);
        assertThat(rng.capturedBound).isEqualTo(61); // upper-exclusive
    }

    @Test
    void zeroMinWithMaxFloorsToOne() {
        CapturingRng rng = new CapturingRng(3);
        long result = ReconnectDelay.resolveSec(0, 60, rng);
        assertThat(result).isEqualTo(3);
        assertThat(rng.capturedOrigin).isEqualTo(1); // floored
        assertThat(rng.capturedBound).isEqualTo(61);
    }

    @Test
    void jitterAlwaysWithinInclusiveRange() {
        Random seeded = new Random(12345L);
        for (int i = 0; i < 10_000; i++) {
            long d = ReconnectDelay.resolveSec(1, 60, seeded);
            assertThat(d).isBetween(1L, 60L);
        }
    }

    @Test
    void describeReportsMode() {
        assertThat(ReconnectDelay.describe(0, 0)).isEqualTo("off (library default 1s)");
        assertThat(ReconnectDelay.describe(5, 0)).isEqualTo("constant 5s");
        assertThat(ReconnectDelay.describe(5, 5)).isEqualTo("constant 5s");
        assertThat(ReconnectDelay.describe(1, 60)).isEqualTo("jitter [1,60]s");
        assertThat(ReconnectDelay.describe(0, 60)).isEqualTo("jitter [1,60]s");
    }
}
