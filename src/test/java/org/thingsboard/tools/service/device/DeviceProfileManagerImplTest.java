/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
package org.thingsboard.tools.service.device;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

@Slf4j
class DeviceProfileManagerImplTest {

    DeviceProfileManagerImpl deviceProfileManager;

    @BeforeEach
    void setUp() {
        deviceProfileManager = spy(DeviceProfileManagerImpl.class);
    }

    @Test
    void testDeviceProfileResourceFiles() throws IOException {
        List<String> files = deviceProfileManager.getFiles();
        assertThat(files).contains("smart_meter.json", "smart_tracker.json", "industrial_plc.json");
    }

    @Test
    void testDeviceProfileResourceFile() throws IOException {
        String fileContent = deviceProfileManager.getFile("smart_meter.json");
        assertThat(fileContent).isNotEmpty();
    }

}
