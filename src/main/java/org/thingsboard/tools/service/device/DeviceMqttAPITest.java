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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "MQTT")
public class DeviceMqttAPITest extends BaseDeviceAPITest {

    private static final int CONNECT_TIMEOUT_IN_SECONDS = 5;

    @Value("${mqtt.host}")
    private String mqttHost;

    @Value("${mqtt.port}")
    private int mqttPort;

    private final ExecutorService mqttExecutor = Executors.newFixedThreadPool(100);

    private final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());

    private EventLoopGroup EVENT_LOOP_GROUP;

    @PostConstruct
    void init() {
        super.init();
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
    }

    @PreDestroy
    void destroy() {
        super.destroy();
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }
        if (!this.mqttExecutor.isShutdown()) {
            this.mqttExecutor.shutdown();
        }
        if (!EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    private MqttClient initClient(String token) throws Exception {
        MqttClientConfig config = new MqttClientConfig();
        config.setUsername(token);
        MqttClient client = MqttClient.create(config, null);
        client.setEventLoop(EVENT_LOOP_GROUP);
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

    @Override
    public void runApiTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        if (mqttClients.size() == 0) {
            log.info("Test stopped. No devices available!");
            return;
        }
        log.info("Starting performance test for {} devices...", mqttClients.size());
        long maxDelay = (publishTelemetryPause + 1) * publishTelemetryCount;
        final int totalMessagesToPublish = mqttClients.size() * publishTelemetryCount;
        AtomicInteger totalPublishedCount = new AtomicInteger();
        AtomicInteger successPublishedCount = new AtomicInteger();
        AtomicInteger failedPublishedCount = new AtomicInteger();
        for (MqttClient mqttClient : mqttClients) {
            testExecutor.submit(() -> {
                schedulerExecutor.scheduleAtFixedRate(() -> {
                    try {
                        mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                                .addListener(future -> {
                                            if (future.isSuccess()) {
                                                successPublishedCount.getAndIncrement();
                                                log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                            } else {
                                                failedPublishedCount.getAndIncrement();
                                                log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                            }
                                        }
                                );
                    } catch (Exception e) {
                        failedPublishedCount.getAndIncrement();
                    } finally {
                        totalPublishedCount.getAndIncrement();
                    }
                }, randomInt.nextInt(publishTelemetryPause), publishTelemetryPause, TimeUnit.MILLISECONDS);
            });
        }
        testExecutor.submit(() -> {
            schedulerExecutor.scheduleAtFixedRate(() -> {
                try {
                    log.info("[{}] messages have been published. [{}] messages to publish. Total [{}].",
                            totalPublishedCount.get(), totalMessagesToPublish - totalPublishedCount.get(), totalMessagesToPublish);
                } catch (Exception ignored) {}
            }, 0, 5, TimeUnit.SECONDS);
        });
        Thread.sleep(maxDelay);
        schedulerExecutor.shutdownNow();
        log.info("Performance test was completed for {} devices!", mqttClients.size());
        log.info("{} messages were published successfully, {} failed!", successPublishedCount.get(), failedPublishedCount.get());
    }

    @Override
    public void warmUpDevices(final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        log.info("Warming up {} devices...", deviceCount);
        AtomicInteger totalConnectedCount = new AtomicInteger();
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            mqttExecutor.submit(() -> {
                try {
                    Thread.sleep(randomInt.nextInt(publishTelemetryPause / 100));
                } catch (InterruptedException e) {
                    log.error("Error during thread sleep", e);
                }
                try {
                    String token = getToken(tokenNumber);
                    mqttClients.add(initClient(token));
                } catch (Exception e) {
                    log.error("Error while warm-up device", e);
                } finally {
                    connectLatch.countDown();
                    totalConnectedCount.getAndIncrement();
                    log.info("[{}] devices have been connected!", totalConnectedCount.get());
                }
            });
        }
        connectLatch.await();

        AtomicInteger totalWarmedUpCount = new AtomicInteger();
        CountDownLatch warmUpLatch = new CountDownLatch(mqttClients.size());
        for (MqttClient mqttClient : mqttClients) {
            mqttExecutor.submit(() -> {
                try {
                    Thread.sleep(randomInt.nextInt(publishTelemetryPause / 100));
                } catch (InterruptedException e) {
                    log.error("Error during thread sleep", e);
                }
                mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                    } else {
                                        log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                    }
                                    warmUpLatch.countDown();
                                    totalWarmedUpCount.getAndIncrement();
                                    log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
                                }
                        );
            });
        }
        warmUpLatch.await();
        log.info("{} devices have been warmed up successfully!", mqttClients.size());
    }
}
