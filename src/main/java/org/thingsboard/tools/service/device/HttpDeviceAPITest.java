/**
 * Copyright © 2016-2025 The Thingsboard Authors
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.Netty4ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.shared.AbstractAPITest;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "HTTP")
public class HttpDeviceAPITest extends AbstractAPITest implements DeviceAPITest {

    private AsyncRestTemplate httpClient;
    private EventLoopGroup eventLoopGroup;

    @PostConstruct
    public void init() {
        super.init();
        this.deviceCount = this.deviceEndIdx - this.deviceStartIdx;
        this.eventLoopGroup = new NioEventLoopGroup();
        Netty4ClientHttpRequestFactory nettyFactory = new Netty4ClientHttpRequestFactory(this.eventLoopGroup);
        this.httpClient = new AsyncRestTemplate(nettyFactory);
    }

    @PreDestroy
    public void destroy() {
        super.destroy();
        if (this.eventLoopGroup != null) {
            this.eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void createDevices() throws Exception {
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), "devices");
    }

    @Override
    public void warmUpDevices() throws InterruptedException {
    }

    @Override
    public void runApiTests() throws InterruptedException {
        super.runApiTests(deviceCount);
    }

    @Value("${rest.url}")
    private String restUrl;

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        try {
            Set<String> iterationDevices = new HashSet<>();
            log.info("[{}] Starting performance iteration for {} devices...", iteration, deviceCount);
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond);
            boolean alarmIteration = iteration >= alarmsStartTs && iteration < alarmsEndTs;
            int alarmCount = 0;
            for (int i = 0; i < testMessagesPerSecond; i++) {
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                String deviceName = getDeviceName(iterationDevices, iteration, i);
                Msg message = getNextMessage(deviceName, alarmRequired);
                if (message.isTriggersAlarm()) {
                    alarmCount++;
                }
                restClientService.getWorkers().submit(() -> {
                    ListenableFuture<ResponseEntity<DeferredResult<ResponseEntity>>> future =
                            httpClient.exchange(
                                    restUrl + getTestUrl(),
                                    HttpMethod.POST,
                                    new HttpEntity(message.getData()),
                                    new ParameterizedTypeReference<DeferredResult<ResponseEntity>>() {},
                                    deviceName);
                    future.addCallback(new ListenableFutureCallback<ResponseEntity<DeferredResult<ResponseEntity>>>() {
                        @Override
                        public void onSuccess(ResponseEntity<DeferredResult<ResponseEntity>> responseEntity) {
                            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                                totalSuccessPublishedCount.incrementAndGet();
                                successPublishedCount.incrementAndGet();
                                log.debug("[{}] Message was successfully published to device: {}", iteration, deviceName);
                            } else {
                                totalFailedPublishedCount.incrementAndGet();
                                failedPublishedCount.incrementAndGet();
                                log.error("[{}] Error while publishing message to device: {}", iteration, deviceName);
                            }
                            iterationLatch.countDown();
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            totalFailedPublishedCount.incrementAndGet();
                            failedPublishedCount.incrementAndGet();
                            log.error("[{}] Error while publishing message to device: {}", iteration, deviceName, throwable.getCause());
                            iterationLatch.countDown();
                        }

                    });
                });
            }
            iterationLatch.await();
            log.info("[{}] Completed performance iteration. Success: {}, Failed: {}, Alarms: {}", iteration, successPublishedCount.get(), failedPublishedCount.get(), alarmCount);
            testDurationLatch.countDown();
        } catch (Throwable t) {
            log.warn("[{}] Failed to process iteration", iteration, t);
        }
    }

    protected String getDeviceName(Set<String> iterationDevices, int iteration, int msgOffsetIdx) {
        String client;
        if (sequentialTest) {
            int iterationOffset = (iteration * testMessagesPerSecond) % deviceCount;
            int idx = (iterationOffset + msgOffsetIdx) % deviceCount;
            return getToken(false, idx);
        } else {
            while (true) {
                client = getToken(false, random.nextInt(deviceCount));
                if (iterationDevices.add(client)) {
                    break;
                }
            }
        }
        return client;
    }

    private String getTestUrl() {
        return telemetryTest ? "/api/v1/{accessToken}/telemetry" : "/api/v1/{accessToken}/attributes";
    }

    @Override
    public void connectDevices() {
    }

    @Override
    public void generationX509() {

    }
}
