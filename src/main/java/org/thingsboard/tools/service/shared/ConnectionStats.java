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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe holder for MQTT connection health across a gateway run. {@code live} is a running
 * gauge of established (post-CONNACK) sessions; {@code connects}/{@code disconnects}/{@code reconnects}
 * are per-window counts reset by {@link #summaryAndReset(int)}; {@code peak} is the high-water mark of
 * {@code live} within the window. Mutators are lock-free atomics (mirroring {@code RpcLatencyStats});
 * {@code summaryAndReset} is synchronized so a report cannot interleave with another report.
 */
public class ConnectionStats {

    private final AtomicInteger live = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private volatile Integer target;

    public void onConnect() {
        int l = live.incrementAndGet();
        connects.incrementAndGet();
        peak.accumulateAndGet(l, Math::max);
    }

    public void onReconnect() {
        int l = live.incrementAndGet();
        reconnects.incrementAndGet();
        peak.accumulateAndGet(l, Math::max);
    }

    public void onDisconnect() {
        live.updateAndGet(v -> v > 0 ? v - 1 : 0);
        disconnects.incrementAndGet();
    }

    public void setTarget(int target) {
        this.target = target;
    }

    public int getLive() { return live.get(); }
    public int getPeak() { return peak.get(); }
    public long getConnects() { return connects.get(); }
    public long getDisconnects() { return disconnects.get(); }
    public long getReconnects() { return reconnects.get(); }

    /**
     * Render the canonical one-line summary, then reset the window counters and set {@code peak = live}
     * for the next window. {@code live} and {@code target} persist.
     */
    public synchronized String summaryAndReset(int windowSec) {
        int liveNow = live.get();
        Integer t = target;
        String liveStr = (t == null)
                ? String.format("live=%d", liveNow)
                : String.format("live=%d/%d", liveNow, t);
        String line = String.format(
                "Connections [window %ds]: %s, peak=%d, connects=%d, disconnects=%d, reconnects=%d",
                windowSec, liveStr, peak.get(),
                connects.getAndSet(0), disconnects.getAndSet(0), reconnects.getAndSet(0));
        peak.set(live.get());
        return line;
    }
}
