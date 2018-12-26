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
package org.thingsboard.tools.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.device.DeviceAPITest;
import org.thingsboard.tools.service.rule.RuleChainManager;
import org.thingsboard.tools.service.stats.StatisticsCollector;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class TestExecutor {

    @Value("${device.createOnStart}")
    private boolean deviceCreateOnStart;

    @Value("${device.deleteOnComplete}")
    private boolean deviceDeleteOnComplete;

    @Value("${publish.count}")
    private int publishTelemetryCount;

    @Value("${publish.pause}")
    private int publishTelemetryPause;

    @Value("${device.api}")
    private String deviceAPIType;

    @Autowired
    private StatisticsCollector statisticsCollector;

    @Autowired
    private DeviceAPITest deviceAPITest;

    @Autowired
    private RuleChainManager ruleChainManager;

    @PostConstruct
    public void init() throws Exception {
        if (deviceCreateOnStart) {
            deviceAPITest.createDevices();
        }

        deviceAPITest.warmUpDevices();

        ruleChainManager.createRuleChainWithCountNodeAndSetAsRoot();

        statisticsCollector.start();

        deviceAPITest.runApiTests(publishTelemetryCount, publishTelemetryPause);

        statisticsCollector.end();

        Thread.sleep(3000); // wait for messages delivery before removing rule chain

        ruleChainManager.revertRootNodeAndCleanUp();

        statisticsCollector.printResults();

        if (deviceDeleteOnComplete) {
            deviceAPITest.removeDevices();
        }
    }

}
