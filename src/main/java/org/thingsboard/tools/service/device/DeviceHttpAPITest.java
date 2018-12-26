/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "HTTP")
public class DeviceHttpAPITest extends BaseDeviceAPITest {

    @PostConstruct
    void init() {
        super.init();
    }

    @PreDestroy
    void destroy() {
        super.destroy();
    }

    @Override
    public void runApiTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException {
        log.info("Starting performance test for {} devices...", deviceCount);
        long maxDelay = (publishTelemetryPause + 1) * publishTelemetryCount;
        final int totalMessagesToPublish = deviceCount * publishTelemetryCount;
        AtomicInteger publishedCount = new AtomicInteger();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            testExecutor.submit(() -> {
                testPublishExecutor.scheduleAtFixedRate(() -> {
                    try {
                        String token = getToken(tokenNumber);
                        restClient.getRestTemplate()
                                .postForEntity(restUrl + "/api/v1/{token}/telemetry",
                                        mapper.readTree(dataAsStr),
                                        ResponseEntity.class,
                                        token);
                        publishedCount.getAndIncrement();
                    } catch (Exception e) {
                        log.error("Error while publishing telemetry, token: {}", tokenNumber, e);
                    } finally {
                        if (publishedCount.get() % deviceCount == 0) {
                            log.info("[{}] messages have been published. [{}] messages to publish. Total [{}].",
                                    publishedCount.get(), totalMessagesToPublish - publishedCount.get(), totalMessagesToPublish);
                        }
                    }
                }, 0, publishTelemetryPause, TimeUnit.MILLISECONDS);
            });
        }
        Thread.sleep(maxDelay);
        testPublishExecutor.shutdownNow();
        log.info("Performance test was completed for {} devices!", deviceCount);
    }

    @Override
    public void warmUpDevices() throws InterruptedException {
        log.info("Warming up {} devices...", deviceCount);
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            httpExecutor.submit(() -> {
                try {
                    String token = getToken(tokenNumber);
                    restClient.getRestTemplate()
                            .postForEntity(restUrl + "/api/v1/{token}/telemetry",
                                    mapper.readTree(dataAsStr),
                                    ResponseEntity.class,
                                    token);
                } catch (Exception e) {
                    log.error("Error while warming up device, token: {}", tokenNumber, e);
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();
    }
}
