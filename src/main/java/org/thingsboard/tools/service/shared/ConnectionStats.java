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

/**
 * Thread-safe holder for MQTT connection health across a gateway run. {@code live} is a running
 * gauge of established (post-CONNACK) sessions; {@code connects}/{@code disconnects}/{@code reconnects}
 * are per-window counts reset by {@link #summaryAndReset(int)}; {@code peak} is the high-water mark of
 * {@code live} within the window. All mutation is guarded by the instance monitor — connection events
 * are low-frequency relative to telemetry, so contention is negligible.
 */
public class ConnectionStats {

    private int live;
    private int peak;
    private long connects;
    private long disconnects;
    private long reconnects;
    private Integer target;

    public synchronized void onConnect() {
        live++;
        connects++;
        if (live > peak) {
            peak = live;
        }
    }

    public synchronized void onReconnect() {
        live++;
        reconnects++;
        if (live > peak) {
            peak = live;
        }
    }

    public synchronized void onDisconnect() {
        if (live > 0) {
            live--;
        }
        disconnects++;
    }

    public synchronized void setTarget(int target) {
        this.target = target;
    }

    public synchronized int getLive() { return live; }
    public synchronized int getPeak() { return peak; }
    public synchronized long getConnects() { return connects; }
    public synchronized long getDisconnects() { return disconnects; }
    public synchronized long getReconnects() { return reconnects; }

    /**
     * Render the canonical one-line summary, then reset the window counters and set {@code peak = live}
     * for the next window. {@code live} and {@code target} persist.
     */
    public synchronized String summaryAndReset(int windowSec) {
        String liveStr = (target == null)
                ? String.format("live=%d", live)
                : String.format("live=%d/%d", live, target);
        String line = String.format(
                "Connections [window %ds]: %s, peak=%d, connects=%d, disconnects=%d, reconnects=%d",
                windowSec, liveStr, peak, connects, disconnects, reconnects);
        connects = 0;
        disconnects = 0;
        reconnects = 0;
        peak = live;
        return line;
    }
}
