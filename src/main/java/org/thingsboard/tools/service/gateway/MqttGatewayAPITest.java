/**
 * Copyright © 2016-2018 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.tools.service.msg.MessageGenerator;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MqttGatewayAPITest implements GatewayAPITest {

    private static ObjectMapper mapper = new ObjectMapper();

    private static final int LOG_PAUSE = 1;
    private static final int CONNECT_TIMEOUT = 5;

    @Autowired
    private MessageGenerator msgGenerator;

    @Value("${mqtt.host}")
    private String mqttHost;

    @Value("${mqtt.port}")
    private int mqttPort;

    @Value("${mqtt.ssl.enabled}")
    boolean mqttSslEnabled;

    @Value("${mqtt.ssl.key_store}")
    String mqttSslKeyStore;

    @Value("${mqtt.ssl.key_store_password}")
    String mqttSllKeyStorePassword;

    @Value("${device.startIdx}")
    int deviceStartIdx;

    @Value("${device.endIdx}")
    int deviceEndIdx;

    @Value("${gateway.startIdx}")
    int gatewayStartIdx;

    @Value("${gateway.endIdx}")
    int gatewayEndIdx;

    @Value("${rest.url}")
    String restUrl;

    @Value("${rest.username}")
    String username;

    @Value("${rest.password}")
    String password;

    @Value("${warmup.packSize:100}")
    int warmUpPackSize;
    @Value("${warmup.gateway.connect:10000}")
    int gatewayConnectTime;
    @Value("${test.mps:1000}")
    int testMessagesPerSecond;
    @Value("${test.duration:60}")
    int testDurationInSec;

    private RestClient restClient;

    private int gatewayCount;
    private int deviceCount;

    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    private final ScheduledExecutorService schedulerLogExecutor = Executors.newScheduledThreadPool(10);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final ExecutorService workers = Executors.newFixedThreadPool(10);
    private final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());

    private final List<DeviceId> gatewayIds = Collections.synchronizedList(new ArrayList<>(1024));
    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
    private final List<DeviceGatewayClient> devices = new ArrayList<>(1024 * 1024);

    private EventLoopGroup EVENT_LOOP_GROUP;

    private volatile CountDownLatch testDurationLatch;
    private final Random random = new Random();

    @PostConstruct
    public void init() {
        gatewayCount = gatewayEndIdx - gatewayStartIdx;
        deviceCount = deviceEndIdx - deviceStartIdx;
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
    }

    @PreDestroy
    public void destroy() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdownNow();
        }
        if (!this.scheduler.isShutdown()) {
            this.scheduler.shutdownNow();
        }
        if (!this.workers.isShutdown()) {
            this.workers.shutdownNow();
        }
        if (!EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void createGateways() throws Exception {
        createEntities(gatewayStartIdx, gatewayEndIdx, true, true);
    }

    @Override
    public void createDevices() throws Exception {
        createEntities(deviceStartIdx, deviceEndIdx, false, false);
    }

    @Override
    public void warmUpDevices() throws InterruptedException {
        connectGateways(gatewayConnectTime);
        mapDevicesToGatewayConnections();
        log.info("Warming up {} devices...", devices.size());
        AtomicInteger totalWarmedUpCount = new AtomicInteger();
        List<DeviceGatewayClient> pack = null;
        for (DeviceGatewayClient device : devices) {
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
        log.info("{} devices have been warmed up successfully!", devices.size());
    }

    @Override
    public void runApiTests() throws InterruptedException {
        log.info("Starting performance test for {} devices...", devices.size());
        AtomicInteger totalSuccessCount = new AtomicInteger();
        AtomicInteger totalFailedCount = new AtomicInteger();
        testDurationLatch = new CountDownLatch(testDurationInSec);
        for (int i = 0; i < testDurationInSec; i++) {
            int iterationNumber = i;
            scheduler.schedule(() -> runApiTestIteration(iterationNumber, totalSuccessCount, totalFailedCount), i, TimeUnit.SECONDS);
        }
        testDurationLatch.await((long) (testDurationInSec * 1.2), TimeUnit.SECONDS);
        log.info("Completed performance iteration. Success: {}, Failed: {}", totalSuccessCount.get(), totalFailedCount.get());
    }

    private void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount) {
        try {
            log.info("[{}] Starting performance iteration for {} devices...", iteration, mqttClients.size());
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            int deviceCount = devices.size();
            for (int i = 0; i < testMessagesPerSecond; i++) {
                DeviceGatewayClient client = devices.get(random.nextInt(deviceCount));
                byte[] message = msgGenerator.getNextMessage(client.getDeviceName());
                workers.submit(() -> {
                    client.getMqttClient().publish("v1/gateway/telemetry", Unpooled.wrappedBuffer(message), MqttQoS.AT_LEAST_ONCE)
                            .addListener(future -> {
                                        iterationLatch.countDown();
                                        if (future.isSuccess()) {
                                            totalSuccessPublishedCount.incrementAndGet();
                                            successPublishedCount.incrementAndGet();
                                            log.debug("[{}] Message was successfully published to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName());
                                        } else {
                                            totalFailedPublishedCount.incrementAndGet();
                                            failedPublishedCount.incrementAndGet();
                                            log.error("[{}] Error while publishing message to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName());
                                        }
                                    }
                            );
                });
            }
            iterationLatch.await();
            log.info("[{}] Completed performance iteration. Success: {}, Failed: {}", iteration, successPublishedCount.get(), failedPublishedCount.get());
            testDurationLatch.countDown();
        } catch (Throwable t) {
            log.warn("[{}] Failed to process iteration", iteration, t);
        }
    }

    private void sendAndWaitPack(List<DeviceGatewayClient> pack, AtomicInteger totalWarmedUpCount) throws InterruptedException {
        CountDownLatch packLatch = new CountDownLatch(pack.size());
        for (DeviceGatewayClient device : pack) {
            scheduler.submit(() -> {
                device.getMqttClient().publish("v1/gateway/connect", Unpooled.wrappedBuffer(("{\"device\":\"" + device.getDeviceName() + "\"}").getBytes(StandardCharsets.UTF_8))
                        , MqttQoS.AT_LEAST_ONCE)
                        .addListener(future -> {
                                    if (future.isSuccess()) {
                                        log.debug("Warm up Message was successfully published to device: {}", device.getDeviceName());
                                    } else {
                                        log.error("Error while publishing warm up message to device: {}", device.getDeviceName());
                                    }
                                    packLatch.countDown();
                                    totalWarmedUpCount.getAndIncrement();
                                }
                        );
            });
        }
        packLatch.await();
        log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
    }

    private void connectGateways(double publishTelemetryPause) throws InterruptedException {
        log.info("Connecting {} gateways...", gatewayCount);
        AtomicInteger totalConnectedCount = new AtomicInteger();
        CountDownLatch connectLatch = new CountDownLatch(gatewayCount);
        int idx = 0;
        for (int i = gatewayStartIdx; i < gatewayEndIdx; i++) {
            final int tokenNumber = i;
            final int delayPause = (int) (publishTelemetryPause / gatewayCount * idx);
            idx++;
            scheduler.schedule(() -> {
                try {
                    String token = getToken(true, tokenNumber);
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
                log.info("[{}] gateways have been connected!", totalConnectedCount.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        connectLatch.await();
        scheduledLogFuture.cancel(true);

        log.info("{} gateways have been connected successfully!", mqttClients.size());
    }

    private void mapDevicesToGatewayConnections() {
        int gatewayCount = mqttClients.size();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int deviceIdx = i - deviceStartIdx;
            int gatewayIdx = deviceIdx % gatewayCount;
            DeviceGatewayClient client = new DeviceGatewayClient();
            client.setMqttClient(mqttClients.get(gatewayIdx));
            client.setDeviceName(getToken(false, i));
            client.setGatewayName(getToken(true, i));
            devices.add(client);
        }
    }

    private void createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException {
        restClient.login(username, password);
        int entityCount = endIdx - startIdx;
        log.info("Creating {} {}...", entityCount, isGateway ? "gateways" : "devices");
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = startIdx; i < endIdx; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                Device entity = new Device();
                try {
                    String token = getToken(isGateway, tokenNumber);
                    if (isGateway) {
                        entity.setName(token);
                        entity.setType("gateway");
                        entity.setAdditionalInfo(mapper.createObjectNode().putObject("additionalInfo").put("gateway", true));
                    } else {
                        entity.setName(token);
                        entity.setType("device");
                    }
                    entity = restClient.createDevice(entity);
                    if (setCredentials) {
                        restClient.updateDeviceCredentials(entity.getId(), token);
                    }
                    if (isGateway) {
                        gatewayIds.add(entity.getId());
                    } else {
                        deviceIds.add(entity.getId());
                    }
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity", e);
                    if (entity != null && entity.getId() != null) {
                        restClient.getRestTemplate().delete(restUrl + "/api/entity/" + entity.getId().getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("{} devices have been created so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {
            }
        }, 10, 10, TimeUnit.MINUTES);

        latch.await();
        tokenRefreshScheduleFuture.cancel(true);
        logScheduleFuture.cancel(true);
        log.info("{} entities have been created successfully!", isGateway ? gatewayIds.size() : deviceIds.size());
    }

    @Override
    public void removeGateways() throws Exception {
        removeEntities(gatewayIds, true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(deviceIds, false);
    }

    private void removeEntities(List<DeviceId> entityIds, boolean isGateway) throws InterruptedException {
        restClient.login(username, password);
        log.info("Removing {} {}...", isGateway ? "gateways" : "devices", entityIds.size());
        CountDownLatch latch = new CountDownLatch(entityIds.size());
        AtomicInteger count = new AtomicInteger();
        for (DeviceId entityId : entityIds) {
            httpExecutor.submit(() -> {
                try {
                    restClient.getRestTemplate().delete(restUrl + "/api/device/" + entityId.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting {}", isGateway ? "gateway" : "device", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("{} {} have been removed so far...", isGateway ? "gateways" : "devices", count.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {
            }
        }, 10, 10, TimeUnit.MINUTES);

        latch.await();
        logScheduleFuture.cancel(true);
        tokenRefreshScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("{} {} have been removed successfully! {} were failed for removal!", count.get(), isGateway ? "gateways" : "devices", entityIds.size() - count.get());
    }

    private String getToken(boolean isGateway, int token) {
        return (isGateway ? "GW" : "DW") + String.format("%18d", token).replace(" ", "0");
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
                trustStore.load(stream, mqttSllKeyStorePassword.toCharArray());
                trustFact.init(trustStore);
                return SslContextBuilder.forClient().trustManager(trustFact).build();
            } catch (Exception e) {
                throw new RuntimeException("Exception while creating SslContext", e);
            }
        } else {
            return null;
        }
    }

}
