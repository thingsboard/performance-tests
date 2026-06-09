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

import java.util.Random;

/**
 * Pure timing/sizing math for the ephemeral connection mode. No Spring, no I/O.
 */
public final class EphemeralSchedule {

    private EphemeralSchedule() {
    }

    /**
     * Auto cap = ceil(connectRate * connectTimeoutSec * headroom), where connectRate = gatewayCount / cycleLengthSec.
     * Non-throttling by construction: worst-case in-flight connects even in a total outage (every connect runs to
     * the full timeout) is rate * connectTimeoutSec, so the cap is never the bottleneck in steady state.
     */
    public static int autoMaxConcurrentConnects(int gatewayCount, int cycleLengthSec, int connectTimeoutSec, int headroom) {
        if (gatewayCount <= 0 || cycleLengthSec <= 0) {
            return 1;
        }
        double rate = (double) gatewayCount / cycleLengthSec;
        int value = (int) Math.ceil(rate * connectTimeoutSec * headroom);
        return Math.max(1, value);
    }

    /** First-cycle offset uniformly in [0, cycleLengthMillis) — spreads the startup herd. */
    public static long firstOffsetMillis(Random random, long cycleLengthMillis) {
        if (cycleLengthMillis <= 0) {
            return 0;
        }
        return (long) (random.nextDouble() * cycleLengthMillis);
    }

    /** Per-cycle delay = cycleLengthMillis + uniform[0, jitterMillis) (one-sided; never shorter than the cycle). */
    public static long nextDelayMillis(Random random, long cycleLengthMillis, long jitterMillis) {
        long jitter = jitterMillis <= 0 ? 0 : (long) (random.nextDouble() * jitterMillis);
        return cycleLengthMillis + jitter;
    }

    public static double connectsPerSecond(int gatewayCount, int cycleLengthSec) {
        return cycleLengthSec <= 0 ? 0 : (double) gatewayCount / cycleLengthSec;
    }

    /** Continuous ephemeral ports tied up by TIME_WAIT ~= connectRate * timeWaitSec. */
    public static long timeWaitPortBudget(int gatewayCount, int cycleLengthSec, int timeWaitSec) {
        return (long) Math.ceil(connectsPerSecond(gatewayCount, cycleLengthSec) * timeWaitSec);
    }

    /**
     * Per-pod-distinct RNG seed: when a fixed seed is configured, decorrelate pods by instance index so their
     * connection bursts do not align in time; when seed == 0 (the unset default) use a non-deterministic seed.
     */
    public static long scheduleSeed(long configuredSeed, int instanceIdx) {
        if (configuredSeed != 0) {
            return configuredSeed + 1_000_003L * instanceIdx;
        }
        return System.nanoTime() + 1_000_003L * instanceIdx;
    }
}
