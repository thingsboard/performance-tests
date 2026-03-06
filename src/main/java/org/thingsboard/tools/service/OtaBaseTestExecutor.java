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
package org.thingsboard.tools.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.ota.MqttOtaAPITest;
import org.thingsboard.tools.service.shared.BaseTestExecutor;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "test", value = "api", havingValue = "ota")
public class OtaBaseTestExecutor extends BaseTestExecutor {

    @Value("${test.exitAfterComplete:true}")
    boolean exitAfterComplete;

    @Autowired
    private MqttOtaAPITest otaAPITest;

    @Override
    protected void initEntities() throws Exception {
        if (deviceCreateOnStart) {
            otaAPITest.createDevices();
        }
        otaAPITest.createFirmwarePackage();

        if (testEnabled) {
            otaAPITest.connectDevices();
        }

        if (warmupEnabled && testEnabled) {
            otaAPITest.warmUpDevices();
        }
    }

    @Override
    protected void runApiTests() throws InterruptedException {
        otaAPITest.runApiTests();
    }

    @Override
    protected void cleanUpEntities() throws Exception {
        if (deviceDeleteOnComplete) {
            otaAPITest.removeDevices();
        }
        otaAPITest.deleteFirmwarePackage();
    }

    @Override
    protected void waitOtherClients() throws Exception {
        if (testEnabled) {
            log.info("OTA test completed. Waiting for other clients to complete!");
            while (!exitAfterComplete) {
                try {
                    log.info("If all clients done, please execute next command: 'kubectl delete statefulset tb-performance-run'");
                    TimeUnit.SECONDS.sleep(10);
                } catch (InterruptedException e) {
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
        }
    }
}
