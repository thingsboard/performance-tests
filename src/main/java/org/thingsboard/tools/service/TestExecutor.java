/**
 * Copyright © 2016-2018 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
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
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.dashboard.DefaultDashboardManager;
import org.thingsboard.tools.service.gateway.GatewayAPITest;
import org.thingsboard.tools.service.rule.RuleChainManager;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class TestExecutor {
    @Value("${dashboard.createOnStart}")
    private boolean dashboardCreateOnStart;

    @Value("${dashboard.deleteOnComplete}")
    private boolean dashboardDeleteOnComplete;

    @Value("${customer.createOnStart}")
    private boolean customerCreateOnStart;

    @Value("${customer.deleteOnComplete}")
    private boolean customerDeleteOnComplete;

    @Value("${gateway.createOnStart}")
    private boolean gatewayCreateOnStart;

    @Value("${gateway.deleteOnComplete}")
    private boolean gatewayDeleteOnComplete;

    @Value("${device.createOnStart}")
    private boolean deviceCreateOnStart;

    @Value("${device.deleteOnComplete}")
    private boolean deviceDeleteOnComplete;

    @Value("${warmup.enabled:true}")
    private boolean warmupEnabled;

    @Value("${test.enabled:true}")
    private boolean testEnabled;

    @Value("${device.api}")
    private String deviceAPIType;

    @Autowired
    private GatewayAPITest gatewayAPITest;

    @Autowired
    private RuleChainManager ruleChainManager;

    @Autowired
    private DefaultDashboardManager dashboardManager;

    @Autowired
    private CustomerManager customerManager;

    @PostConstruct
    public void init() throws Exception {
        if (dashboardCreateOnStart) {
            dashboardManager.createDashboards();
        }
        if (customerCreateOnStart) {
            customerManager.createCustomers();
        }
        if (gatewayCreateOnStart) {
            gatewayAPITest.createGateways();
        }
        if (deviceCreateOnStart) {
            gatewayAPITest.createDevices();
        }

        if (testEnabled) {
            gatewayAPITest.connectGateways();
        }

        if (warmupEnabled) {
            gatewayAPITest.warmUpDevices();
        }

        if (testEnabled) {

            ruleChainManager.createRuleChainWithCountNodeAndSetAsRoot();

            gatewayAPITest.runApiTests();

            Thread.sleep(3000); // wait for messages delivery before removing rule chain

            ruleChainManager.revertRootNodeAndCleanUp();
        }

        if (deviceDeleteOnComplete) {
            gatewayAPITest.removeDevices();
        }
        if (gatewayDeleteOnComplete) {
            gatewayAPITest.removeGateways();
        }
        if (customerDeleteOnComplete) {
            customerManager.removeCustomers();
        }
        if (dashboardDeleteOnComplete) {
            dashboardManager.removeDashboards();
        }
    }

}
