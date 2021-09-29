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
import org.springframework.util.CollectionUtils;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileInfo;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.tools.service.mqtt.MqttDeviceClient;
import org.thingsboard.tools.service.msg.Msg;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractMqttAPITest extends AbstractAPITest {

    protected final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());


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

    private EventLoopGroup EVENT_LOOP_GROUP;
    private static final int CONNECT_TIMEOUT = 5;

    @PostConstruct
    protected void init() {
        super.init();
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
    }

    @Override
    protected void send(int iteration,
                        AtomicInteger totalSuccessPublishedCount,
                        AtomicInteger totalFailedPublishedCount,
                        AtomicInteger successPublishedCount,
                        AtomicInteger failedPublishedCount,
                        CountDownLatch testDurationLatch,
                        CountDownLatch iterationLatch,
                        DeviceClient client,
                        Msg message) {
        ((MqttDeviceClient) client).getMqttClient().publish(getTestTopic(), Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_LEAST_ONCE)
                .addListener(future -> {
                    if (future.isSuccess()) {
                        totalSuccessPublishedCount.incrementAndGet();
                        successPublishedCount.incrementAndGet();
                        logSuccessTestMessage(iteration, client);
                    } else {
                        totalFailedPublishedCount.incrementAndGet();
                        failedPublishedCount.incrementAndGet();
                        logFailureTestMessage(iteration, client, future.cause());
                    }
                    iterationLatch.countDown();
                });
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

    @Override
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

        if (isGateway) {
            createDeviceProfile("gateway");
        } else {
            createDeviceProfile("device");
        }

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
                        entity = restClientService.getRestClient().saveDevice(entity, token);
                    } else {
                        entity = restClientService.getRestClient().saveDevice(entity);
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

    @Override
    protected DeviceProfileId createDeviceProfile(String name) {
        List<DeviceProfileInfo> data = restClientService.getRestClient().getDeviceProfileInfos(
                new PageLink(1, 0, name), DeviceTransportType.DEFAULT).getData();
        if (!CollectionUtils.isEmpty(data)) {
            return (DeviceProfileId) data.get(0).getId();
        }

        DeviceProfile deviceProfile = new DeviceProfile();
        deviceProfile.setDefault(false);
        deviceProfile.setName(name);
        deviceProfile.setType(DeviceProfileType.DEFAULT);
        deviceProfile.setTransportType(DeviceTransportType.DEFAULT);
        deviceProfile.setProvisionType(DeviceProfileProvisionType.DISABLED);
        deviceProfile.setDescription(name + " device profile");
        DeviceProfileData deviceProfileData = new DeviceProfileData();
        DefaultDeviceProfileConfiguration configuration = new DefaultDeviceProfileConfiguration();
        DefaultDeviceProfileTransportConfiguration transportConfiguration = new DefaultDeviceProfileTransportConfiguration();
        DisabledDeviceProfileProvisionConfiguration provisionConfiguration = new DisabledDeviceProfileProvisionConfiguration(null);
        deviceProfileData.setConfiguration(configuration);
        deviceProfileData.setTransportConfiguration(transportConfiguration);
        deviceProfileData.setProvisionConfiguration(provisionConfiguration);
        deviceProfile.setProfileData(deviceProfileData);
        return restClientService.getRestClient().saveDeviceProfile(deviceProfile).getId();
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

    protected abstract String getTestTopic();


    @Override
    @PreDestroy
    public void destroy() {
        for (MqttClient mqttClient : mqttClients) {
            mqttClient.disconnect();
        }

        if (!EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        super.destroy();
    }
}
