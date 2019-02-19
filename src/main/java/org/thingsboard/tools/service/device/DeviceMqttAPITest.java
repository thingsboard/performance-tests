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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "MQTT")
public class DeviceMqttAPITest extends BaseDeviceAPITest {

    private static final int CONNECT_TIMEOUT = 5;

    @Value("${mqtt.host}")
    private String mqttHost;

    @Value("${mqtt.port}")
    private int mqttPort;

    private final ScheduledExecutorService warmUpExecutor = Executors.newScheduledThreadPool(10);

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
        if (!this.warmUpExecutor.isShutdown()) {
            this.warmUpExecutor.shutdown();
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
            result = connectFuture.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
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
        int i = 0;
        for (MqttClient mqttClient : mqttClients) {
            final int delayPause = publishTelemetryPause / mqttClients.size() * i;
            i++;
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
            }, delayPause, publishTelemetryPause, TimeUnit.MILLISECONDS);
        }

        ScheduledFuture<?> scheduledLogFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] messages have been published successfully. [{}] failed. [{}] total.",
                        successPublishedCount.get(), failedPublishedCount.get(), totalMessagesToPublish);
            } catch (Exception ignored) {
            }
        }, 0, PUBLISHED_MESSAGES_LOG_PAUSE, TimeUnit.SECONDS);

        Thread.sleep(maxDelay);
        scheduledLogFuture.cancel(false);
        schedulerExecutor.shutdownNow();

        log.info("Performance test was completed for {} devices!", mqttClients.size());
        log.info("{} messages were published successfully, {} failed!", successPublishedCount.get(), failedPublishedCount.get());
    }

    @Override
    public void warmUpDevices(final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        log.info("Connecting {} devices...", deviceCount);
        AtomicInteger totalConnectedCount = new AtomicInteger();
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        int idx = 0;
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            final int delayPause = publishTelemetryPause / deviceCount * idx;
            idx++;
            warmUpExecutor.schedule(() -> {
                try {
                    String token = getToken(tokenNumber);
                    mqttClients.add(initClient(token));
                } catch (Exception e) {
                    log.error("Error while connect device", e);
                } finally {
                    connectLatch.countDown();
                    totalConnectedCount.getAndIncrement();
                }
            }, delayPause, TimeUnit.MILLISECONDS);
        }
        ScheduledFuture<?> scheduledLogFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] devices have been connected!", totalConnectedCount.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        connectLatch.await();
        scheduledLogFuture.cancel(false);

        log.info("{} devices have been connected successfully!", mqttClients.size());
        log.info("Warming up {} devices...", mqttClients.size());

        AtomicInteger totalWarmedUpCount = new AtomicInteger();
        CountDownLatch warmUpLatch = new CountDownLatch(mqttClients.size());

        idx = 0;
        for (MqttClient mqttClient : mqttClients) {
            final int delayPause = publishTelemetryPause / mqttClients.size() * idx;
            idx++;
            warmUpExecutor.schedule(() -> {
                mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                    } else {
                                        log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                    }
                                    warmUpLatch.countDown();
                                    totalWarmedUpCount.getAndIncrement();
                                }
                        );
            }, delayPause, TimeUnit.MILLISECONDS);
        }

        scheduledLogFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        warmUpLatch.await();
        scheduledLogFuture.cancel(false);

        log.info("{} devices have been warmed up successfully!", mqttClients.size());
    }
}
