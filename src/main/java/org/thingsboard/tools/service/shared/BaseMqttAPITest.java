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
package org.thingsboard.tools.service.shared;

import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.common.util.AbstractListeningExecutor;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.Msg;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class BaseMqttAPITest extends AbstractAPITest {

    protected static final int CONNECT_TIMEOUT = 5;
    private EventLoopGroup EVENT_LOOP_GROUP;
    // Off-event-loop executor for inbound MQTT message handlers (e.g. RPC receive). netty-mqtt feeds
    // each handler's result through Futures.transform(..., this executor), so it must be non-null once
    // we subscribe (a null executor NPEs on the first received PUBLISH). Created only when inbound
    // handling is enabled; stays null for publish-only runs, which never receive a PUBLISH.
    private AbstractListeningExecutor mqttHandlerExecutor;

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

    @Value("${gateway.statsReport:TB}")
    protected String statsReportMode;
    @Value("${gateway.statsReportIntervalSec:300}")
    protected int statsReportIntervalSec;

    private final AtomicInteger lastReportedSuccess = new AtomicInteger();
    private final AtomicInteger lastReportedFailed = new AtomicInteger();

    protected final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>(1024 * 16));
    // Maps each connected MQTT client to the name it connected with (gateway or device name).
    // mqttClients order is non-deterministic (connects run concurrently), so this is the reliable
    // way to label a client by its actual entity name.
    protected final Map<MqttClient, String> clientNames = new ConcurrentHashMap<>();

    // Aggregate MQTT connection health across every client this test creates. Fed by a per-client
    // ConnectionTrackingCallback registered in createClient; emitted by each gateway mode's reporter.
    protected final ConnectionStats connectionStats = new ConnectionStats();

    protected final List<DeviceClient> deviceClients = Collections.synchronizedList(new ArrayList<>(1024 * 16));

    @PostConstruct
    protected void init() {
        super.init();
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
        if (isInboundHandlingEnabled()) {
            mqttHandlerExecutor = new AbstractListeningExecutor() {
                @Override
                protected int getThreadPollSize() {
                    return Math.max(2, Runtime.getRuntime().availableProcessors());
                }
            };
            mqttHandlerExecutor.init();
        }
    }

    /** Whether this test subscribes to inbound MQTT messages and therefore needs a handler executor. */
    protected boolean isInboundHandlingEnabled() {
        return false;
    }

    @PreDestroy
    public void destroy() {
        super.destroy();
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }

        if (mqttHandlerExecutor != null) {
            mqttHandlerExecutor.destroy();
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
            restClientService.getHttpExecutor().submit(() -> {
                try {
                    MqttClient client = initClient(deviceName);
                    mqttClients.add(client);
                    clientNames.put(client, deviceName);
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

    /** Builds an un-connected MQTT client bound to the shared event loop, authenticating with the given token. */
    protected MqttClient createClient(String token) {
        MqttClientConfig config = new MqttClientConfig(getSslContext());
        config.setUsername(token);
        MqttClient client = MqttClient.create(config, null, mqttHandlerExecutor);
        client.setEventLoop(EVENT_LOOP_GROUP);
        client.setCallback(new ConnectionTrackingCallback(connectionStats));
        return client;
    }

    /** Initiates a non-blocking connect; the returned Netty Future completes with the broker result. */
    protected Future<MqttConnectResult> connectAsync(MqttClient client) {
        return client.connect(mqttHost, mqttPort);
    }

    private MqttClient initClient(String token) throws Exception {
        MqttClient client = createClient(token);
        Future<MqttConnectResult> connectFuture = connectAsync(client);
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

    protected SslContext getSslContext() {
        if (mqttSslEnabled) {
            if (StringUtils.isNotBlank(mqttSslKeyStore)) {
                try {
                    TrustManagerFactory trustFact = TrustManagerFactory.getInstance("SunX509");
                    KeyStore trustStore = KeyStore.getInstance("JKS");
                    FileInputStream stream = new FileInputStream(mqttSslKeyStore);
                    trustStore.load(stream, mqttSslKeyStorePassword.toCharArray());
                    trustFact.init(trustStore);
                    return SslContextBuilder.forClient().trustManager(trustFact).build();
                } catch (Exception e) {
                    log.warn("Error while initializing SSL context for keystore [{}]. Will try default SSLContext", mqttSslKeyStore, e);
                }
            }

            try {
                return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build();
            } catch (Exception e) {
                throw new RuntimeException("Error while initializing default SSL context", e);
            }
        }
        return null;
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

    protected void scheduleGatewayStatsReporting() {
        if (statsReportIntervalSec <= 0 || "NONE".equalsIgnoreCase(statsReportMode)) {
            log.info("Gateway stats reporting disabled");
            return;
        }
        if ("LOG".equalsIgnoreCase(statsReportMode)) {
            // logScheduler: separate pool - never blocks the single-threaded test metronome
            reportScheduledFuture = restClientService.getLogScheduler()
                    .scheduleAtFixedRate(() -> log.info(gatewayStatsSummary()), statsReportIntervalSec, statsReportIntervalSec, TimeUnit.SECONDS);
        } else {
            reportScheduledFuture = restClientService.getScheduler()
                    .scheduleAtFixedRate(this::reportMqttClientsStats, statsReportIntervalSec, statsReportIntervalSec, TimeUnit.SECONDS);
        }
    }

    protected String gatewayStatsSummary() {
        long connected = 0;
        for (MqttClient mqttClient : mqttClients) {
            if (mqttClient.isConnected()) {
                connected++;
            }
        }
        int success = totalSuccessPublishedCount.get();
        int failed = totalFailedPublishedCount.get();
        return String.format("Gateway stats: connected %d/%d, published since last report: success=%d, failed=%d",
                connected, mqttClients.size(), success - lastReportedSuccess.getAndSet(success), failed - lastReportedFailed.getAndSet(failed));
    }

    /**
     * One unit of publishing work: which connection, what payload, how many alarm-triggering
     * entries it contains. The DeviceClient doubles as the logging context.
     */
    public record PublishTask(DeviceClient client, byte[] data, int alarmsTriggered) {
    }

    /**
     * Per-publish decision point. Default: sequential walk over deviceClients, one device's message
     * per publish — index = (iteration * testMessagesPerSecond + msgOffsetIdx) % deviceCount. Batch
     * mode overrides this (and only this).
     */
    protected PublishTask nextPublishTask(int iteration, int msgOffsetIdx, boolean alarmRequired, Set<Object> iterationTargets) throws Exception {
        int deviceCount = deviceClients.size();
        int index = (iteration * testMessagesPerSecond + msgOffsetIdx) % deviceCount;
        DeviceClient client = deviceClients.get(index);
        Msg message = getNextMessage(client.getDeviceName(), alarmRequired);
        return new PublishTask(client, message.getData(), message.isTriggersAlarm() ? 1 : 0);
    }

    protected void runApiTestIteration(final int iteration,
                                       AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount,
                                       CountDownLatch testDurationLatch,
                                       final boolean isGateway) {
        try {
            log.info("[{}] Starting performance iteration for {} {}...", iteration, mqttClients.size(), isGateway ? "gateways" : "devices");
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            boolean alarmIteration = iteration >= alarmsStartTs && iteration < alarmsEndTs;
            int alarmCount = 0;
            Set<Object> iterationTargets = new HashSet<>();
            for (int i = 0; i < testMessagesPerSecond; i++) {
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                PublishTask task = nextPublishTask(iteration, i, alarmRequired, iterationTargets);
                alarmCount += task.alarmsTriggered();
                restClientService.getWorkers().submit(() -> {
                    task.client().getMqttClient().publish(getTestTopic(), Unpooled.wrappedBuffer(task.data()), MqttQoS.AT_MOST_ONCE)
                            .addListener(future -> {
                                        if (future.isSuccess()) {
                                            totalSuccessPublishedCount.incrementAndGet();
                                            successPublishedCount.incrementAndGet();
                                            logSuccessTestMessage(iteration, task.client());
                                        } else {
                                            totalFailedPublishedCount.incrementAndGet();
                                            failedPublishedCount.incrementAndGet();
                                            logFailureTestMessage(iteration, task.client(), future);
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
