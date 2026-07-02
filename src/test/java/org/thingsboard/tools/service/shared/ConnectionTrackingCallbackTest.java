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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTrackingCallbackTest {

    @Test
    void firstConnAckCountsConnect() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.onConnAck(null);
        assertThat(stats.getLive()).isEqualTo(1);
        assertThat(stats.getConnects()).isEqualTo(1);
        assertThat(stats.getReconnects()).isEqualTo(0);
    }

    @Test
    void lostThenConnAckCountsReconnect() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.onConnAck(null);
        cb.connectionLost(new RuntimeException("reset"));
        cb.onConnAck(null);
        assertThat(stats.getLive()).isEqualTo(1);
        assertThat(stats.getConnects()).isEqualTo(1);
        assertThat(stats.getReconnects()).isEqualTo(1);
        assertThat(stats.getDisconnects()).isEqualTo(1);
    }

    @Test
    void duplicateConnAckWhileUpIsIgnored() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.onConnAck(null);
        cb.onConnAck(null); // spurious; already up
        assertThat(stats.getConnects()).isEqualTo(1);
        assertThat(stats.getReconnects()).isEqualTo(0);
        assertThat(stats.getLive()).isEqualTo(1);
    }

    @Test
    void duplicateConnectionLostCountsOnce() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.onConnAck(null);
        cb.connectionLost(new RuntimeException("reset"));
        cb.connectionLost(new RuntimeException("reset again"));
        assertThat(stats.getDisconnects()).isEqualTo(1);
        assertThat(stats.getLive()).isEqualTo(0);
    }

    @Test
    void onSuccessfulReconnectInvokesAction() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        AtomicInteger calls = new AtomicInteger();
        cb.setOnReconnect(calls::incrementAndGet);
        cb.onSuccessfulReconnect();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void onSuccessfulReconnectWithNoActionIsSafe() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.onSuccessfulReconnect(); // no action set — must not throw
        assertThat(stats.getReconnects()).isEqualTo(0);
    }

    @Test
    void reconnectActionDoesNotPerturbStatsClassification() {
        ConnectionStats stats = new ConnectionStats();
        ConnectionTrackingCallback cb = new ConnectionTrackingCallback(stats);
        cb.setOnReconnect(() -> { });
        cb.onConnAck(null);                        // connect
        cb.connectionLost(new RuntimeException());  // disconnect
        cb.onSuccessfulReconnect();                 // fires action; must not touch stats
        cb.onConnAck(null);                         // reconnect (stats)
        assertThat(stats.getConnects()).isEqualTo(1);
        assertThat(stats.getReconnects()).isEqualTo(1);
        assertThat(stats.getDisconnects()).isEqualTo(1);
    }
}
