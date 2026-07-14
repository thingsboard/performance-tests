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
import org.thingsboard.tools.service.gateway.rpc.RpcOutstandingTracker.RpcKey;

import static org.assertj.core.api.Assertions.assertThat;

class RpcOutstandingTrackerTest {

    private static final RpcKey K1 = new RpcKey("DW5", "42");
    private static final RpcKey K2 = new RpcKey("DW6", "42"); // same requestId, different device

    @Test
    void firstReceiptTracksDuplicateDoesNot() {
        RpcOutstandingTracker t = new RpcOutstandingTracker();
        assertThat(t.firstReceipt(K1, 1000)).isTrue();
        assertThat(t.outstandingCount()).isEqualTo(1);
        assertThat(t.firstReceipt(K1, 1050)).isFalse();      // duplicate
        assertThat(t.outstandingCount()).isEqualTo(1);       // not re-added
    }

    @Test
    void keyIsPerDeviceRequestIdPair() {
        RpcOutstandingTracker t = new RpcOutstandingTracker();
        assertThat(t.firstReceipt(K1, 1000)).isTrue();
        assertThat(t.firstReceipt(K2, 1000)).isTrue();       // same id, other device = distinct
        assertThat(t.outstandingCount()).isEqualTo(2);
    }

    @Test
    void answerDropsOutstandingAndLateDuplicateStillRecognised() {
        RpcOutstandingTracker t = new RpcOutstandingTracker();
        t.firstReceipt(K1, 1000);
        t.markAnswered(K1, 1100);
        assertThat(t.outstandingCount()).isZero();
        // a redelivery after we answered must NOT look like a fresh first-receipt
        assertThat(t.firstReceipt(K1, 1200)).isFalse();
        assertThat(t.outstandingCount()).isZero();
    }

    @Test
    void evictsAnsweredPastTtlButKeepsUnanswered() {
        RpcOutstandingTracker t = new RpcOutstandingTracker();
        t.firstReceipt(K1, 1000);
        t.markAnswered(K1, 1000);          // answered at t=1000
        t.firstReceipt(K2, 1000);          // never answered

        t.evictAnsweredOlderThan(1000 + 120_000, 120_000); // ttl elapsed for the answered one

        // answered K1 evicted -> a new delivery now counts as first receipt again
        assertThat(t.firstReceipt(K1, 200_000)).isTrue();
        // unanswered K2 kept and reported
        assertThat(t.outstandingKeys()).contains(K2);
    }

    @Test
    void outstandingKeysListsOnlyUnanswered() {
        RpcOutstandingTracker t = new RpcOutstandingTracker();
        t.firstReceipt(K1, 1000);
        t.firstReceipt(K2, 1000);
        t.markAnswered(K1, 1100);
        assertThat(t.outstandingKeys()).containsExactly(K2);
    }
}
