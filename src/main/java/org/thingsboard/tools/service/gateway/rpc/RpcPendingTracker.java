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
 * re-pushes still-in-flight RPCs on reconnect, so the same key can arrive more than once; this tracker
 * classifies first-receipt vs in-flight duplicate and exposes the count of RPCs received but not yet
 * successfully answered ({@code pendingCount}) — the honest {@code pending}.
 *
 * <p>Three states: {@code PENDING} (received, reply not yet confirmed), {@code ANSWERED} (reply
 * confirmed), and {@code LOST} (reply given up). ANSWERED keys are kept only so memory can be bounded by
 * eviction; they are evicted once older than the RPC expiry. PENDING keys are never evicted — a genuinely
 * unanswered RPC stays until drain, where it is reported for DB {@code EXPIRED} correlation. Thread-safe
 * and non-blocking: a {@link ConcurrentHashMap} with atomic {@code compute}; safe to call from netty
 * event-loop threads.
 */
public class RpcPendingTracker {

    public record RpcKey(String deviceName, String requestId) {
    }

    private enum State { PENDING, ANSWERED, LOST }

    private record Entry(State state, long tsMs) {
    }

    private final ConcurrentHashMap<RpcKey, Entry> map = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();

    /**
     * Record the key. Dedups <b>in-flight only</b>: returns {@code false} (a genuine server redelivery of
     * a still-unanswered request) only when the key is currently {@code PENDING}; otherwise returns
     * {@code true} (a fresh RPC) and (re)opens it to {@code PENDING}. A key that is {@code ANSWERED} or
     * {@code LOST} is treated as forgotten — the platform rewinds {@code rpcSeq} and reuses ids of drained
     * devices, so a receipt reusing such an id is a NEW RPC, not a duplicate (gateway contract: dedup
     * in-flight requests, forget an id once answered/expired). Safe because the platform never re-publishes
     * an already-answered RPC — the only genuine redelivery is a re-pushed unanswered {@code SENT}, which
     * is still {@code PENDING} and thus still deduped.
     */
    public boolean firstReceipt(RpcKey key, long nowMs) {
        Entry reopened = new Entry(State.PENDING, nowMs);
        Entry current = map.compute(key, (k, e) ->
                (e != null && e.state() == State.PENDING) ? e : reopened);
        if (current == reopened) {          // our instance won -> a fresh RPC (absent / reused ANSWERED|LOST id)
            pending.incrementAndGet();
            return true;
        }
        return false;                       // kept the existing PENDING entry -> in-flight duplicate
    }

    /** Mark the key answered (reply confirmed sent/recovered) — removes it from the pending count.
     *  A key already given up as {@code LOST} is left {@code LOST}: a late orphan-publish success must not
     *  resurrect a reply we already declared undelivered, so {@code lostKeys()} and the {@code undelivered}
     *  count stay in agreement for DB {@code EXPIRED} correlation. */
    public void markAnswered(RpcKey key, long nowMs) {
        map.compute(key, (k, e) -> {
            if (e != null && e.state() == State.LOST) {
                return e; // already declared undelivered — do not flip to answered
            }
            if (e != null && e.state() == State.PENDING) {
                pending.decrementAndGet();
            }
            return new Entry(State.ANSWERED, nowMs);
        });
    }

    /** Mark the key terminally lost (reply given up: past TTL or buffer full). Removes it from the
     *  pending count so {@code pending} means "still recoverable" and drain can quiesce. A key that
     *  was already answered is left answered (a lost path must not override a delivered reply). */
    public void markLost(RpcKey key, long nowMs) {
        map.compute(key, (k, e) -> {
            if (e != null && e.state() == State.ANSWERED) {
                return e; // already delivered — do not downgrade to lost
            }
            if (e != null && e.state() == State.PENDING) {
                pending.decrementAndGet();
            }
            return new Entry(State.LOST, nowMs);
        });
    }

    /** The terminally-lost keys, for drain-time logging / DB EXPIRED correlation. */
    public List<RpcKey> lostKeys() {
        List<RpcKey> out = new ArrayList<>();
        map.forEach((k, e) -> {
            if (e.state() == State.LOST) {
                out.add(k);
            }
        });
        return out;
    }

    /** Distinct RPCs received but never confirmed answered = honest {@code pending}. */
    public int pendingCount() {
        return pending.get();
    }

    /** Evict ANSWERED keys older than {@code ttlMs} (bounded memory — no redelivery is possible past
     *  the RPC expiry). PENDING keys are kept. */
    public void evictAnsweredOlderThan(long nowMs, long ttlMs) {
        map.forEach((k, e) -> {
            if (e.state() == State.ANSWERED && nowMs - e.tsMs() >= ttlMs) {
                map.remove(k, e); // conditional: only if unchanged since read
            }
        });
    }

    /** The still-unanswered (PENDING) keys, for drain-time logging / DB EXPIRED correlation. */
    public List<RpcKey> pendingKeys() {
        List<RpcKey> out = new ArrayList<>();
        map.forEach((k, e) -> {
            if (e.state() == State.PENDING) {
                out.add(k);
            }
        });
        return out;
    }
}
