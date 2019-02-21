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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.client.AsyncRestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
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
        restClient.login(username, password);
        log.info("Starting performance test for {} devices...", deviceCount);
        long maxDelay = publishTelemetryPause * publishTelemetryCount;
        final int totalMessagesToPublish = deviceCount * publishTelemetryCount;
        AtomicInteger totalPublishedCount = new AtomicInteger();
        AtomicInteger successPublishedCount = new AtomicInteger();
        AtomicInteger failedPublishedCount = new AtomicInteger();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(dataAsStr, headers);

        int idx = 0;
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            final int tokenNumber = i;
            final int delayPause = (int) ((double) publishTelemetryPause / deviceCount * idx);
            idx++;
            schedulerExecutor.scheduleAtFixedRate(() -> {
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
                        }
                    });
                } catch (Exception e) {
                    log.error("Error while publishing telemetry, token: {}", tokenNumber, e);
                }
            }, delayPause, publishTelemetryPause, TimeUnit.MILLISECONDS);
        }

        ScheduledFuture<?> scheduledLogFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] messages have been published successfully. [{}] failed. [{}] total.",
                        successPublishedCount.get(), failedPublishedCount.get(), totalMessagesToPublish);
            } catch (Exception ignored) {
            }
        }, 0, PUBLISHED_MESSAGES_LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.MINUTES);

        Thread.sleep(maxDelay);
        scheduledLogFuture.cancel(true);
        tokenRefreshScheduleFuture.cancel(true);
        schedulerExecutor.shutdownNow();

        log.info("Performance test was completed for {} devices!", deviceCount);
        log.info("{} messages were published successfully, {} failed!", successPublishedCount.get(), failedPublishedCount.get());
    }

    @Override
    public void warmUpDevices(final int publishTelemetryPause) throws InterruptedException {
        restClient.login(username, password);
        log.info("Warming up {} devices...", deviceCount);
        CountDownLatch connectLatch = new CountDownLatch(deviceCount);
        AtomicInteger totalWarmedUpCount = new AtomicInteger();
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
                    totalWarmedUpCount.getAndIncrement();
                }
            });
        }

        ScheduledFuture<?> scheduledLogFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] devices have been warmed up!", totalWarmedUpCount.get());
            } catch (Exception ignored) {
            }
        }, 0, LOG_PAUSE, TimeUnit.SECONDS);

        ScheduledFuture<?> tokenRefreshScheduleFuture = schedulerLogExecutor.scheduleAtFixedRate(() -> {
            try {
                restClient.login(username, password);
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.MINUTES);

        connectLatch.await();
        scheduledLogFuture.cancel(true);
        tokenRefreshScheduleFuture.cancel(true);
    }
}
