/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DeviceManager {

    private static final int CONNECT_TIMEOUT_IN_SECONDS = 5;
    private static byte[] data = "{\"longKey\":73}".getBytes(StandardCharsets.UTF_8);

    @Value("${device.startIdx}")
    private int deviceStartIdx;

    @Value("${device.endIdx}")
    private int deviceEndIdx;

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    @Value("${mqtt.host}")
    private String mqttHost;

    @Value("${mqtt.port}")
    private int mqttPort;

    private RestClient restClient;

    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    private final ScheduledExecutorService mqttPublishExecutor = Executors.newScheduledThreadPool(100);
    private final ExecutorService mqttExecutor = Executors.newFixedThreadPool(100);

    private final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());
    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>());

    private int deviceCount;

    @PostConstruct
    private void init() {
        deviceCount = deviceEndIdx - deviceStartIdx;
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
    }

    @PreDestroy
    private void destroy() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdown();
        }
        if (!this.mqttExecutor.isShutdown()) {
            this.mqttExecutor.shutdown();
        }
    }

    private MqttClient initClient(String token) throws Exception {
        MqttClientConfig config = new MqttClientConfig();
        config.setUsername(token);
        MqttClient client = MqttClient.create(config, null);
        Future<MqttConnectResult> connectFuture = client.connect(mqttHost, mqttPort);
        MqttConnectResult result;
        try {
            result = connectFuture.get(CONNECT_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s:%d.", mqttHost, mqttPort));
        }
        if (!result.isSuccess()) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("Failed to connect to MQTT broker at %s:%d. Result code is: %s", mqttHost, mqttPort, result.getReturnCode()));
        }
        return client;
    }

    public void createDevices() throws Exception {
        log.info("Creating {} devices...", deviceCount);
        CountDownLatch latch = new CountDownLatch(deviceCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                try {
                    Device device = restClient.createDevice("Device " + UUID.randomUUID(), "default");
                    String token = String.format("%20d", tokenNumber);
                    restClient.updateDeviceCredentials(device.getId(), token);
                    deviceIds.add(device.getId());
                } catch (Exception e) {
                    log.error("Error while creating device", e);
                } finally {
                    latch.countDown();
                    count.getAndIncrement();
                }
            });
        }
        latch.await();
        log.info("{} devices have been created successfully!", deviceCount);
    }

    public void removeDevices() throws Exception {
        log.info("Removing {} devices...", deviceIds.size());
        CountDownLatch latch = new CountDownLatch(deviceIds.size());
        AtomicInteger count = new AtomicInteger();
        for (DeviceId deviceId : deviceIds) {
            httpExecutor.submit(() -> {
                try {
                    restClient.getRestTemplate().delete(restUrl + "/api/device/" + deviceId.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting device", e);
                } finally {
                    latch.countDown();
                }
                if (count.get() > 0 && count.get() % 10 == 0) {
                    log.info("{} devices has been removed so far...", count.get());
                }
            });
        }
        latch.await();
        Thread.sleep(1000);
        log.info("{} devices have been removed successfully! {} were failed for removal!", count.get(), deviceIds.size() - count.get());
    }

    public void runTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException {
        log.info("Starting performance test for {} devices...", mqttClients.size());
        final int totalMessagesToPublish = mqttClients.size() * publishTelemetryCount;
        final String defaultClientId = mqttClients.get(0).getClientConfig().getClientId();
        CountDownLatch mqttClientsCountLatch = new CountDownLatch(mqttClients.size());
        AtomicInteger publishedCount = new AtomicInteger();
        for (MqttClient mqttClient : mqttClients) {
            mqttExecutor.submit(() -> {
                try {
                    CountDownLatch telemetryCountLatch = new CountDownLatch(publishTelemetryCount);
                    ScheduledFuture<?> scheduledFuture = mqttPublishExecutor.scheduleAtFixedRate(() -> {
                        try {
                            mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                                    .addListener(future -> {
                                                if (future.isSuccess()) {
                                                    log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                                } else {
                                                    log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                                }
                                                publishedCount.getAndIncrement();
                                            }
                                    );
                        } finally {
                            telemetryCountLatch.countDown();
                            if (publishedCount.get() > 0 && publishedCount.get() % 100 == 0) {
                                log.info("[{}] messages have been published. [{}] messages to publish. Total [{}].",
                                        publishedCount.get(), totalMessagesToPublish - publishedCount.get(), totalMessagesToPublish);
                            }
                        }
                    }, 0, publishTelemetryPause, TimeUnit.MILLISECONDS);
                    try {
                        telemetryCountLatch.await();
                    } catch (InterruptedException e) {
                        log.error("Exception while waiting", e);
                    }
                    scheduledFuture.cancel(true);
                } finally {
                    mqttClientsCountLatch.countDown();
                }
            });
        }
        mqttClientsCountLatch.await();
        log.info("Performance test was completed for {} devices!", mqttClients.size());
    }

    public void warmUpDevices() throws InterruptedException {
        log.info("Warming up {} devices...", deviceCount);
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            mqttExecutor.submit(() -> {
                try {
                    String token = String.format("%20d", tokenNumber);
                    mqttClients.add(initClient(token));
                } catch (Exception e) {
                    log.error("Error while warm-up device", e);
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();

        CountDownLatch warmUpLatch = new CountDownLatch(mqttClients.size());
        for (MqttClient mqttClient : mqttClients) {
            mqttExecutor.submit(() -> {
                mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                    } else {
                                        log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                    }
                                    warmUpLatch.countDown();
                                }
                        );
            });
        }
        warmUpLatch.await();
        log.info("{} devices have been warmed up successfully!", mqttClients.size());
    }
}
