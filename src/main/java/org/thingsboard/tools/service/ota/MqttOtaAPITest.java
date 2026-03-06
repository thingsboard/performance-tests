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
package org.thingsboard.tools.service.ota;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.OtaPackageInfo;
import org.thingsboard.server.common.data.id.OtaPackageId;
import org.thingsboard.server.common.data.ota.ChecksumAlgorithm;
import org.thingsboard.server.common.data.ota.OtaPackageType;
import org.thingsboard.tools.service.device.DeviceAPITest;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "test", value = "api", havingValue = "ota")
public class MqttOtaAPITest extends BaseMqttAPITest implements DeviceAPITest {

    @Value("${ota.firmwareTitle:OTA_PERF_TEST}")
    private String firmwareTitle;

    @Value("${ota.firmwareVersion:1.0}")
    private String firmwareVersion;

    @Value("${ota.firmwareSize:1048576}")
    private int firmwareSize;

    @Value("${ota.chunkSize:51200}")
    private int chunkSize;

    private final List<OtaDeviceState> otaDeviceStates = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicLong totalBytesDownloaded = new AtomicLong();

    private volatile OtaPackageId otaPackageId;
    private volatile long testStartTime;

    // === Firmware package management ===

    public void createFirmwarePackage() throws Exception {
        DeviceProfile profile = deviceProfileManager.getByName(payloadType);

        OtaPackageInfo otaInfo = new OtaPackageInfo();
        otaInfo.setDeviceProfileId(profile.getId());
        otaInfo.setType(OtaPackageType.FIRMWARE);
        otaInfo.setTitle(firmwareTitle);
        otaInfo.setVersion(firmwareVersion);

        OtaPackageInfo savedInfo = restClientService.getRestClient().saveOtaPackageInfo(otaInfo, false);

        byte[] firmwareData = new byte[firmwareSize];
        new Random(42L).nextBytes(firmwareData);

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(firmwareData);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }

        OtaPackageInfo saved = restClientService.getRestClient()
                .saveOtaPackageData(savedInfo.getId(), sb.toString(), ChecksumAlgorithm.MD5, "firmware.bin", firmwareData);

        this.otaPackageId = saved.getId();
        log.info("Created firmware package '{}' v{}, size={} bytes, id={}",
                firmwareTitle, firmwareVersion, firmwareSize, otaPackageId);
    }

    public void deleteFirmwarePackage() {
        if (otaPackageId == null) {
            return;
        }
        try {
            DeviceProfile profile = restClientService.getRestClient()
                    .getDeviceProfileById(deviceProfileManager.getByName(payloadType).getId())
                    .orElse(null);
            if (profile != null && otaPackageId.equals(profile.getFirmwareId())) {
                profile.setFirmwareId(null);
                restClientService.getRestClient().saveDeviceProfile(profile);
                log.info("Unassigned firmware from device profile '{}'", payloadType);
            }
            restClientService.getRestClient().deleteOtaPackage(otaPackageId);
            log.info("Deleted firmware package {}", otaPackageId);
        } catch (Exception e) {
            log.warn("Failed to delete firmware package {}", otaPackageId, e);
        }
    }

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
        List<String> devicesNames = buildDeviceNames();
        List<String> pack = null;
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
        setupOtaSubscriptions();
    }

    @Override
    public void generationX509() {
    }

    // === Test execution ===

    @Override
    public void runApiTests() throws InterruptedException {
        int totalDevices = otaDeviceStates.size();
        log.info("Starting OTA firmware update test for {} devices, firmware size={} bytes, chunk size={} bytes",
                totalDevices, firmwareSize, chunkSize);

        testStartTime = System.currentTimeMillis();
        triggerOtaUpdate();

        long timeoutMs = (long) testDurationInSec * 1000;

        var logFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            int done = completedCount.get();
            int failed = failedCount.get();
            int inProgress = totalDevices - done - failed;
            long elapsed = System.currentTimeMillis() - testStartTime;
            double throughputMBps = elapsed > 0
                    ? totalBytesDownloaded.get() / 1024.0 / 1024.0 / (elapsed / 1000.0)
                    : 0;
            log.info("OTA progress [{} sec]: completed={}/{}, failed={}/{}, inProgress={}/{}, throughput={} MB/s",
                    elapsed / 1000, done, totalDevices, failed, totalDevices, inProgress, totalDevices,
                    String.format("%.2f", throughputMBps));
        }, 5, 5, TimeUnit.SECONDS);

        while (completedCount.get() + failedCount.get() < totalDevices) {
            if (System.currentTimeMillis() - testStartTime > timeoutMs) {
                log.warn("OTA test reached duration limit ({} sec). Completed: {}/{}, Failed: {}/{}",
                        testDurationInSec, completedCount.get(), totalDevices, failedCount.get(), totalDevices);
                break;
            }
            Thread.sleep(1000);
        }

        logFuture.cancel(true);

        long totalTime = System.currentTimeMillis() - testStartTime;
        long totalBytes = totalBytesDownloaded.get();
        double throughputMBps = totalTime > 0 ? totalBytes / 1024.0 / 1024.0 / (totalTime / 1000.0) : 0;

        log.info("OTA test finished in {} ms. Devices: total={}, completed={}, failed={}, timedOut={}. " +
                        "Total bytes downloaded: {}, Throughput: {} MB/s",
                totalTime, totalDevices, completedCount.get(), failedCount.get(),
                totalDevices - completedCount.get() - failedCount.get(),
                totalBytes, String.format("%.2f", throughputMBps));
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
        log.debug("[{}] Message published to {}", iteration, client.getDeviceName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.warn("[{}] Failed to publish to {}: {}", iteration, client.getDeviceName(), future.cause().getMessage());
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        testDurationLatch.countDown();
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

    private void setupOtaSubscriptions() throws InterruptedException {
        log.info("Setting up OTA subscriptions for {} devices...", mqttClients.size());
        CountDownLatch latch = new CountDownLatch(mqttClients.size());

        for (MqttClient client : mqttClients) {
            String deviceName = client.getClientConfig().getUsername();
            OtaDeviceState state = new OtaDeviceState(client, deviceName);
            otaDeviceStates.add(state);

            client.on("v2/fw/response/+/chunk/+", (topic, payload) -> {
                handleFirmwareChunk(state, payload);
                return CompletableFuture.completedFuture(null);
            }, MqttQoS.AT_LEAST_ONCE);

            client.on("v1/devices/me/attributes", (topic, payload) -> {
                handleAttributeUpdate(state, payload);
                return CompletableFuture.completedFuture(null);
            }, MqttQoS.AT_LEAST_ONCE).addListener(f -> latch.countDown());
        }

        boolean allSubscribed = latch.await(30, TimeUnit.SECONDS);
        if (!allSubscribed) {
            log.warn("Not all devices subscribed to OTA topics within 30s, continuing anyway...");
        }
        log.info("OTA subscriptions ready for {} devices", mqttClients.size());
    }

    private void triggerOtaUpdate() {
        DeviceProfile profile = restClientService.getRestClient()
                .getDeviceProfileById(deviceProfileManager.getByName(payloadType).getId())
                .orElseThrow(() -> new RuntimeException("Device profile not found: " + payloadType));
        profile.setFirmwareId(otaPackageId);
        restClientService.getRestClient().saveDeviceProfile(profile);
        log.info("Firmware '{}' v{} assigned to profile '{}'. OTA triggered for {} devices.",
                firmwareTitle, firmwareVersion, payloadType, otaDeviceStates.size());
    }

    private void handleAttributeUpdate(OtaDeviceState state, ByteBuf payload) {
        if (state.started) {
            return;
        }
        try {
            String json = payload.toString(StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(json);
            if (node.has("fw_title") && node.has("fw_version")) {
                state.fwTitle = node.get("fw_title").asText();
                state.fwVersion = node.get("fw_version").asText();
                state.fwSize = node.has("fw_size") ? node.get("fw_size").asInt() : firmwareSize;
                state.started = true;
                state.startTime = System.currentTimeMillis();
                publishTelemetry(state.mqttClient, "{\"fw_state\":\"DOWNLOADING\"}");
                requestChunk(state);
                log.debug("[{}] OTA started: fw='{}' v='{}' size={}",
                        state.deviceName, state.fwTitle, state.fwVersion, state.fwSize);
            }
        } catch (Exception e) {
            log.warn("[{}] Error handling attribute update", state.deviceName, e);
        }
    }

    private void handleFirmwareChunk(OtaDeviceState state, ByteBuf payload) {
        if (state.completed || state.failed) {
            return;
        }
        try {
            int chunkBytes = payload.readableBytes();
            if (chunkBytes > 0) {
                state.downloadedBytes += chunkBytes;
                totalBytesDownloaded.addAndGet(chunkBytes);
                state.chunkIndex++;
                requestChunk(state);
            } else {
                onDownloadComplete(state);
            }
        } catch (Exception e) {
            log.warn("[{}] Error handling firmware chunk", state.deviceName, e);
            state.failed = true;
            failedCount.incrementAndGet();
        }
    }

    private void requestChunk(OtaDeviceState state) {
        String topic = "v2/fw/request/" + state.requestId + "/chunk/" + state.chunkIndex;
        byte[] payload = String.valueOf(chunkSize).getBytes(StandardCharsets.UTF_8);
        state.mqttClient.publish(topic, Unpooled.wrappedBuffer(payload), MqttQoS.AT_MOST_ONCE);
    }

    private void onDownloadComplete(OtaDeviceState state) {
        state.completed = true;
        long downloadTime = System.currentTimeMillis() - state.startTime;
        publishTelemetry(state.mqttClient, "{\"fw_state\":\"DOWNLOADED\"}");
        publishTelemetry(state.mqttClient, "{\"fw_state\":\"VERIFIED\"}");
        publishTelemetry(state.mqttClient, "{\"fw_state\":\"UPDATING\"}");
        publishTelemetry(state.mqttClient, String.format(
                "{\"fw_state\":\"UPDATED\",\"current_fw_title\":\"%s\",\"current_fw_version\":\"%s\"}",
                state.fwTitle, state.fwVersion));
        completedCount.incrementAndGet();
        log.debug("[{}] OTA completed in {} ms, {} bytes in {} chunks",
                state.deviceName, downloadTime, state.downloadedBytes, state.chunkIndex);
    }

    private void publishTelemetry(MqttClient client, String json) {
        client.publish("v1/devices/me/telemetry",
                Unpooled.wrappedBuffer(json.getBytes(StandardCharsets.UTF_8)),
                MqttQoS.AT_MOST_ONCE);
    }

    // === OTA device state ===

    static class OtaDeviceState {
        final MqttClient mqttClient;
        final String deviceName;

        volatile String fwTitle;
        volatile String fwVersion;
        volatile int fwSize;
        volatile int downloadedBytes;
        volatile boolean started = false;
        volatile boolean completed = false;
        volatile boolean failed = false;
        volatile long startTime;

        int requestId = 1;
        int chunkIndex = 0;

        OtaDeviceState(MqttClient mqttClient, String deviceName) {
            this.mqttClient = mqttClient;
            this.deviceName = deviceName;
        }
    }
}
