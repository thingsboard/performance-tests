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
package org.thingsboard.tools.service.gateway.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-unique-RPC accounting keyed by {@code (deviceName, requestId)}. The server legitimately
 * re-pushes still-pending RPCs on reconnect, so the same key can arrive more than once; this tracker
 * classifies first-receipt vs duplicate and exposes the count of RPCs received but not yet successfully
 * answered ({@code outstandingCount}) — the honest {@code pending}.
 *
 * <p>Two states: {@code OUTSTANDING} (received, reply not yet confirmed) and {@code ANSWERED}
 * (reply confirmed). ANSWERED keys are kept only so a late redelivery is still recognised as a
 * duplicate; they are evicted once older than the RPC expiry (no redelivery can arrive after that).
 * OUTSTANDING keys are never evicted — a genuinely unanswered RPC stays until drain, where it is
 * reported for DB {@code EXPIRED} correlation. Thread-safe and non-blocking: a {@link ConcurrentHashMap}
 * with atomic {@code putIfAbsent}/{@code compute}; safe to call from netty event-loop threads.
 */
public class RpcOutstandingTracker {

    public record RpcKey(String deviceName, String requestId) {
    }

    private enum State { OUTSTANDING, ANSWERED }

    private record Entry(State state, long tsMs) {
    }

    private final ConcurrentHashMap<RpcKey, Entry> map = new ConcurrentHashMap<>();
    private final AtomicInteger outstanding = new AtomicInteger();

    /** Record the key. Returns {@code true} on first receipt (newly OUTSTANDING); {@code false} if the
     *  key is already known (a duplicate delivery — do not double-count or re-answer). */
    public boolean firstReceipt(RpcKey key, long nowMs) {
        Entry prev = map.putIfAbsent(key, new Entry(State.OUTSTANDING, nowMs));
        if (prev == null) {
            outstanding.incrementAndGet();
            return true;
        }
        return false;
    }

    /** Mark the key answered (reply confirmed sent/recovered) — removes it from the outstanding count. */
    public void markAnswered(RpcKey key, long nowMs) {
        map.compute(key, (k, e) -> {
            if (e != null && e.state() == State.OUTSTANDING) {
                outstanding.decrementAndGet();
            }
            return new Entry(State.ANSWERED, nowMs);
        });
    }

    /** Distinct RPCs received but never confirmed answered = honest {@code pending}. */
    public int outstandingCount() {
        return outstanding.get();
    }

    /** Evict ANSWERED keys older than {@code ttlMs} (bounded memory — no redelivery is possible past
     *  the RPC expiry). OUTSTANDING keys are kept. */
    public void evictAnsweredOlderThan(long nowMs, long ttlMs) {
        map.forEach((k, e) -> {
            if (e.state() == State.ANSWERED && nowMs - e.tsMs() >= ttlMs) {
                map.remove(k, e); // conditional: only if unchanged since read
            }
        });
    }

    /** The still-unanswered keys, for drain-time logging / DB EXPIRED correlation. */
    public List<RpcKey> outstandingKeys() {
        List<RpcKey> out = new ArrayList<>();
        map.forEach((k, e) -> {
            if (e.state() == State.OUTSTANDING) {
                out.add(k);
            }
        });
        return out;
    }
}
