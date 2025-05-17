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
package org.thingsboard.tools.service.gateway;

import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "MQTT")
public class MqttGatewayAPITest extends BaseMqttAPITest implements GatewayAPITest {

    @Value("${gateway.startIdx}")
    int gatewayStartIdxConfig;
    @Value("${gateway.endIdx}")
    int gatewayEndIdxConfig;
    @Value("${gateway.count}")
    int gatewayCount;

    private List<Device> gateways = Collections.synchronizedList(new ArrayList<>(1024));

    private int gatewayStartIdx;
    private int gatewayEndIdx;


    @PostConstruct
    protected void init() {
        super.init();
        if (this.useInstanceIdx) {
            this.gatewayStartIdx = this.gatewayCount * this.instanceIdx;
            this.gatewayEndIdx = this.gatewayStartIdx + this.gatewayCount;
        } else {
            this.gatewayStartIdx = this.gatewayStartIdxConfig;
            this.gatewayEndIdx = this.gatewayEndIdxConfig;
        }
        log.info("Initialized with gatewayStartIdx [{}], gatewayEndIdx [{}]", this.gatewayStartIdx, this.gatewayEndIdx);
    }

    @Override
    public void createDevices() throws Exception {
        createDevices(false);
    }

    @Override
    public void createGateways() throws Exception {
        List<Device> entities = createEntities(gatewayStartIdx, gatewayEndIdx, true,true);
        gateways = Collections.synchronizedList(entities);
    }

    @Override
    public void connectGateways() throws InterruptedException {
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> gatewayNames;
        if (!gateways.isEmpty()) {
            gatewayNames = gateways.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            gatewayNames = new ArrayList<>();
            for (int i = gatewayStartIdx; i < gatewayEndIdx; i++) {
                gatewayNames.add(getToken(true, i));
            }
        }
        for (String gateway : gatewayNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(gateway);
            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, true);
                Thread.sleep(100 + new Random().nextInt(100));
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectDevices(pack, totalConnectedCount, true);
        }
        reportScheduledFuture = restClientService.getScheduler().scheduleAtFixedRate(this::reportMqttClientsStats, 300, 300, TimeUnit.SECONDS);
        mapDevicesToGatewayClientConnections();
    }

    private void mapDevicesToGatewayClientConnections() {
        int gatewayCount = mqttClients.size();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int deviceIdx = i - deviceStartIdx;
            int gatewayIdx = deviceIdx % gatewayCount;
            DeviceClient client = new DeviceClient();
            client.setMqttClient(mqttClients.get(gatewayIdx));
            client.setDeviceName(getToken(false, i));
            client.setGatewayName(getToken(true, gatewayIdx));
            deviceClients.add(client);
        }
    }

    @Override
    public void runApiTests() throws InterruptedException {
        super.runApiTests(deviceClients.size());
    }


    @Override
    protected String getWarmUpTopic() {
        return "v1/gateway/connect";
    }

    @Override
    protected byte[] getData(String deviceName) {
        return ("{\"device\":\"" + deviceName + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        runApiTestIteration(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, testDurationLatch, true);
    }

    @Override
    protected String getTestTopic() {
        return telemetryTest ? "v1/gateway/telemetry" : "v1/gateway/attributes";
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] Message was successfully published to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.error("[{}] Error while publishing message to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName(),
                future.cause());
    }

    @Override
    public void removeGateways() throws Exception {
        removeEntities(gateways.stream().map(Device::getId).collect(Collectors.toList()), "gateways");
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), "devices");
    }
}
