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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.client.AsyncRestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "HTTP")
public class DeviceHttpAPITest extends BaseDeviceAPITest {

    private EventLoopGroup eventLoopGroup;
    private AsyncRestTemplate httpClient;

    @PostConstruct
    void init() {
        super.init();
        this.eventLoopGroup = new NioEventLoopGroup();
        Netty4ClientHttpRequestFactory nettyFactory = new Netty4ClientHttpRequestFactory(this.eventLoopGroup);
        httpClient = new AsyncRestTemplate(nettyFactory);
    }

    @PreDestroy
    void destroy() {
        super.destroy();
        if (this.eventLoopGroup != null) {
            this.eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void runApiTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException {
        log.info("Starting performance test for {} devices...", deviceCount);
        long maxDelay = (publishTelemetryPause + 1) * publishTelemetryCount;
        final int totalMessagesToPublish = deviceCount * publishTelemetryCount;
        AtomicInteger totalPublishedCount = new AtomicInteger();
        AtomicInteger successPublishedCount = new AtomicInteger();
        AtomicInteger failedPublishedCount = new AtomicInteger();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(dataAsStr, headers);

        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            testExecutor.submit(() -> {
                testPublishExecutor.scheduleAtFixedRate(() -> {
                    try {
                        String token = getToken(tokenNumber);
                        String url = restUrl + "/api/v1/" + token + "/telemetry";
                        ListenableFuture<ResponseEntity<Void>> future = httpClient.exchange(url, HttpMethod.POST, entity, Void.class);
                        future.addCallback(new ListenableFutureCallback<ResponseEntity>() {
                            @Override
                            public void onFailure(Throwable throwable) {
                                failedPublishedCount.getAndIncrement();
                                log.error("Error while publishing telemetry, token: {}", tokenNumber, throwable);

                                totalPublishedCount.getAndIncrement();
                                logPublishedMessages(totalPublishedCount.get(), totalMessagesToPublish, tokenNumber);
                            }

                            @Override
                            public void onSuccess(ResponseEntity responseEntity) {
                                if (responseEntity.getStatusCode().is2xxSuccessful()) {
                                    successPublishedCount.getAndIncrement();
                                } else {
                                    failedPublishedCount.getAndIncrement();
                                    log.error("Error while publishing telemetry, token: {}, status code: {}", tokenNumber, responseEntity.getStatusCode().getReasonPhrase());
                                }

                                totalPublishedCount.getAndIncrement();
                                logPublishedMessages(totalPublishedCount.get(), totalMessagesToPublish, tokenNumber);
                            }
                        });
                    } catch (Exception e) {
                        log.error("Error while publishing telemetry, token: {}", tokenNumber, e);
                    }
                }, 0, publishTelemetryPause, TimeUnit.MILLISECONDS);
            });
        }
        Thread.sleep(maxDelay);
        testPublishExecutor.shutdownNow();
        log.info("Performance test was completed for {} devices!", deviceCount);
        log.info("{} messages were published successfully, {} failed!", successPublishedCount.get(), failedPublishedCount.get());
    }

    private void logPublishedMessages(int count, int totalMessagesToPublish, int tokenNumber) {
        if (tokenNumber == deviceStartIdx) {
            log.info("[{}] messages have been published. [{}] messages to publish. Total [{}].",
                    count, totalMessagesToPublish - count, totalMessagesToPublish);
        }
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
