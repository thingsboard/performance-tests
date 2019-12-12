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
package org.thingsboard.tools.service.gateway;

import java.util.List;

public interface GatewayAPITest {

    void createDevices() throws Exception;

    void createGateways() throws Exception;

    void removeDevices() throws Exception;

    void removeGateways() throws Exception;

    void connectGateways() throws InterruptedException;

    void warmUpDevices() throws InterruptedException;

    void runApiTests() throws InterruptedException;

    List<DeviceGatewayClient> getDevices();

}
