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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class BaseDeviceAPITest implements DeviceAPITest {

    static String dataAsStr = "{\"longKey\":73}";
    static byte[] data = dataAsStr.getBytes(StandardCharsets.UTF_8);

    static Random randomInt = new Random();

    static protected ObjectMapper mapper = new ObjectMapper();

    @Value("${device.startIdx}")
    int deviceStartIdx;

    @Value("${device.endIdx}")
    int deviceEndIdx;

    @Value("${rest.url}")
    String restUrl;

    @Value("${rest.username}")
    String username;

    @Value("${rest.password}")
    String password;

    RestClient restClient;

    int deviceCount;

    final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    final ScheduledExecutorService schedulerExecutor = Executors.newScheduledThreadPool(10);
    final ExecutorService testExecutor = Executors.newFixedThreadPool(100);

    private final List<DeviceId> deviceIds = Collections.synchronizedList(new ArrayList<>());

    void init() {
        deviceCount = deviceEndIdx - deviceStartIdx;
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
    }

    void destroy() {
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdown();
        }
    }

    String getToken(int token) {
        return String.format("%20d", token).replace(" ", "0");
    }

    @Override
    public void createDevices() throws Exception {
        restClient.login(username, password);
        log.info("Creating {} devices...", deviceCount);
        CountDownLatch latch = new CountDownLatch(deviceCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                Device device = null;
                try {
                    String token = getToken(tokenNumber);
                    device = restClient.createDevice("Device " + token, "default");
                    restClient.updateDeviceCredentials(device.getId(), token);
                    deviceIds.add(device.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating device", e);
                    if (device != null && device.getId() != null) {
                        restClient.getRestTemplate().delete(restUrl + "/api/device/" + device.getId().getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        httpExecutor.submit(() -> {
            schedulerExecutor.scheduleAtFixedRate(() -> {
                try {
                    log.info("{} devices has been created so far...", count.get());
                } catch (Exception ignored) {}
            }, 0, 5, TimeUnit.SECONDS);
        });
        latch.await();
        schedulerExecutor.shutdownNow();
        log.info("{} devices have been created successfully!", deviceIds.size());
    }

    @Override
    public void removeDevices() throws Exception {
        restClient.login(username, password);
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
            });
        }
        httpExecutor.submit(() -> {
            schedulerExecutor.scheduleAtFixedRate(() -> {
                try {
                    log.info("{} devices has been removed so far...", count.get());
                } catch (Exception ignored) {}
            }, 0, 5, TimeUnit.SECONDS);
        });
        latch.await();
        Thread.sleep(1000);
        log.info("{} devices have been removed successfully! {} were failed for removal!", count.get(), deviceIds.size() - count.get());
    }

}
