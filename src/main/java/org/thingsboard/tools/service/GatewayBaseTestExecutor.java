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
package org.thingsboard.tools.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.gateway.GatewayAPITest;
import org.thingsboard.tools.service.shared.BaseTestExecutor;

@Service
@ConditionalOnProperty(prefix = "test", value = "api", havingValue = "gateway")
public class GatewayBaseTestExecutor extends BaseTestExecutor {

    @Value("${gateway.createOnStart}")
    private boolean gatewayCreateOnStart;

    @Value("${gateway.deleteOnComplete}")
    private boolean gatewayDeleteOnComplete;

    @Autowired
    private GatewayAPITest gatewayAPITest;

    @Override
    protected void initEntities() throws Exception {
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
    }

    @Override
    protected void runApiTests() throws InterruptedException {
        gatewayAPITest.runApiTests();
    }

    @Override
    protected void cleanUpEntities() throws Exception {
        if (deviceDeleteOnComplete) {
            gatewayAPITest.removeDevices();
        }
        if (gatewayDeleteOnComplete) {
            gatewayAPITest.removeGateways();
        }
    }

    @Override
    protected void waitOtherClients() throws Exception {

    }
}
