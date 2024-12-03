/**
 * Copyright © 2016-2022 The Thingsboard Authors
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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;
import org.thingsboard.tools.service.shared.CloudEventRestClientService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.thingsboard.tools.service.msg.smartMeter.SmartMeterTelemetryGenerator.CREATE_TIME;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "events_MQTT")
public class CloudEventAPITest extends BaseMqttAPITest implements DeviceAPITest {
    private static final AtomicInteger SUCCESS_MESSAGES_SENT_COUNTER = new AtomicInteger();
    private static final AtomicInteger FAILED_MESSAGE_SENT_COUNTER = new AtomicInteger();
    private static final AtomicInteger SUCCESS_RENAME_DEVICE_COUNTER = new AtomicInteger();
    private static final AtomicInteger FAILED_RENAME_DEVICE_COUNTER = new AtomicInteger();
    private static RestClient sourceClient;
    private static RestClient targetClient;

    private AtomicInteger waitTSCounter;
    private CountDownLatch tsDurationLatch;
    private CountDownLatch changeNameDurationLatch;
    private Integer countOfAllTSMessage;

    @Value("${rest.target_url}")
    protected String targetUrl;
    @Value("${rest.username}")
    protected String username;
    @Value("${rest.password}")
    protected String password;
    @Value("${device.check_attribute_delay}")
    protected Integer checkAttributeDelay;
    @Value("${rest.wait_ts_count}")
    protected Integer waitTsCount;
    @Value("${rest.source_type}")
    protected String sourceType;
    @Value("${rest.edge_id:}")
    protected String edgeStringId;

    @Override
    public void createDevices() throws Exception {
        initDeviceSuffix(sourceType.equals("CLOUD") ? "_TB" : "_" + edgeStringId);
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        List<DeviceId> entityIds = devices.stream().map(IdBased::getId).collect(Collectors.toList());
        String typeDevice = "devices";

        removeFromCloud(entityIds, typeDevice);
    }

    private void removeFromCloud(List<DeviceId> entityIds, String typeDevice) throws InterruptedException {
        if (sourceType.equals("CLOUD")) {
            removeEntities(sourceClient, entityIds, typeDevice);
        } else {
            removeEntities(targetClient, entityIds, typeDevice);
        }
    }

    @Override
    public void runApiTests() throws InterruptedException {
        prepareParameters();
        prepareClients();
        startPerformanceTest();
        waitTSMessage();
    }

    private void prepareParameters() {
        waitTSCounter = new AtomicInteger(waitTsCount);
        tsDurationLatch = new CountDownLatch(testDurationInSec);
        countOfAllTSMessage = testDurationInSec * testMessagesPerSecond;
        changeNameDurationLatch = new CountDownLatch(testDurationInSec / checkAttributeDelay);
    }

    private void prepareClients() {
        sourceClient = restClientService.getRestClient();
        targetClient = new RestClient(targetUrl);
        targetClient.login(username, password);

        if (sourceType.equals("CLOUD")) {
            assignDevicesToEdge();
        }
    }

    private void assignDevicesToEdge() {
        EdgeId edgeId = new EdgeId(UUID.fromString(edgeStringId));
        Edge edge = sourceClient.getEdgeById(edgeId).orElseThrow(() -> new RuntimeException("Not found Edge by edge id in TB"));

        for (Device device : devices) {
            sourceClient.assignDeviceToEdge(edge.getId(), device.getId());
        }
    }

    private void startPerformanceTest() throws InterruptedException {
        log.info("Starting performance test for {} devices...", deviceCount);

        for (int i = 0; i < testDurationInSec; i++) {
            int iterationNumber = i;
            restClientService.getScheduler().schedule(() -> runApiTestIteration(iterationNumber), i, TimeUnit.SECONDS);
            scheduleChangeNameMessage(i, iterationNumber);
        }

        log.info("All iterations has been scheduled. Awaiting all iteration completion...");
        tsDurationLatch.await((long) ((testDurationInSec + checkAttributeDelay) * 1.2), TimeUnit.SECONDS);
        changeNameDurationLatch.await((long) ((testDurationInSec + checkAttributeDelay) * 1.2), TimeUnit.SECONDS);
        log.info("Completed performance iteration. Success Sent: {}, Failed Sent: {}, Success Rename: {}, Failed Rename: {}",
                SUCCESS_MESSAGES_SENT_COUNTER.get(), FAILED_MESSAGE_SENT_COUNTER.get(), SUCCESS_RENAME_DEVICE_COUNTER.get(), FAILED_RENAME_DEVICE_COUNTER.get());
    }

    protected void runApiTestIteration(int iteration) {
        try {
            log.info("[{} seconds] Starting performance iteration for {} {}...", iteration, mqttClients.size(), "devices");

            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();

            sendTsMessage(iteration, successPublishedCount, failedPublishedCount, iterationLatch);

            iterationLatch.await();

            log.info("[{}] Completed performance iteration. Success: {}, Failed: {}", iteration, successPublishedCount.get(), failedPublishedCount.get());
            tsDurationLatch.countDown();
        } catch (Throwable t) {
            log.warn("[{}] Failed to process iteration", iteration, t);
        }
    }

    private void sendTsMessage(int iteration, AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount, CountDownLatch iterationLatch) {
        int deviceCount = deviceClients.size();
        int msgCount = iteration * testMessagesPerSecond % deviceCount;
        for (int i = 0; i < testMessagesPerSecond; i++) {
            int index = msgCount % deviceCount;
            DeviceClient client = deviceClients.get(index);
            Msg message = getNextMessage(client.getDeviceName(), false);
            sendMessageToDevice(iteration, client, message, successPublishedCount, failedPublishedCount, iterationLatch);

            msgCount++;
        }
    }

    private void sendMessageToDevice(int iteration, DeviceClient client, Msg message, AtomicInteger successPublishedCount,
                                     AtomicInteger failedPublishedCount, CountDownLatch iterationLatch) {
        Future<Void> futurePublish = client.getMqttClient()
                .publish(getTestTopic(), Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_MOST_ONCE)
                .addListener(future -> handleResult(iteration, client, successPublishedCount, failedPublishedCount, iterationLatch, future));

        restClientService.getWorkers().submit(() -> futurePublish);
    }

    private void handleResult(int iteration, DeviceClient client, AtomicInteger successPublishedCount, AtomicInteger failedPublishedCount,
                              CountDownLatch iterationLatch, Future<? super Void> future) {
        if (future.isSuccess()) {
            SUCCESS_MESSAGES_SENT_COUNTER.incrementAndGet();
            successPublishedCount.incrementAndGet();
            logSuccessTestMessage(iteration, client);
        } else {
            FAILED_MESSAGE_SENT_COUNTER.incrementAndGet();
            failedPublishedCount.incrementAndGet();
            logFailureTestMessage(iteration, client, future);
        }

        iterationLatch.countDown();
    }

    private void scheduleChangeNameMessage(int i, int iterationNumber) {
        CloudEventRestClientService cloudEventRestClientService = ((CloudEventRestClientService) restClientService);

        if (i % checkAttributeDelay == 0) {
            cloudEventRestClientService.getChangeNameScheduler().schedule(() -> sendCloudEventMessage(iterationNumber), i, TimeUnit.SECONDS);
        }
    }

    private void sendCloudEventMessage(int iteration) {
        try {
            Device deviceWithNewName = changeDeviceName(iteration);
            checkDeviceName(deviceWithNewName);
            changeNameDurationLatch.countDown();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Device changeDeviceName(int iteration) throws InterruptedException {
        Device device = devices.get(iteration);
        log.info("[{}] Starting change Device name - {}", iteration, device.getName());

        device.setName(device.getName() + "_" + iteration);
        sourceClient.saveDevice(device);

        log.info("[{}] Completed change Device name - {}", iteration, device.getName());

        TimeUnit.SECONDS.sleep(checkAttributeDelay);

        return device;
    }

    private void checkDeviceName(Device deviceWithNewName) {
        Optional<Device> cloudDevice = targetClient.getDeviceById(deviceWithNewName.getId());

        if (cloudDevice.isPresent() && cloudDevice.get().getName().equals(deviceWithNewName.getName())) {
            log.info("Success check Device Name - {}", cloudDevice.get().getName());
            SUCCESS_RENAME_DEVICE_COUNTER.incrementAndGet();
        } else {
            log.info("Failed check Device Name - {}", deviceWithNewName.getName());
            FAILED_RENAME_DEVICE_COUNTER.incrementAndGet();
        }
    }

    private void waitTSMessage() throws InterruptedException {
        AtomicInteger findTsMessage = new AtomicInteger();
        String key = "batteryLevel";
        long endTime = CREATE_TIME + (long) countOfAllTSMessage;
        long tsMessageByDevice = countOfAllTSMessage / devices.size();
        long leftover = countOfAllTSMessage % devices.size();

        for (Device device : devices) {
            List<TsKvEntry> deviceTs = targetClient.getTimeseries(device.getId(), Collections.singletonList(key), 1000L,
                    null, null, CREATE_TIME, endTime, Integer.MAX_VALUE, true);

            if (deviceTs.size() == tsMessageByDevice) {
                findTsMessage.addAndGet(deviceTs.size());
            } else if(deviceTs.size() == tsMessageByDevice + 1 && leftover > 0) {
                leftover--;
                findTsMessage.addAndGet(deviceTs.size());
            } else{
                log.info("deviceTs size - {}, tsMessageByDevice - {}", deviceTs.size(), tsMessageByDevice);
                log.info("The TS check was missed because not everyone has arrived yet.");
                break;
            }
        }

        waitAndRetry(findTsMessage);
    }

    private void waitAndRetry(AtomicInteger findTsMessage) throws InterruptedException {
        if (countOfAllTSMessage != findTsMessage.get() && waitTSCounter.get() != 1) {
            log.info("Need wait before try check TS again - " + 10 + " seconds");

            TimeUnit.SECONDS.sleep(10);
            waitTSCounter.decrementAndGet();

            waitTSMessage();
        } else {
            log.info("Sent TS: {}, Find TS: {}", countOfAllTSMessage, findTsMessage.get());
        }
    }

    @Override
    protected String getWarmUpTopic() {
        return "v1/devices/me/telemetry";
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
        List<String> devicesNames = prepareDevicesNames();

        connectDevicesBySequence(devicesNames, totalConnectedCount);
        mapDevicesToDeviceClientConnections();

        TimeUnit.SECONDS.sleep(5);
    }

    private List<String> prepareDevicesNames() {
        if (!devices.isEmpty()) {
            return devices.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            List<String> devicesNames = new ArrayList<>();

            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
                devicesNames.add(getToken(false, i));
            }

            return devicesNames;
        }
    }

    private void connectDevicesBySequence(List<String> devicesNames, AtomicInteger totalConnectedCount) throws InterruptedException {
        List<String> pack = null;

        for (String device : devicesNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }

            pack.add(device);

            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, false);
                TimeUnit.SECONDS.sleep(1 + random.nextInt(10));
                pack = null;
            }
        }

        if (pack != null) {
            connectDevices(pack, totalConnectedCount, false);
        }
    }

    private void mapDevicesToDeviceClientConnections() {
        mqttClients.forEach(this::fillDeviceClients);

        log.info("Sorting device clients...");
        deviceClients.sort(Comparator.comparing(DeviceClient::getDeviceName));
        log.info("Shuffling device clients...");
        Collections.shuffle(deviceClients, random);
        log.info("Mapping devices to device client connections done");
    }

    private void fillDeviceClients(MqttClient mqttClient) {
        DeviceClient client = new DeviceClient();
        client.setMqttClient(mqttClient);
        client.setDeviceName(mqttClient.getClientConfig().getUsername());
        deviceClients.add(client);
    }

    @Override
    public void generationX509() {

    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        throw new UnsupportedOperationException("Not supported.");
    }

}