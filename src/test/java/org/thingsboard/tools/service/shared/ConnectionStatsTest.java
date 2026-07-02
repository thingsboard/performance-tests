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

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionStatsTest {

    @Test
    void connectRaisesLiveAndPeak() {
        ConnectionStats s = new ConnectionStats();
        s.onConnect();
        s.onConnect();
        assertThat(s.getLive()).isEqualTo(2);
        assertThat(s.getPeak()).isEqualTo(2);
        assertThat(s.getConnects()).isEqualTo(2);
    }

    @Test
    void disconnectLowersLiveButPeakHolds() {
        ConnectionStats s = new ConnectionStats();
        s.onConnect();
        s.onConnect();
        s.onDisconnect();
        assertThat(s.getLive()).isEqualTo(1);
        assertThat(s.getPeak()).isEqualTo(2);
        assertThat(s.getDisconnects()).isEqualTo(1);
    }

    @Test
    void disconnectFlooredAtZero() {
        ConnectionStats s = new ConnectionStats();
        s.onDisconnect();
        assertThat(s.getLive()).isEqualTo(0);
        assertThat(s.getDisconnects()).isEqualTo(1);
    }

    @Test
    void reconnectRaisesLiveAndCounter() {
        ConnectionStats s = new ConnectionStats();
        s.onConnect();
        s.onDisconnect();
        s.onReconnect();
        assertThat(s.getLive()).isEqualTo(1);
        assertThat(s.getReconnects()).isEqualTo(1);
    }

    @Test
    void summaryWithTargetFormatsSlash() {
        ConnectionStats s = new ConnectionStats();
        s.setTarget(2000);
        s.onConnect();
        String line = s.summaryAndReset(10);
        assertThat(line).isEqualTo(
                "Connections [window 10s]: live=1/2000, peak=1, connects=1, disconnects=0, reconnects=0");
    }

    @Test
    void summaryWithoutTargetOmitsSlash() {
        ConnectionStats s = new ConnectionStats();
        s.onConnect();
        String line = s.summaryAndReset(10);
        assertThat(line).isEqualTo(
                "Connections [window 10s]: live=1, peak=1, connects=1, disconnects=0, reconnects=0");
    }

    @Test
    void summaryAndResetZeroesWindowCountersKeepsLiveAndPeakToLive() {
        ConnectionStats s = new ConnectionStats();
        s.onConnect();
        s.onConnect();
        s.onConnect();      // live=3, peak=3
        s.onDisconnect();   // live=2, disconnects=1
        s.onReconnect();    // live=3, reconnects=1
        String first = s.summaryAndReset(10);
        assertThat(first).isEqualTo(
                "Connections [window 10s]: live=3, peak=3, connects=3, disconnects=1, reconnects=1");
        // second window with no events: counters zeroed, live retained, peak reset to live
        String second = s.summaryAndReset(10);
        assertThat(second).isEqualTo(
                "Connections [window 10s]: live=3, peak=3, connects=0, disconnects=0, reconnects=0");
    }
}
