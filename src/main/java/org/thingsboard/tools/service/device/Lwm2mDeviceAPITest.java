/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.tools.service.shared.AbstractAPITest;
import org.thingsboard.tools.service.shared.BaseLwm2mAPITest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class Lwm2mDeviceAPITest extends BaseLwm2mAPITest implements DeviceAPITest {

    @Override
    public void createDevices() throws Exception {
        createDevices(true, true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), false);
    }

    @Override
    public void warmUpDevices() throws InterruptedException {

    }

    @Override
    public void runApiTests() throws InterruptedException {

    }

    @Override
    public void connectDevices() throws InterruptedException {

    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {

    }
}
