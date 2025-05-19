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

import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "MQTT")
public class MqttDeviceAPITest extends BaseMqttAPITest implements DeviceAPITest {

    @Override
    public void createDevices() throws Exception {
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(restClientService.getRestClient(), devices.stream().map(IdBased::getId).collect(Collectors.toList()), "devices");
    }

    @Override
    public void runApiTests() throws InterruptedException {
        super.runApiTests(mqttClients.size());
    }

    @Override
    protected String getWarmUpTopic() {
        return "v1/devices/me/telemetry";
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        runApiTestIteration(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, testDurationLatch, false);
    }

    @Override
    protected String getTestTopic() {
        return telemetryTest ? "v1/devices/me/telemetry" : "v1/devices/me/attributes";
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] Message was successfully published to device: {}", iteration, client.getDeviceName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.error("[{}] Error while publishing message to device: [{}] {}", iteration, client.getDeviceName(), future.cause().getMessage());
    }

    @Override
    public void connectDevices() throws InterruptedException {
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> devicesNames;
        if (!devices.isEmpty()) {
            devicesNames = devices.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            devicesNames = new ArrayList<>();
            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
                devicesNames.add(getToken(false, i));
            }
        }
        for (String device : devicesNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(device);
            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, false);
                Thread.sleep(1 + random.nextInt(100));
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectDevices(pack, totalConnectedCount, false);
        }
        mapDevicesToDeviceClientConnections();
    }

    @Override
    public void generationX509() {

    }

    private void mapDevicesToDeviceClientConnections() {
        for (MqttClient mqttClient : mqttClients) {
            DeviceClient client = new DeviceClient();
            client.setMqttClient(mqttClient);
            client.setDeviceName(mqttClient.getClientConfig().getUsername());
            deviceClients.add(client);
        }
        log.info("Sorting device clients...");
        deviceClients.sort(Comparator.comparing(DeviceClient::getDeviceName));
        log.info("Shuffling device clients...");
        Collections.shuffle(deviceClients, random);
        log.info("Mapping devices to device client connections done");
    }
}
