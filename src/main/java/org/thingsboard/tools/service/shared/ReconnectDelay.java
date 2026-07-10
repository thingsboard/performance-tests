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

import java.util.random.RandomGenerator;

/**
 * Pure resolution of the per-client MQTT reconnect delay. No Spring, no I/O.
 *
 * <p>netty-mqtt 4.0.1 reconnects after a fixed per-client {@code reconnectDelay}, scheduled in whole
 * seconds, and rejects a value that is not {@code > 0}. So the floor is 1s and the granularity is 1s.
 * Constant and jitter are one mechanism: {@code min == max} is constant, {@code max > min} draws a
 * uniform random delay per client. A drawn value is held in that client's config and reused on every
 * reconnect attempt, so a fleet dropped together spreads over the window and retries stay
 * desynchronized.
 */
public final class ReconnectDelay {

    private ReconnectDelay() {
    }

    /**
     * @return the per-client reconnect delay in seconds, or {@code 0} meaning "leave the library
     * default (1s) untouched" (i.e. do not call {@code setReconnectDelay}).
     */
    public static long resolveSec(long minSec, long maxSec, RandomGenerator rng) {
        if (minSec <= 0 && maxSec <= 0) {
            return 0; // off — library default (1s)
        }
        if (maxSec <= 0) {
            return Math.max(1, minSec); // constant at min (min > 0 here)
        }
        long lo = minSec <= 0 ? 1 : minSec; // 1s floor
        long hi = Math.max(lo, maxSec);      // clamp max < min to constant
        return lo == hi ? lo : rng.nextLong(lo, hi + 1); // upper-exclusive bound
    }

    /** One-line description of the resolved mode for the startup log. */
    public static String describe(long minSec, long maxSec) {
        if (minSec <= 0 && maxSec <= 0) {
            return "off (library default 1s)";
        }
        if (maxSec <= 0) {
            return "constant " + Math.max(1, minSec) + "s";
        }
        long lo = minSec <= 0 ? 1 : minSec;
        long hi = Math.max(lo, maxSec);
        return lo == hi ? "constant " + lo + "s" : "jitter [" + lo + "," + hi + "]s";
    }
}
