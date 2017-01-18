/**
 * Copyright © 2016-2017 The Thingsboard Authors
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
package org.thingsboard.client.tools;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.security.DeviceCredentials;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class MqttStressTestTool {

    private static byte[] data = "{\"longKey\":73}".getBytes(StandardCharsets.UTF_8);
    private static ResultAccumulator results = new ResultAccumulator();
    private static List<MqttStressTestClient> clients = new ArrayList<>();
    private static List<IMqttToken> connectTokens = new ArrayList<>();

    /**
     * Returns list of device credential IDs
     *
     * @param params
     * @return
     * @throws Exception
     */
    public static List<String> createDevices(TestParams params) throws Exception {
        AtomicLong value = new AtomicLong(Long.MAX_VALUE);
        log.info("value: {} ", value.incrementAndGet());

        RestClient restClient = new RestClient(params.getRestApiUrl());
        restClient.login(params.getUsername(), params.getPassword());

        List<String> deviceCredentialsIds = new ArrayList<>();
        for (int i = 0; i < params.getDeviceCount(); i++) {
            Device device = restClient.createDevice("Device " + UUID.randomUUID());
            DeviceCredentials credentials = restClient.getCredentials(device.getId());
            String[] mqttUrls = params.getMqttUrls();
            String mqttURL = mqttUrls[i % mqttUrls.length];
            MqttStressTestClient client = new MqttStressTestClient(results, mqttURL, credentials.getCredentialsId());

            deviceCredentialsIds.add(credentials.getCredentialsId());

            connectTokens.add(client.connect());
            clients.add(client);
        }

        for (IMqttToken tokens : connectTokens) {
            tokens.waitForCompletion();
        }

        for (MqttStressTestClient client : clients) {
            client.warmUp(data);
        }

        Thread.sleep(1000);

        for (MqttStressTestClient client : clients) {
            client.disconnect();
        }

        return deviceCredentialsIds;
    }
}
