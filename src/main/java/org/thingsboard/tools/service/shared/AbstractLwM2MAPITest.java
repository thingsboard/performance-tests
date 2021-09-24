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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.tools.service.lwm2m.LwM2MClient;
import org.thingsboard.tools.service.lwm2m.LwM2MDeviceClient;
import org.thingsboard.tools.service.msg.Msg;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractLwM2MAPITest extends AbstractAPITest {

    protected final List<LwM2MClient> lwM2MClients = Collections.synchronizedList(new ArrayList<>());


    @Value("${mqtt.host}")
    private String mqttHost;
    @Value("${mqtt.port}")

    private static final int CONNECT_TIMEOUT = 5;

    @PostConstruct
    protected void init() {
        super.init();
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
        try {
            ((LwM2MDeviceClient) client).getClient().send(message);
            totalSuccessPublishedCount.incrementAndGet();
            successPublishedCount.incrementAndGet();
            logSuccessTestMessage(iteration, client);
        } catch (Exception e) {
            totalFailedPublishedCount.incrementAndGet();
            failedPublishedCount.incrementAndGet();
            logFailureTestMessage(iteration, client, e);
        }
        iterationLatch.countDown();
    }

    private LwM2MClient initClient(String endpoint) throws Exception {

        return null;
    }

    protected void connectDevices(List<String> pack, AtomicInteger totalConnectedCount, boolean isGateway) throws InterruptedException {
        final String devicesType = isGateway ? "gateways" : "devices";
        final String deviceType = isGateway ? "gateway" : "device";
        log.info("Connecting {} {}...", pack.size(), devicesType);
        CountDownLatch connectLatch = new CountDownLatch(pack.size());
        for (String deviceName : pack) {
            restClientService.getWorkers().submit(() -> {
                try {
                    lwM2MClients.add(initClient(deviceName));
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


    @Override
    @PreDestroy
    public void destroy() {
        for (LwM2MClient mqttClient : lwM2MClients) {
            mqttClient.destroy();
        }
        super.destroy();
    }
}
