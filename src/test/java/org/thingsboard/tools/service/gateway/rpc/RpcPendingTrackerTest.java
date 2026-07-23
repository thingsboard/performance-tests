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

import org.junit.jupiter.api.Test;
import org.thingsboard.tools.service.gateway.rpc.RpcPendingTracker.RpcKey;

import static org.assertj.core.api.Assertions.assertThat;

class RpcPendingTrackerTest {

    private static final RpcKey K1 = new RpcKey("DW5", "42");
    private static final RpcKey K2 = new RpcKey("DW6", "42"); // same requestId, different device

    @Test
    void firstReceiptTracksInFlightDuplicateDoesNot() {
        RpcPendingTracker t = new RpcPendingTracker();
        assertThat(t.firstReceipt(K1, 1000)).isTrue();
        assertThat(t.pendingCount()).isEqualTo(1);
        assertThat(t.firstReceipt(K1, 1050)).isFalse();      // still PENDING -> in-flight duplicate
        assertThat(t.pendingCount()).isEqualTo(1);           // not re-added
    }

    @Test
    void keyIsPerDeviceRequestIdPair() {
        RpcPendingTracker t = new RpcPendingTracker();
        assertThat(t.firstReceipt(K1, 1000)).isTrue();
        assertThat(t.firstReceipt(K2, 1000)).isTrue();       // same id, other device = distinct
        assertThat(t.pendingCount()).isEqualTo(2);
    }

    @Test
    void answeredKeyReopensAsFreshOnReuse() {
        // Dedup is IN-FLIGHT ONLY. Once answered, the id is forgotten; a receipt reusing it (the platform
        // rewinds rpcSeq for a drained device) is a NEW RPC, not a duplicate.
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.markAnswered(K1, 1100);
        assertThat(t.pendingCount()).isZero();
        assertThat(t.firstReceipt(K1, 1200)).isTrue();       // reused id -> fresh RPC
        assertThat(t.pendingCount()).isEqualTo(1);           // re-opened as PENDING
    }

    @Test
    void lostKeyReopensAsFreshOnReuse() {
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.markLost(K1, 1100);
        assertThat(t.pendingCount()).isZero();
        assertThat(t.firstReceipt(K1, 1200)).isTrue();       // reused after give-up -> fresh RPC
        assertThat(t.pendingCount()).isEqualTo(1);
    }

    @Test
    void evictsAnsweredPastTtlButKeepsUnanswered() {
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.markAnswered(K1, 1000);          // answered at t=1000
        t.firstReceipt(K2, 1000);          // never answered

        t.evictAnsweredOlderThan(1000 + 120_000, 120_000); // ttl elapsed for the answered one

        // answered K1 evicted -> a new delivery counts as first receipt again
        assertThat(t.firstReceipt(K1, 200_000)).isTrue();
        // unanswered K2 kept and reported
        assertThat(t.pendingKeys()).contains(K2);
    }

    @Test
    void markLostRemovesFromPendingAndIsReported() {
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.firstReceipt(K2, 1000);
        t.markLost(K1, 1100);
        assertThat(t.pendingCount()).isEqualTo(1);   // only K2 still recoverable
        assertThat(t.lostKeys()).containsExactly(K1);
        assertThat(t.pendingKeys()).containsExactly(K2);
    }

    @Test
    void markLostDoesNotOverrideAnAlreadyAnsweredKey() {
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.markAnswered(K1, 1100);
        t.markLost(K1, 1200);                            // late/spurious lost after delivery
        assertThat(t.pendingCount()).isZero();
        assertThat(t.lostKeys()).isEmpty();              // stays answered, not downgraded to lost
    }

    @Test
    void markAnsweredDoesNotResurrectAGivenUpLostKey() {
        // D2: a late orphan-publish success on a reply we already gave up must NOT flip it back, so the
        // logged lost list keeps agreeing with the undelivered count for DB EXPIRED correlation.
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.markLost(K1, 1100);
        t.markAnswered(K1, 1200);                        // late orphan success after give-up
        assertThat(t.pendingCount()).isZero();
        assertThat(t.lostKeys()).containsExactly(K1);    // still lost, not resurrected to answered
    }

    @Test
    void pendingKeysListsOnlyUnanswered() {
        RpcPendingTracker t = new RpcPendingTracker();
        t.firstReceipt(K1, 1000);
        t.firstReceipt(K2, 1000);
        t.markAnswered(K1, 1100);
        assertThat(t.pendingKeys()).containsExactly(K2);
    }
}
