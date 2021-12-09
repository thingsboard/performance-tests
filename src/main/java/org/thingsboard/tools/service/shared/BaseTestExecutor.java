/**
 * Copyright © 2016-2021 The Thingsboard Authors
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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.dashboard.DefaultDashboardManager;
import org.thingsboard.tools.service.rule.RuleChainManager;

import javax.annotation.PostConstruct;

@Slf4j
public abstract class BaseTestExecutor {
    @Value("${dashboard.createOnStart}")
    private boolean dashboardCreateOnStart;

    @Value("${dashboard.deleteOnComplete}")
    private boolean dashboardDeleteOnComplete;

    @Value("${customer.createOnStart}")
    private boolean customerCreateOnStart;

    @Value("${customer.deleteOnComplete}")
    private boolean customerDeleteOnComplete;

    @Value("${device.createOnStart}")
    protected boolean deviceCreateOnStart;

    @Value("${device.deleteOnComplete}")
    protected boolean deviceDeleteOnComplete;

    @Value("${warmup.enabled:true}")
    protected boolean warmupEnabled;

    @Value("${test.enabled:true}")
    protected boolean testEnabled;

    @Value("${test.updateRootRuleChain:true}")
    protected boolean updateRootRuleChain;

    @Value("${test.revertRootRuleChain:true}")
    protected boolean revertRootRuleChain;

    @Value("${device.api}")
    private String deviceAPIType;

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

        initEntities();

        if (updateRootRuleChain) {
            ruleChainManager.createRuleChainWithCountNodeAndSetAsRoot();
        }

        if (testEnabled) {
            runApiTests();
        }

        if (revertRootRuleChain) {
            Thread.sleep(3000); // wait for messages delivery before removing rule chain

            ruleChainManager.revertRootNodeAndCleanUp();
        }

        cleanUpEntities();

        if (customerDeleteOnComplete) {
            customerManager.removeCustomers();
        }
        if (dashboardDeleteOnComplete) {
            dashboardManager.removeDashboards();
        }
        waitOtherClients();
    }

    protected abstract void initEntities() throws Exception;

    protected abstract void runApiTests() throws InterruptedException;

    protected abstract void cleanUpEntities() throws Exception;

    protected abstract void waitOtherClients() throws Exception;
}
