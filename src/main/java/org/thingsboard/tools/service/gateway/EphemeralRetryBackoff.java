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

import java.util.random.RandomGenerator;

/**
 * Pure backoff math for the ephemeral retry loop. No Spring, no I/O.
 *
 * <p>Full-jitter over an exponentially-growing, max-capped window: for a 1-based {@code attempt},
 * ceiling = min(maxMs, minMs &lt;&lt; attempt), and the result is a uniform draw in [minMs, ceiling].
 * Using {@code attempt} (not {@code attempt-1}) as the shift means even the first retry has a real
 * jitter window ([min, 2·min]) rather than a single deterministic value, so a fleet that dropped
 * together does not retry in lockstep; the cap bounds the permit-hold per attempt.
 */
public final class EphemeralRetryBackoff {

    private EphemeralRetryBackoff() {
    }

    public static long resolveMillis(int attempt, long minMs, long maxMs, RandomGenerator rng) {
        long lo = Math.max(0, minMs);
        long hi = Math.max(lo, maxMs);
        if (hi <= lo) {
            return lo; // degenerate window: constant, rng untouched
        }
        int shift = Math.min(30, Math.max(0, attempt)); // guard against overflow / attempt<=0
        long grown = lo <= 0 ? hi : lo << shift;
        long ceiling = (grown <= 0 || grown > hi) ? hi : grown; // overflow-safe cap at hi
        if (ceiling <= lo) {
            return lo;
        }
        return rng.nextLong(lo, ceiling + 1); // upper-exclusive => [lo, ceiling]
    }
}
