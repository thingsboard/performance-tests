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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnounceStatsTest {

    @Test
    void reportsWindowDeltas() {
        AnnounceStats s = new AnnounceStats();
        for (int i = 0; i < 1600; i++) {
            s.onAcked();
        }
        s.onRetry();
        s.onRetry();
        s.onRetry();
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Gateway device announce [window 10s]: acked=1600, failed=0, retried=3, unconfirmed=0");
    }

    @Test
    void secondWindowReportsDeltaOnly() {
        AnnounceStats s = new AnnounceStats();
        s.onAcked();
        s.summaryAndReset(10);
        s.onAttemptFailed();
        s.onUnconfirmed();
        assertThat(s.summaryAndReset(10)).isEqualTo(
                "Gateway device announce [window 10s]: acked=0, failed=1, retried=0, unconfirmed=1");
    }
}
