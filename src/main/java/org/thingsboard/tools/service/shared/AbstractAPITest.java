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
package org.thingsboard.tools.service.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.device.DeviceProfileManager;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractAPITest {
    protected static ObjectMapper mapper = new ObjectMapper();

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

    @Value("${test.sequential:false}")
    protected boolean sequentialTest;
    @Value("${test.telemetry:true}")
    protected boolean telemetryTest;
    @Value("${test.mps:1000}")
    protected int testMessagesPerSecond;
    @Value("${test.duration:60}")
    protected int testDurationInSec;
    @Value("${test.alarms.start:0}")
    protected int alarmsStartTs;
    @Value("${test.alarms.end:999999}")
    protected int alarmsEndTs;
    @Value("${test.alarms.aps:1}")
    protected int alarmsPerSecond;
    @Value("${test.seed:0}")
    protected int seed;
    @Value("${test.payloadType:SMART_METER}")
    protected String payloadType;

    @Autowired
    @Qualifier("randomTelemetryGenerator")
    protected MessageGenerator tsMsgGenerator;
    @Autowired
    @Qualifier("randomAttributesGenerator")
    protected MessageGenerator attrMsgGenerator;

    @Autowired
    protected RestClientService restClientService;
    @Autowired
    protected CustomerManager customerManager;

    @Autowired
    DeviceProfileManager deviceProfileManager;

    protected List<Device> devices = Collections.synchronizedList(new ArrayList<>(1024 * 8));
    public Set<String> clientTryingToConnect = ConcurrentHashMap.newKeySet(1024 * 8);
    public Map<String, String> clientAccessConnect = new ConcurrentHashMap<>(1024 * 8);

    protected Random random;
    CountDownLatch testDurationLatch;

    protected int deviceStartIdx;
    protected int deviceEndIdx;
    protected int instanceIdx;

    private String suffix = "";

    @PostConstruct
    protected void init() {
        random = new Random(seed);
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
    protected void destroy() {
        if (reportScheduledFuture != null) {
            reportScheduledFuture.cancel(true);
        }
    }

    protected void initDeviceSuffix(String suffix){
        this.suffix = suffix;
    }

    protected void createDevices(boolean setCredentials) throws Exception {
        List<Device> entities = createEntities(deviceStartIdx, deviceEndIdx, false, setCredentials);
        devices = Collections.synchronizedList(entities);
    }

    protected void runApiTests(int deviceCount) throws InterruptedException {
        log.info("Sorting {} devices...", deviceCount);
        devices.sort(Comparator.comparing(Device::getName));
        log.info("Shuffling {} devices with random seed {}...", deviceCount, seed);
        Collections.shuffle(devices, new Random(seed));
        log.info("Starting performance test for {} devices...", deviceCount);
        AtomicInteger totalSuccessCount = new AtomicInteger();
        AtomicInteger totalFailedCount = new AtomicInteger();
        testDurationLatch = new CountDownLatch(testDurationInSec);
        AtomicInteger iterationNumber = new AtomicInteger();
        ScheduledFuture<?> scheduledFuture = restClientService.getScheduler().scheduleAtFixedRate(() -> {
            try {
                runApiTestIteration(iterationNumber.incrementAndGet(), totalSuccessCount, totalFailedCount, testDurationLatch);
            } catch (Exception e) {
                log.error("Failed to run performance iteration {}", iterationNumber.get(), e);
            }
        }, 0, 1, TimeUnit.SECONDS);
        log.info("Awaiting all iteration completion...");
        testDurationLatch.await((long) (testDurationInSec * 1.2), TimeUnit.SECONDS);
        scheduledFuture.cancel(true);
        log.info("Completed performance iteration. Success: {}, Failed: {}", totalSuccessCount.get(), totalFailedCount.get());
    }

    protected abstract void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch);

    protected void removeEntities(RestClient restClient, List<DeviceId> entityIds, String typeDevice) throws InterruptedException {
        log.info("Removing [{}] [{}]...", typeDevice, entityIds.size());
        CountDownLatch latch = new CountDownLatch(entityIds.size());
        AtomicInteger count = new AtomicInteger();
        for (DeviceId entityId : entityIds) {
            restClientService.getHttpExecutor().submit(() -> {
                try {
                    restClient.deleteDevice(entityId);
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting [{}]", typeDevice, getHttpErrorException(e));
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] [{}] have been removed so far...", typeDevice, count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("[{}] [{}] have been removed successfully! {} were failed for removal!", count.get(), typeDevice, entityIds.size() - count.get());
    }


    protected List<Device> createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException {
        List<Device> result;
        if (isGateway) {
            result = Collections.synchronizedList(new ArrayList<>(1024));
        } else {
            result = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
        }
        int entityCount = endIdx - startIdx;


        List<CustomerId> customerIds = customerManager.getCustomerIds();

        log.info("Creating {} {}...", entityCount, (isGateway ? "gateways" : "devices"));
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = startIdx; i < endIdx; i++) {
            final int tokenNumber = i;
            restClientService.getHttpExecutor().submit(() -> {
                Device entity = new Device();
                try {
                    entity.setDeviceProfileId(deviceProfileManager.getByName(payloadType).getId());
                    String token = getToken(isGateway, tokenNumber);
                    if (isGateway) {
                        entity.setName(token);
                        entity.setType("gateway");
                        entity.setAdditionalInfo(mapper.createObjectNode().putObject("additionalInfo").put("gateway", true));
                    } else {
                        entity.setName(token);
                        entity.setType("device");
                    }

                    Optional<Device> existedDevice = restClientService.getRestClient().findDevice(entity.getName());

                    if (existedDevice.isPresent()) {
                        entity = existedDevice.get();
                    } else if (setCredentials) {
                        entity = restClientService.getRestClient().saveDevice(entity, token);
                    } else {
                        entity = restClientService.getRestClient().saveDevice(entity);
                    }

                    result.add(entity);

                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity [{}] [{}]", entity.getName(), getHttpErrorException(e));
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

    protected String getToken(boolean isGateway, int token) {
        return (isGateway ? "GW" : "DW") + String.format("%8d", token).replace(" ", "0") + suffix;
    }

    protected Msg getNextMessage(String deviceName, boolean alarmRequired) {
        return (telemetryTest ? tsMsgGenerator : attrMsgGenerator).getNextMessage(deviceName, alarmRequired);
    }

    protected String getHttpErrorException(Exception e) {
        if (e instanceof HttpClientErrorException) {
            return ((HttpClientErrorException) e).getResponseBodyAsString();
        } else if (e instanceof HttpServerErrorException) {
            return ((HttpServerErrorException) e).getResponseBodyAsString();
        } else {
            return e.toString();
        }
    }

    protected <T> T loadJsonResource(String pathResource, Class<T> type) throws IOException {
//        try {
        JsonNode node = mapper.readTree(this.getClass().getClassLoader().getResourceAsStream(pathResource));
        if (type.equals(JsonNode.class)) {
            return (T) node;
        } else if (type.equals(String.class)) {
            return (T) mapper.writeValueAsString(node);
        } else {
//                String dashboardConfigStr = mapper.writeValueAsString(node);
//                node = mapper.readTree(dashboardConfigStr);
            return mapper.treeToValue(node, type);
        }
//        } catch (Exception e) {
//            log.warn("[{}] Failed to load from resource", pathResource, e);
//            throw new RuntimeException(e);
//        }
    }

//    public Map<String, String> getLwm2mDeviceAPITest1() {
//        return lwm2mDeviceAPITest1;
//    }

}
