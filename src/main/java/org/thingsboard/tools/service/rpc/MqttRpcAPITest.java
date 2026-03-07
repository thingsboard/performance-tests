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
package org.thingsboard.tools.service.rpc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.tools.service.device.DeviceAPITest;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "test", value = "api", havingValue = "rpc")
public class MqttRpcAPITest extends BaseMqttAPITest implements DeviceAPITest {

    private static final byte[] RPC_RESPONSE = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
    private static final String RPC_REQUEST_PREFIX = "v1/devices/me/rpc/request/";

    @Value("${rpc.twoWay:false}")
    private boolean rpcTwoWay;

    @Value("${rpc.method:setGpio}")
    private String rpcMethod;

    /** Map of device name → DeviceId for REST API calls */
    private final Map<String, DeviceId> deviceIdMap = new ConcurrentHashMap<>();

    // === DeviceAPITest lifecycle ===

    @Override
    public void createDevices() throws Exception {
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(Device::getId).collect(Collectors.toList()), "devices");
    }

    @Override
    public void connectDevices() throws InterruptedException {
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> devicesNames = buildDeviceNames();

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

        subscribeToRpcRequests();
        mapDevicesToClients();
    }

    @Override
    public void generationX509() {
    }

    // === Test execution ===

    @Override
    public void runApiTests() throws InterruptedException {
        buildDeviceIdMap();
        log.info("Starting RPC performance test: {} devices, {} RPS, {}s duration, twoWay={}",
                deviceClients.size(), testMessagesPerSecond, testDurationInSec, rpcTwoWay);
        super.runApiTests(deviceClients.size());
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        try {
            log.info("[{}] Starting RPC iteration for {} devices...", iteration, deviceClients.size());
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);

            ObjectNode requestBody = buildRpcRequestBody();
            int deviceCount = deviceClients.size();

            for (int i = 0; i < testMessagesPerSecond; i++) {
                int idx = (iteration * testMessagesPerSecond + i) % deviceCount;
                DeviceClient client = deviceClients.get(idx);
                DeviceId deviceId = deviceIdMap.get(client.getDeviceName());

                if (deviceId == null) {
                    log.warn("[{}] No deviceId for {}, skipping", iteration, client.getDeviceName());
                    iterationLatch.countDown();
                    continue;
                }

                restClientService.getWorkers().submit(() -> {
                    try {
                        if (rpcTwoWay) {
                            restClientService.getRestClient().handleTwoWayDeviceRPCRequest(deviceId, requestBody);
                        } else {
                            restClientService.getRestClient().handleOneWayDeviceRPCRequest(deviceId, requestBody);
                        }
                        totalSuccessPublishedCount.incrementAndGet();
                        successCount.incrementAndGet();
                        log.debug("[{}] RPC sent to device: {}", iteration, client.getDeviceName());
                    } catch (Exception e) {
                        totalFailedPublishedCount.incrementAndGet();
                        failedCount.incrementAndGet();
                        log.error("[{}] RPC failed for device {}: {}", iteration, client.getDeviceName(), e.getMessage());
                    } finally {
                        iterationLatch.countDown();
                    }
                });
            }

            iterationLatch.await();
            log.info("[{}] RPC iteration complete. Success: {}, Failed: {}",
                    iteration, successCount.get(), failedCount.get());
        } catch (Throwable t) {
            log.warn("[{}] Failed to process RPC iteration", iteration, t);
        } finally {
            testDurationLatch.countDown();
        }
    }

    // === BaseMqttAPITest required implementations ===

    @Override
    protected String getWarmUpTopic() {
        return "v1/devices/me/telemetry";
    }

    @Override
    protected byte[] getData(String deviceName) {
        return "{\"warmup\":1}".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected String getTestTopic() {
        return "v1/devices/me/telemetry";
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] RPC sent to {}", iteration, client.getDeviceName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.warn("[{}] RPC failed for {}: {}", iteration, client.getDeviceName(), future.cause().getMessage());
    }

    // === Private helpers ===

    private List<String> buildDeviceNames() {
        if (!devices.isEmpty()) {
            return devices.stream().map(Device::getName).collect(Collectors.toList());
        }
        List<String> names = new ArrayList<>();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            names.add(getToken(false, i));
        }
        return names;
    }

    private void subscribeToRpcRequests() throws InterruptedException {
        log.info("Subscribing {} devices to RPC requests...", mqttClients.size());
        CountDownLatch latch = new CountDownLatch(mqttClients.size());

        for (MqttClient client : mqttClients) {
            client.on("v1/devices/me/rpc/request/+", (topic, payload) -> {
                String requestId = topic.substring(topic.lastIndexOf('/') + 1);
                String responseTopic = "v1/devices/me/rpc/response/" + requestId;
                client.publish(responseTopic, Unpooled.wrappedBuffer(RPC_RESPONSE), MqttQoS.AT_MOST_ONCE);
                return CompletableFuture.completedFuture(null);
            }, MqttQoS.AT_MOST_ONCE).addListener(f -> latch.countDown());
        }

        boolean allSubscribed = latch.await(30, TimeUnit.SECONDS);
        if (!allSubscribed) {
            log.warn("Not all devices subscribed to RPC topics within 30s, continuing anyway...");
        }
        log.info("RPC subscriptions ready for {} devices", mqttClients.size());
    }

    private void mapDevicesToClients() {
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
    }

    private void buildDeviceIdMap() {
        for (Device device : devices) {
            deviceIdMap.put(device.getName(), device.getId());
        }
        if (deviceIdMap.isEmpty()) {
            log.info("Device list empty, looking up device IDs via REST...");
            for (DeviceClient client : deviceClients) {
                restClientService.getRestClient().findDevice(client.getDeviceName())
                        .ifPresent(d -> deviceIdMap.put(d.getName(), d.getId()));
            }
        }
        log.info("Device ID map built for {} devices", deviceIdMap.size());
    }

    private ObjectNode buildRpcRequestBody() {
        ObjectNode body = mapper.createObjectNode();
        body.put("method", rpcMethod);
        body.putObject("params").put("value", 1);
        return body;
    }
}
