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
 * Stats for the "Announce a downstream device connection" (v1/gateway/connect) publish that
 * (re)establishes a sub-device's server-side RPC routing. {@code acked} = broker-confirmed (PUBACK);
 * {@code failed} = per-attempt publish failures/timeouts; {@code retried} = retries scheduled;
 * {@code unconfirmed} = devices that exhausted the retry cap without a PUBACK — this must stay 0
 * ({@code >0} = devices at risk of losing RPC routing). Reported per window (getAndSet deltas).
 */
public class AnnounceStats {

    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong unconfirmed = new AtomicLong();

    public void onAcked() { acked.incrementAndGet(); }
    public void onAttemptFailed() { failed.incrementAndGet(); }
    public void onRetry() { retried.incrementAndGet(); }
    public void onUnconfirmed() { unconfirmed.incrementAndGet(); }

    public long getAcked() { return acked.get(); }
    public long getFailed() { return failed.get(); }
    public long getRetried() { return retried.get(); }
    public long getUnconfirmed() { return unconfirmed.get(); }

    public synchronized String summaryAndReset(int windowSec) {
        return String.format(
                "Gateway device announce [window %ds]: acked=%d, failed=%d, retried=%d, unconfirmed=%d",
                windowSec, acked.getAndSet(0), failed.getAndSet(0), retried.getAndSet(0), unconfirmed.getAndSet(0));
    }
}
