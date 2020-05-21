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
package org.thingsboard.tools.service.shared;

import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.Msg;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class BaseMqttAPITest extends AbstractAPITest {

    private static final int CONNECT_TIMEOUT = 5;
    private EventLoopGroup EVENT_LOOP_GROUP;

    @Value("${mqtt.host}")
    private String mqttHost;
    @Value("${mqtt.port}")
    private int mqttPort;
    @Value("${mqtt.ssl.enabled}")
    boolean mqttSslEnabled;
    @Value("${mqtt.ssl.key_store}")
    String mqttSslKeyStore;
    @Value("${mqtt.ssl.key_store_password}")
    String mqttSslKeyStorePassword;

    protected final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());

    protected final List<DeviceClient> deviceClients =  Collections.synchronizedList(new ArrayList<>(1024 * 1024));

    @PostConstruct
    protected void init() {
        super.init();
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
    }

    @PreDestroy
    public void destroy() {
        super.destroy();
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }

        if (!EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    public void warmUpDevices() throws InterruptedException {
        log.info("Warming up {} devices...", deviceClients.size());
        AtomicInteger totalWarmedUpCount = new AtomicInteger();
        List<DeviceClient> pack = null;
        for (DeviceClient device : deviceClients) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(device);
            if (pack.size() == warmUpPackSize) {
                sendAndWaitPack(pack, totalWarmedUpCount);
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            sendAndWaitPack(pack, totalWarmedUpCount);
        }
        log.info("{} devices have been warmed up successfully!", deviceClients.size());
    }

    private void sendAndWaitPack(List<DeviceClient> pack, AtomicInteger totalWarmedUpCount) throws InterruptedException {
        CountDownLatch packLatch = new CountDownLatch(pack.size());
        for (DeviceClient deviceClient : pack) {
            restClientService.getScheduler().submit(() -> {
                deviceClient.getMqttClient().publish(getWarmUpTopic(), Unpooled.wrappedBuffer(getData(deviceClient.getDeviceName())), MqttQoS.AT_MOST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Warm up Message was successfully published to device: {}", deviceClient.getDeviceName());
                                    } else {
                                        log.error("Error while publishing warm up message to device: {}", deviceClient.getDeviceName());
                                    }
                                    packLatch.countDown();
                                    totalWarmedUpCount.getAndIncrement();
                                }
                        );
            });
        }
        boolean succeeded = packLatch.await(10, TimeUnit.SECONDS);
        if (succeeded) {
            log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
        } else {
            log.error("[{}] devices warmed up failed: {}!", totalWarmedUpCount.get(), packLatch.getCount());
        }
    }

    protected abstract String getWarmUpTopic();

    protected abstract byte[] getData(String deviceName);

    protected DeviceClient getDeviceClient(Set<DeviceClient> iterationDevices, int iteration, int msgOffsetIdx) {
        DeviceClient client;
        if (sequentialTest) {
            int iterationOffset = (iteration * testMessagesPerSecond) % deviceClients.size();
            int idx = (iterationOffset + msgOffsetIdx) % deviceClients.size();
            client = deviceClients.get(idx);
        } else {
            while (true) {
                client = deviceClients.get(random.nextInt(deviceClients.size()));
                if (iterationDevices.add(client)) {
                    break;
                }
            }
        }
        return client;
    }

    protected void connectDevices(List<String> pack, AtomicInteger totalConnectedCount, boolean isGateway) throws InterruptedException {
        final String devicesType = isGateway ? "gateways" : "devices";
        final String deviceType = isGateway ? "gateway" : "device";
        log.info("Connecting {} {}...", pack.size(), devicesType);
        CountDownLatch connectLatch = new CountDownLatch(pack.size());
        for (String deviceName : pack) {
            restClientService.getWorkers().submit(() -> {
                try {
                    mqttClients.add(initClient(deviceName));
                    totalConnectedCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error while connect {}", deviceType, e);
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();
        log.info("{} {} have been connected successfully!", totalConnectedCount.get(), devicesType);
    }

    private MqttClient initClient(String token) throws Exception {
        MqttClientConfig config = new MqttClientConfig(getSslContext());
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

    private SslContext getSslContext() {
        if (mqttSslEnabled) {
            try {
                TrustManagerFactory trustFact = TrustManagerFactory.getInstance("SunX509");
                KeyStore trustStore = KeyStore.getInstance("JKS");
                FileInputStream stream = new FileInputStream(mqttSslKeyStore);
                trustStore.load(stream, mqttSslKeyStorePassword.toCharArray());
                trustFact.init(trustStore);
                return SslContextBuilder.forClient().trustManager(trustFact).build();
            } catch (Exception e) {
                throw new RuntimeException("Exception while creating SslContext", e);
            }
        } else {
            return null;
        }
    }

    protected void reportMqttClientsStats() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer("{\"msgCount\":0}".getBytes(StandardCharsets.UTF_8)), MqttQoS.AT_MOST_ONCE).addListener(future -> {
                        if (future.isSuccess()) {
                            log.debug("[{}] Gateway statistics message was successfully published.", mqttClient.getClientConfig().getUsername());
                        } else {
                            log.error("[{}] Error while publishing gateway statistics message ", mqttClient.getClientConfig().getUsername(), future.cause());
                        }
                    }
            );
        }
    }

    protected void runApiTestIteration(int iteration,
                                       AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount,
                                       CountDownLatch testDurationLatch,
                                       boolean isGateway) {
        try {
            Set<DeviceClient> iterationDevices = new HashSet<>();
            log.info("[{}] Starting performance iteration for {} {}...", iteration, mqttClients.size(), isGateway ? "gateways" : "devices");
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            boolean alarmIteration = iteration >= alarmsStartTs && iteration < alarmsEndTs;
            int alarmCount = 0;
            for (int i = 0; i < testMessagesPerSecond; i++) {
                if (iterationDevices.size() >= deviceClients.size()) {
                    iterationDevices = new HashSet<>();
                }
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                DeviceClient client = getDeviceClient(iterationDevices, iteration, i);
                Msg message = getNextMessage(client.getDeviceName(), alarmRequired);
                if (message.isTriggersAlarm()) {
                    alarmCount++;
                }
                restClientService.getWorkers().submit(() -> {
                    client.getMqttClient().publish(getTestTopic(), Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_MOST_ONCE)
                            .addListener(future -> {
                                        if (future.isSuccess()) {
                                            totalSuccessPublishedCount.incrementAndGet();
                                            successPublishedCount.incrementAndGet();
                                            logSuccessTestMessage(iteration, client);
                                        } else {
                                            totalFailedPublishedCount.incrementAndGet();
                                            failedPublishedCount.incrementAndGet();
                                            logFailureTestMessage(iteration, client, future);
                                        }
                                        iterationLatch.countDown();
                                    }
                            );
                });
            }
            iterationLatch.await();
            log.info("[{}] Completed performance iteration. Success: {}, Failed: {}, Alarms: {}", iteration, successPublishedCount.get(), failedPublishedCount.get(), alarmCount);
            testDurationLatch.countDown();
        } catch (Throwable t) {
            log.warn("[{}] Failed to process iteration", iteration, t);
        }
    }

    protected abstract String getTestTopic();

    protected abstract void logSuccessTestMessage(int iteration, DeviceClient client);

    protected abstract void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future);
}


