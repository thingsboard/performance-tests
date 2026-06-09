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
    void defaultFormatMatchesMasterBehavior() {
        assertThat(EntityNames.entityName(false, "DEFAULT", 2)).isEqualTo("DW00000002");
        assertThat(EntityNames.entityName(false, "DEFAULT", 12345678)).isEqualTo("DW12345678");
        assertThat(EntityNames.entityName(true, "DEFAULT", 0)).isEqualTo("GW00000000");
    }

    @Test
    void uuidFormatIsDeterministicValidUuidShapeForDevicesOnly() {
        String name = EntityNames.entityName(false, "UUID", 42);
        assertThat(name).isEqualTo("c1000000-0000-4000-8000-000000000042");
        assertThat(name).hasSize(36);
        assertThat(name).isEqualTo(EntityNames.entityName(false, "UUID", 42)); // deterministic
        assertThat(EntityNames.entityName(false, "UUID", 43)).isNotEqualTo(name); // unique per idx
        // gateways are NEVER renamed:
        assertThat(EntityNames.entityName(true, "UUID", 42)).isEqualTo("GW00000042");
    }
}
