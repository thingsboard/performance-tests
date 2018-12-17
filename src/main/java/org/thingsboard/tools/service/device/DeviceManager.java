package org.thingsboard.tools.service.device;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.client.tools.ResultAccumulator;
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

@Slf4j
@Service
public class DeviceManager {

    private static final int CONNECT_TIMEOUT_IN_SECONDS = 5;
    private static byte[] data = "{\"longKey\":73}".getBytes(StandardCharsets.UTF_8);

    @Value("${tests.device.count}")
    private int deviceCount;

    @Value("${tests.rest.url}")
    private String restUrl;

    @Value("${tests.rest.username}")
    private String username;

    @Value("${tests.rest.password}")
    private String password;

    @Value("${tests.mqtt.host}")
    private String mqttHost;

    @Value("${tests.mqtt.port}")
    private int mqttPort;


    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    private final ExecutorService mqttExecutor = Executors.newFixedThreadPool(100);

    private final List<MqttClient> mqttClients = Collections.synchronizedList(new ArrayList<>());
    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>());

    @PostConstruct
    private void init() {}

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
        RestClient restClient = new RestClient(restUrl);
        restClient.login(username, password);
        CountDownLatch latch = new CountDownLatch(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                try {
                    Device device = restClient.createDevice("Device " + UUID.randomUUID(), "default");
                    String token = String.format("%20d", tokenNumber);
                    restClient.updateDeviceCredentials(device.getId(), token);
                    deviceIds.add(device.getId());
                } catch (Exception e) {
                    log.error("Error while creating device: {}", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    public void removeDevices() throws Exception {
        RestClient restClient = new RestClient(restUrl);
        restClient.login(username, password);
        CountDownLatch latch = new CountDownLatch(deviceIds.size());
        for (DeviceId deviceId : deviceIds) {
            httpExecutor.submit(() -> {
                try {
                    restClient.deleteDevice(deviceId);
                } catch (Exception e) {
                    log.error("Error while deleting device: {}", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    public void runTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException {
        CountDownLatch runTestsLatch = new CountDownLatch(mqttClients.size());
        for (MqttClient mqttClient : mqttClients) {
            mqttExecutor.submit(() -> {
                for (int i = 0; i < publishTelemetryCount; i++) {
                    mqttClient.publish("v1/devices/me/telemetry", Unpooled.wrappedBuffer(data), MqttQoS.AT_LEAST_ONCE)
                            .addListener(future -> {
                                        if (future.isSuccess()) {
                                            log.error("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                        } else {
                                            log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                        }
                                    }
                            );
                    try {
                        Thread.sleep(publishTelemetryPause);
                    } catch (InterruptedException e) {
                        log.error("Pause interrupted", e);
                    }
                }
                runTestsLatch.countDown();
            });
        }
        runTestsLatch.await();
    }

    public void warmUpDevices() throws InterruptedException {
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            final int tokenNumber = i;
            mqttExecutor.submit(() -> {
                try {
                    String token = String.format("%20d", tokenNumber);
                    mqttClients.add(initClient(token));
                } catch (Exception e) {
                    log.error("Error while creating device: {}", e);
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
                                        log.info("Message was successfully published to device: {}", mqttClient.getClientConfig().getUsername());
                                    } else {
                                        log.error("Error while publishing message to device: {}", mqttClient.getClientConfig().getUsername());
                                    }
                                    warmUpLatch.countDown();
                                }
                        );
            });
        }
        warmUpLatch.await();
    }
}
