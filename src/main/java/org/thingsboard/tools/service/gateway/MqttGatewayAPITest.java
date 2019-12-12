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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.page.TextPageLink;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.common.data.role.Role;
import org.thingsboard.server.common.data.role.RoleType;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.customer.DefaultCustomerManager;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.msg.RandomGatewayAttributesGenerator;
import org.thingsboard.tools.service.msg.RandomGatewayTelemetryGenerator;
import org.thingsboard.tools.service.shared.AbstractAPITest;
import org.thingsboard.tools.service.shared.DefaultRestClientService;
import org.thingsboard.tools.service.shared.RestClientService;

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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MqttGatewayAPITest extends AbstractAPITest implements GatewayAPITest {

    private static ObjectMapper mapper = new ObjectMapper();

    private static final int CONNECT_TIMEOUT = 5;

    @Autowired
    private RandomGatewayTelemetryGenerator tsMsgGenerator;
    @Autowired
    private RandomGatewayAttributesGenerator attrMsgGenerator;

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
    @Value("${device.startIdx}")
    int deviceStartIdx;
    @Value("${device.endIdx}")
    int deviceEndIdx;
    @Value("${gateway.startIdx}")
    int gatewayStartIdx;
    @Value("${gateway.endIdx}")
    int gatewayEndIdx;
    @Value("${warmup.packSize:100}")
    int warmUpPackSize;
    @Value("${test.sequential:true}")
    boolean sequentialTest;
    @Value("${test.telemetry:true}")
    boolean telemetryTest;
    @Value("${test.mps:1000}")
    int testMessagesPerSecond;
    @Value("${test.duration:60}")
    int testDurationInSec;
    @Value("${test.alarms.start:0}")
    int alarmsStartTs;
    @Value("${test.alarms.end:0}")
    int alarmsEndTs;
    @Value("${test.alarms.aps:0}")
    int alarmsPerSecond;
    @Value("${test.dashboardNames:}")
    String dashboardNames;

    private final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());

    private final List<Device> gateways = Collections.synchronizedList(new ArrayList<>(1024));
    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
    private final List<DeviceGatewayClient> devices = new ArrayList<>(1024 * 1024);

    private EventLoopGroup EVENT_LOOP_GROUP;

    private volatile CountDownLatch testDurationLatch;
    private final Random random = new Random();

    @PreDestroy
    public void destroy() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }
    }

    @Override
    public List<DeviceGatewayClient> getDevices() {
        return devices;
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
                connectGateways(pack, totalConnectedCount);
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectGateways(pack, totalConnectedCount);
        }
        restClientService.getScheduler().scheduleAtFixedRate(this::reportGatewayStats, 10, 10, TimeUnit.SECONDS);
        mapDevicesToGatewayConnections();
    }

    private void connectGateways(List<String> pack, AtomicInteger totalConnectedCount) throws InterruptedException {
        log.info("Connecting {} gateways...", pack.size());
        CountDownLatch connectLatch = new CountDownLatch(pack.size());
        for (String gwName : pack) {
            restClientService.getWorkers().submit(() -> {
                try {
                    mqttClients.add(initClient(gwName));
                    totalConnectedCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error while connect device", e);
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();
        log.info("{} gateways have been connected successfully!", pack.size());
    }


    private void reportGatewayStats() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer("{\"msgCount\":0}".getBytes(StandardCharsets.UTF_8)), MqttQoS.AT_LEAST_ONCE).addListener(future -> {
                        if (future.isSuccess()) {
                            log.debug("[{}] Gateway statistics message was successfully published.", mqttClient.getClientConfig().getUsername());
                        } else {
                            log.error("[{}] Error while publishing gateway statistics message ", mqttClient.getClientConfig().getUsername(), future.cause());
                        }
                    }
            );
        }
    }

    @Override
    public void runApiTests() throws InterruptedException {
        log.info("Starting performance test for {} devices...", devices.size());
        AtomicInteger totalSuccessCount = new AtomicInteger();
        AtomicInteger totalFailedCount = new AtomicInteger();
        testDurationLatch = new CountDownLatch(testDurationInSec);
        for (int i = 0; i < testDurationInSec; i++) {
            int iterationNumber = i;
            restClientService.getScheduler().schedule(() -> runApiTestIteration(iterationNumber, totalSuccessCount, totalFailedCount), i, TimeUnit.SECONDS);
        }
        testDurationLatch.await((long) (testDurationInSec * 1.2), TimeUnit.SECONDS);
        log.info("Completed performance iteration. Success: {}, Failed: {}", totalSuccessCount.get(), totalFailedCount.get());
    }

    private void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount) {
        try {
            Set<DeviceGatewayClient> iterationDevices = new HashSet<>();
            log.info("[{}] Starting performance iteration for {} devices...", iteration, mqttClients.size());
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            boolean alarmIteration = iteration >= alarmsStartTs && iteration < alarmsEndTs;
            int alarmCount = 0;
            for (int i = 0; i < testMessagesPerSecond; i++) {
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                DeviceGatewayClient client = getDeviceGatewayClient(iterationDevices, iteration, i);
                Msg message = (telemetryTest ? tsMsgGenerator : attrMsgGenerator).getNextMessage(client.getDeviceName(), alarmRequired);
                if (message.isTriggersAlarm()) {
                    alarmCount++;
                }
                restClientService.getWorkers().submit(() -> {
                    String topic = telemetryTest ? "v1/gateway/telemetry" : "v1/gateway/attributes";
                    client.getMqttClient().publish(topic, Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_LEAST_ONCE)
                            .addListener(future -> {
                                        if (future.isSuccess()) {
                                            totalSuccessPublishedCount.incrementAndGet();
                                            successPublishedCount.incrementAndGet();
                                            log.debug("[{}] Message was successfully published to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName());
                                        } else {
                                            totalFailedPublishedCount.incrementAndGet();
                                            failedPublishedCount.incrementAndGet();
                                            log.error("[{}] Error while publishing message to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName(),
                                                    future.cause());
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

    private DeviceGatewayClient getDeviceGatewayClient(Set<DeviceGatewayClient> iterationDevices, int iteration, int msgOffsetIdx) {
        DeviceGatewayClient client;
        if (sequentialTest) {
            int iterationOffset = (iteration * testMessagesPerSecond) % devices.size();
            int idx = (iterationOffset + msgOffsetIdx) % devices.size();
            client = devices.get(idx);
        } else {
            while (true) {
                client = devices.get(random.nextInt(devices.size()));
                if (iterationDevices.add(client)) {
                    break;
                }
            }
        }
        return client;
    }

    private void sendAndWaitPack(List<DeviceGatewayClient> pack, AtomicInteger totalWarmedUpCount) throws InterruptedException {
        CountDownLatch packLatch = new CountDownLatch(pack.size());
        for (DeviceGatewayClient device : pack) {
            restClientService.getScheduler().submit(() -> {
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
        boolean succeeded = packLatch.await(10, TimeUnit.SECONDS);
        if (succeeded) {
            log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
        } else {
            log.error("[{}] devices warmed up failed: {}!", totalWarmedUpCount.get(), packLatch.getCount());
        }
    }

    private void mapDevicesToGatewayConnections() {
        int gatewayCount = mqttClients.size();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int deviceIdx = i - deviceStartIdx;
            int gatewayIdx = deviceIdx % gatewayCount;
            DeviceGatewayClient client = new DeviceGatewayClient();
            client.setMqttClient(mqttClients.get(gatewayIdx));
            client.setDeviceName(getToken(false, i));
            client.setGatewayName(getToken(true, gatewayIdx));
            devices.add(client);
        }
    }

    private void createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException {
        int entityCount = endIdx - startIdx;
        log.info("Creating {} {}...", entityCount, isGateway ? "gateways" : "devices");
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        List<CustomerId> customerIds = customerManager.getCustomerIds();
        for (int i = startIdx; i < endIdx; i++) {
            final int tokenNumber = i;
            restClientService.getHttpExecutor().submit(() -> {
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

                    if (!customerIds.isEmpty()) {
                        int entityIdx = tokenNumber - startIdx;
                        int customerIdx = entityIdx % customerIds.size();
                        CustomerId customerId = customerIds.get(customerIdx);
                        entity.setOwnerId(customerId);
                    }
                    entity = restClientService.getRestClient().createDevice(entity);
                    if (setCredentials) {
                        restClientService.getRestClient().updateDeviceCredentials(entity.getId(), token);
                    }
                    if (isGateway) {
                        gateways.add(entity);
                    } else {
                        deviceIds.add(entity.getId());
                    }
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity", e);
                    if (entity != null && entity.getId() != null) {
                        restClientService.getRestClient().deleteDevice(entity.getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} devices have been created so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);


        latch.await();
        logScheduleFuture.cancel(true);
        log.info("{} entities have been created successfully!", isGateway ? gateways.size() : deviceIds.size());
    }

    @Override
    public void removeGateways() throws Exception {
        removeEntities(gateways.stream().map(Device::getId).collect(Collectors.toList()), true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(deviceIds, false);
    }

    private void removeEntities(List<DeviceId> entityIds, boolean isGateway) throws InterruptedException {
        log.info("Removing {} {}...", isGateway ? "gateways" : "devices", entityIds.size());
        CountDownLatch latch = new CountDownLatch(entityIds.size());
        AtomicInteger count = new AtomicInteger();
        for (DeviceId entityId : entityIds) {
            restClientService.getHttpExecutor().submit(() -> {
                try {
                    restClientService.getRestClient().deleteDevice(entityId);
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting {}", isGateway ? "gateway" : "device", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} {} have been removed so far...", isGateway ? "gateways" : "devices", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("{} {} have been removed successfully! {} were failed for removal!", count.get(), isGateway ? "gateways" : "devices", entityIds.size() - count.get());
    }

    private String getToken(boolean isGateway, int token) {
        return (isGateway ? "GW" : "DW") + String.format("%8d", token).replace(" ", "0");
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

}
