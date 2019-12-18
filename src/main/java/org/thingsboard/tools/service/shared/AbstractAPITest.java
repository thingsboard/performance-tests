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
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.msg.RandomAttributesGenerator;
import org.thingsboard.tools.service.msg.RandomTelemetryGenerator;

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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractAPITest {

    protected final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());

    private static ObjectMapper mapper = new ObjectMapper();

    private static final int CONNECT_TIMEOUT = 5;

    protected ScheduledFuture<?> reportScheduledFuture;

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
    protected int deviceStartIdxConfig;
    @Value("${device.endIdx}")
    protected int deviceEndIdxConfig;
    @Value("${device.count}")
    protected int deviceCount;
    @Value("${warmup.packSize:100}")
    protected int warmUpPackSize;
    @Value("${test.instanceIdx:0}")
    protected int instanceIdxConfig;
    @Value("${test.useInstanceIdx:false}")
    protected boolean useInstanceIdx;
    @Value("${test.useInstanceIdxRegex:false}")
    protected boolean useInstanceIdxRegex;
    @Value("${test.instanceIdxRegexSource:}")
    protected String instanceIdxRegexSource;
    @Value("${test.instanceIdxRegex:([0-9]+)$}")
    protected String instanceIdxRegex;

    @Value("${test.sequential:true}")
    protected boolean sequentialTest;
    @Value("${test.telemetry:true}")
    protected boolean telemetryTest;
    @Value("${test.mps:1000}")
    protected int testMessagesPerSecond;
    @Value("${test.duration:60}")
    protected int testDurationInSec;
    @Value("${test.alarms.start:0}")
    protected int alarmsStartTs;
    @Value("${test.alarms.end:0}")
    protected int alarmsEndTs;
    @Value("${test.alarms.aps:0}")
    protected int alarmsPerSecond;

    @Autowired
    protected RandomTelemetryGenerator tsMsgGenerator;
    @Autowired
    protected RandomAttributesGenerator attrMsgGenerator;
    @Autowired
    protected RestClientService restClientService;
    @Autowired
    protected CustomerManager customerManager;

    protected List<Device> devices = Collections.synchronizedList(new ArrayList<>(1024 * 1024));

    protected final Random random = new Random();
    private volatile CountDownLatch testDurationLatch;
    private EventLoopGroup EVENT_LOOP_GROUP;

    protected final List<DeviceClient> deviceClients =  Collections.synchronizedList(new ArrayList<>(1024 * 1024));

    protected int deviceStartIdx;
    protected int deviceEndIdx;
    protected int instanceIdx;

    @PostConstruct
    protected void init() {
        if (this.useInstanceIdx) {
            boolean parsed = false;
            if (this.useInstanceIdxRegex) {
                try {
                    Pattern r = Pattern.compile(this.instanceIdxRegex);
                    Matcher m = r.matcher(this.instanceIdxRegexSource);
                    if (m.find()) {
                        this.instanceIdx = Integer.parseInt(m.group(0));
                        parsed = true;
                    }
                } catch (Exception e) {
                    log.error("Failed to parse instanceIdx", e);
                }
            }
            if (!parsed) {
                this.instanceIdx = this.instanceIdxConfig;
            }
            log.info("Initialized with instanceIdx [{}]", this.instanceIdx);

            this.deviceStartIdx = this.deviceCount * this.instanceIdx;
            this.deviceEndIdx = this.deviceStartIdx + this.deviceCount;
        } else {
            this.deviceStartIdx = this.deviceStartIdxConfig;
            this.deviceEndIdx = this.deviceEndIdxConfig;
        }
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
        log.info("Initialized with deviceStartIdx [{}], deviceEndIdx [{}]", this.deviceStartIdx, this.deviceEndIdx);
    }

    @PreDestroy
    public void destroy() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }

        if (reportScheduledFuture != null) {
            reportScheduledFuture.cancel(true);
        }

        if (!EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    protected void createDevices(boolean setCredentials) throws Exception {
        List<Device> entities = createEntities(deviceStartIdx, deviceEndIdx, false, setCredentials);
        devices = Collections.synchronizedList(entities);
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
                deviceClient.getMqttClient().publish(getWarmUpTopic(), Unpooled.wrappedBuffer(getData(deviceClient.getDeviceName())), MqttQoS.AT_LEAST_ONCE)
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

    protected void runApiTests(int deviceCount) throws InterruptedException {
        log.info("Starting performance test for {} devices...", deviceCount);
        AtomicInteger totalSuccessCount = new AtomicInteger();
        AtomicInteger totalFailedCount = new AtomicInteger();
        testDurationLatch = new CountDownLatch(testDurationInSec);
        for (int i = 0; i < testDurationInSec; i++) {
            int iterationNumber = i;
            restClientService.getScheduler().schedule(() -> runApiTestIteration(iterationNumber, totalSuccessCount, totalFailedCount, testDurationLatch), i, TimeUnit.SECONDS);
        }
        testDurationLatch.await((long) (testDurationInSec * 1.2), TimeUnit.SECONDS);
        log.info("Completed performance iteration. Success: {}, Failed: {}", totalSuccessCount.get(), totalFailedCount.get());
    }

    protected abstract void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch);

    protected void removeEntities(List<DeviceId> entityIds, boolean isGateway) throws InterruptedException {
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

    protected List<Device> createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException {
        List<Device> result;
        if (isGateway) {
            result = Collections.synchronizedList(new ArrayList<>(1024));
        } else {
            result = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
        }
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
                    if (setCredentials) {
                        entity = restClientService.getRestClient().createDevice(entity, token);
                    } else {
                        entity = restClientService.getRestClient().createDevice(entity);
                    }

                    result.add(entity);

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
                log.info("{} {} have been created so far...", count.get(), isGateway ? "gateways" : "devices");
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);

        log.info("{} {} have been created successfully!", result.size(), isGateway ? "gateways" : "devices");

        return result;
    }

    protected String getToken(boolean isGateway, int token) {
        return (isGateway ? "GW" : "DW") + String.format("%8d", token).replace(" ", "0");
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
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                DeviceClient client = getDeviceClient(iterationDevices, iteration, i);
                Msg message = (telemetryTest ? tsMsgGenerator : attrMsgGenerator).getNextMessage(client.getDeviceName(), alarmRequired);
                if (message.isTriggersAlarm()) {
                    alarmCount++;
                }
                restClientService.getWorkers().submit(() -> {
                    client.getMqttClient().publish(getTestTopic(), Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_LEAST_ONCE)
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
