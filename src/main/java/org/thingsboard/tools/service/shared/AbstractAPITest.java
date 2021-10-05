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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.msg.RandomAttributesGenerator;
import org.thingsboard.tools.service.msg.RandomTelemetryGenerator;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractAPITest {

    public static ObjectMapper mapper = new ObjectMapper();

    protected ScheduledFuture<?> reportScheduledFuture;

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
    @Value("${test.rmps:100}")
    protected int testRpcMessagesPerSecond;
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

    protected final List<DeviceClient> deviceClients = Collections.synchronizedList(new ArrayList<>(1024 * 1024));

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

        log.info("Initialized with deviceStartIdx [{}], deviceEndIdx [{}]", this.deviceStartIdx, this.deviceEndIdx);
    }

    @PreDestroy
    public void destroy() {
        if (reportScheduledFuture != null) {
            reportScheduledFuture.cancel(true);
        }
    }

    protected void createDevices(boolean setCredentials) throws Exception {
        List<Device> entities = createEntities(deviceStartIdx, deviceEndIdx, false, setCredentials);
        devices = Collections.synchronizedList(entities);
    }

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

    protected int getDeviceIndex(int iteration, int msgOffsetIdx) {
        int iterationOffset = (iteration * testMessagesPerSecond) % deviceClients.size();
        return (iterationOffset + msgOffsetIdx) % deviceClients.size();
    }

    protected abstract List<Device> createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException;

    protected abstract DeviceProfileId createDeviceProfile(String name);

    protected String getToken(boolean isGateway, int token) {
        return (isGateway ? "GW" : "DW") + String.format("%8d", token).replace(" ", "0");
    }

    protected void runApiTestIteration(int iteration,
                                       AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount,
                                       CountDownLatch testDurationLatch) {
        try {
            AtomicInteger successPublishedCount = new AtomicInteger();
            AtomicInteger failedPublishedCount = new AtomicInteger();
            CountDownLatch iterationLatch = new CountDownLatch(testMessagesPerSecond + testRpcMessagesPerSecond);
            boolean alarmIteration = iteration >= alarmsStartTs && iteration < alarmsEndTs;
            int alarmCount = 0;
            for (int i = 0; i < testMessagesPerSecond; i++) {
                boolean alarmRequired = alarmIteration && (alarmCount < alarmsPerSecond);
                DeviceClient client = deviceClients.get(getDeviceIndex(iteration, i));
                Msg message = (telemetryTest ? tsMsgGenerator : attrMsgGenerator).getNextMessage(client.getDeviceName(), alarmRequired);
                if (message.isTriggersAlarm()) {
                    alarmCount++;
                }
                restClientService.getWorkers().submit(() -> {
                    send(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, successPublishedCount,
                            failedPublishedCount, testDurationLatch, iterationLatch, client, message);
                });
            }

            for (int i = 0; i < testRpcMessagesPerSecond; i++) {
                int deviceIndex = getDeviceIndex(iteration, i);
                DeviceClient client = deviceClients.get(deviceIndex);
                Device device = devices.get(deviceIndex);
                restClientService.getWorkers().submit(() -> {
                    restClientService.getRestClient().handleTwoWayDeviceRPCRequest(device.getId(), createRpc(client));
                    iterationLatch.countDown();
                });
            }
            iterationLatch.await();
            log.info("[{}] Completed performance iteration. Success: {}, Failed: {}, Alarms: {}", iteration, successPublishedCount.get(), failedPublishedCount.get(), alarmCount);
            testDurationLatch.countDown();
        } catch (Throwable t) {
            log.warn("[{}] Failed to process iteration", iteration, t);
        }
    }

    protected abstract void send(int iteration,
                                 AtomicInteger totalSuccessPublishedCount,
                                 AtomicInteger totalFailedPublishedCount,
                                 AtomicInteger successPublishedCount,
                                 AtomicInteger failedPublishedCount,
                                 CountDownLatch testDurationLatch,
                                 CountDownLatch iterationLatch,
                                 DeviceClient client,
                                 Msg message);

    protected abstract ObjectNode createRpc(DeviceClient client);

    protected abstract void logSuccessTestMessage(int iteration, DeviceClient client);

    protected abstract void logFailureTestMessage(int iteration, DeviceClient client, Throwable t);
}
