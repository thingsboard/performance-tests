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

class EntityNamesTest {

    @Test
    void deviceName() {
        // DEFAULT format: DW + zero-padded index
        assertThat(EntityNames.toDeviceName("DEFAULT", 2)).isEqualTo("DW00000002");
        assertThat(EntityNames.toDeviceName("DEFAULT", 12345678)).isEqualTo("DW12345678");
        // UUID format: deterministic, valid 36-char UUID shape, unique per idx
        String uuid = EntityNames.toDeviceName("UUID", 42);
        assertThat(uuid).isEqualTo("c1000000-0000-4000-8000-000000000042").hasSize(36);
        assertThat(uuid).isEqualTo(EntityNames.toDeviceName("UUID", 42));
        assertThat(EntityNames.toDeviceName("UUID", 43)).isNotEqualTo(uuid);
    }

    @Test
    void gatewayName() {
        // null/empty prefix => legacy GW%08d
        assertThat(EntityNames.toGatewayName(null, 0)).isEqualTo("GW00000000");
        assertThat(EntityNames.toGatewayName("", 42)).isEqualTo("GW00000042");
        assertThat(EntityNames.toGatewayName(null, 7)).isEqualTo(EntityNames.toGatewayName("", 7));
        // non-empty prefix is prepended to the GW name (per-tenant uniqueness)
        assertThat(EntityNames.toGatewayName("A_", 7)).isEqualTo("A_GW00000007");
        assertThat(EntityNames.toGatewayName("tenant1-", 0)).isEqualTo("tenant1-GW00000000");
    }
}
