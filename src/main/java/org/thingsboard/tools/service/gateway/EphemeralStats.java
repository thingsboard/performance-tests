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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Churn block for ephemeral mode. A cycle is one gateway's connect -> publish batch -> disconnect
 * round-trip. Reports the per-window number of completed cycles (with rate), connect/publish outcomes,
 * and the average per-cycle wall time. No live/peak gauge: in churn an instantaneous connection count
 * sampled at the window boundary is meaningless. Lock-free atomic mutators; {@code summaryAndReset}
 * is synchronized.
 */
public class EphemeralStats {

    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong connectOk = new AtomicLong();
    private final AtomicLong connectFail = new AtomicLong();
    private final AtomicLong publishOk = new AtomicLong();
    private final AtomicLong publishFail = new AtomicLong();
    private final AtomicLong retriesAttempted = new AtomicLong();
    private final AtomicLong cyclesRecoveredAfterRetry = new AtomicLong();
    private final AtomicLong cyclesFailedAfterRetries = new AtomicLong();
    private final AtomicLong cycleWallMillisTotal = new AtomicLong();

    private long lastCycles;
    private long lastConnectOk;
    private long lastConnectFail;
    private long lastPublishOk;
    private long lastPublishFail;
    private long lastRetries;
    private long lastRecovered;
    private long lastLost;
    private long lastWall;

    public void onConnectOk() { connectOk.incrementAndGet(); }
    public void onConnectFail() { connectFail.incrementAndGet(); }
    public void onPublishOk() { publishOk.incrementAndGet(); }
    public void onPublishFail() { publishFail.incrementAndGet(); }
    public void onRetryAttempt() { retriesAttempted.incrementAndGet(); }
    public void onCycleRecoveredAfterRetry() { cyclesRecoveredAfterRetry.incrementAndGet(); }
    public void onCycleFailedAfterRetries() { cyclesFailedAfterRetries.incrementAndGet(); }

    public void onCycleComplete(long wallMillis) {
        cycleWallMillisTotal.addAndGet(wallMillis);
        cycles.incrementAndGet(); // Note: cycleWallMillisTotal and cycles updates are intentionally non-atomic; a report racing between them may skew one window's avgCycleWall slightly (accepted tradeoff).
    }

    public synchronized String summaryAndReset(int windowSec) {
        long c = cycles.get();
        long cok = connectOk.get();
        long cf = connectFail.get();
        long pok = publishOk.get();
        long pf = publishFail.get();
        long rt = retriesAttempted.get();
        long rec = cyclesRecoveredAfterRetry.get();
        long lost = cyclesFailedAfterRetries.get();
        long wall = cycleWallMillisTotal.get();

        long dCycles = c - lastCycles;
        long dConnectOk = cok - lastConnectOk;
        long dConnectFail = cf - lastConnectFail;
        long dPublishOk = pok - lastPublishOk;
        long dPublishFail = pf - lastPublishFail;
        long dRetries = rt - lastRetries;
        long dRecovered = rec - lastRecovered;
        long dLost = lost - lastLost;
        long dWall = wall - lastWall;

        lastCycles = c;
        lastConnectOk = cok;
        lastConnectFail = cf;
        lastPublishOk = pok;
        lastPublishFail = pf;
        lastRetries = rt;
        lastRecovered = rec;
        lastLost = lost;
        lastWall = wall;

        double rate = windowSec > 0 ? (double) dCycles / windowSec : 0.0;
        long avgWall = dCycles > 0 ? dWall / dCycles : 0;
        return String.format(
                "Ephemeral [window %ds]: cycles=%d (~%.0f/s), connectOk=%d, connectFail=%d, publishOk=%d, publishFail=%d, retries=%d, recovered=%d, lost=%d, avgCycleWall=%dms",
                windowSec, dCycles, rate, dConnectOk, dConnectFail, dPublishOk, dPublishFail, dRetries, dRecovered, dLost, avgWall);
    }
}
