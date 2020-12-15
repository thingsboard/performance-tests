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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceCredentialsId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.tools.service.customer.CustomerManager;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractAPITest {

    private static ObjectMapper mapper = new ObjectMapper();

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
    @Value("${test.duration:60}")
    protected int testDurationInSec;
    @Value("${test.alarms.start:0}")
    protected int alarmsStartTs;
    @Value("${test.alarms.end:0}")
    protected int alarmsEndTs;
    @Value("${test.alarms.aps:0}")
    protected int alarmsPerSecond;

    @Value("${lwm2m.noSec.enabled:false}")
    protected boolean lwm2mNoSecEnabled;
    @Value("${lwm2m.psk.enabled:false}")
    protected boolean lwm2mPSKEnabled;
    @Value("${lwm2m.rpk.enabled:false}")
    protected boolean lwm2mRPKEnabled;
    @Value("${lwm2m.x509.enabled:false}")
    protected boolean lwm2mX509Enabled;

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

    protected List<Device> devices = Collections.synchronizedList(new ArrayList<>(1024 * 1024));

    protected final Random random = new Random();
    private volatile CountDownLatch testDurationLatch;

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
    protected void destroy() {
        if (reportScheduledFuture != null) {
            reportScheduledFuture.cancel(true);
        }
    }

    protected void createDevices(boolean setCredentials, boolean isLwm2m) throws Exception {
        List<Device> entities = createEntities(deviceStartIdx, deviceEndIdx, false, isLwm2m, setCredentials);
        devices = Collections.synchronizedList(entities);
    }

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

    protected abstract void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch);

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


    protected List<Device> createEntities(int startIdx, int endIdx, boolean isGateway, boolean isLwm2m, boolean setCredentials) throws InterruptedException {
        List<Device> result;
        if (isGateway) {
            result = Collections.synchronizedList(new ArrayList<>(1024));
        } else {
            result = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
        }
        int entityCount = endIdx - startIdx;


        List<CustomerId> customerIds = customerManager.getCustomerIds();
        if (isLwm2m) {
            log.info("Creating on one SecurityMode [{}] lwm2m devices...", entityCount);
            if (this.lwm2mNoSecEnabled) this.createEntitiesLwm2m(entityCount, startIdx, endIdx, result, Lwm2mProfile.NO_SEC);
            if (this.lwm2mPSKEnabled) this.createEntitiesLwm2m(entityCount, startIdx, endIdx, result, Lwm2mProfile.PSK);
            if (this.lwm2mRPKEnabled) this.createEntitiesLwm2m(entityCount, startIdx, endIdx, result, Lwm2mProfile.RPK);
            if (this.lwm2mX509Enabled) this.createEntitiesLwm2m(entityCount, startIdx, endIdx, result, Lwm2mProfile.X509);
        } else {
            log.info("Creating {} {}...", entityCount, (isGateway ? "gateways" : "devices"));
            CountDownLatch latch = new CountDownLatch(entityCount);
            AtomicInteger count = new AtomicInteger();
            for (int i = startIdx; i < endIdx; i++) {
                final int tokenNumber = i;
                restClientService.getHttpExecutor().submit(() -> {
                    Device entity = new Device();
                    try {
                        String token = getToken(isGateway, isLwm2m, tokenNumber);
                        if (isGateway) {
                            entity.setName(token);
                            entity.setType("gateway");
                            entity.setAdditionalInfo(mapper.createObjectNode().putObject("additionalInfo").put("gateway", true));
                        } else {
                            entity.setName(token);
                            entity.setType("device");
                        }

                        if (setCredentials) {
                            entity = restClientService.getRestClient().createDevice(entity, token);
                        } else {
                            entity = restClientService.getRestClient().createDevice(entity);
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
        }
        return result;
    }

    private void createEntitiesLwm2m(int entityCount, int startIdx, int endIdx, List<Device> result, Lwm2mProfile profileName)  throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = startIdx; i < endIdx; i++) {
            final int tokenNumber = i;
            restClientService.getHttpExecutor().submit(() -> {
                Device entity = new Device();
                try {
                    String token = getToken(false, true, tokenNumber);
                    entity.setName(token);
                    entity.setType(profileName.type);
//                    UUID id = UUIDConverter.fromString("3fce0eb03edf11eb90efd1a8958d2f78");
//                    entity.setDeviceProfileId(new DeviceProfileId(id));
                    /**
                     * Api -> /device?accessToken=null
                     * 1) create
                     Device
                     [tenantId=null,
                     customerId=null,
                     name=Nooo,
                     type=null,
                     label=,
                     deviceProfileId=3fce0eb0-3edf-11eb-90ef-d1a8958d2f78,
                     deviceData=null,
                     additionalInfo={"gateway":false,"description":""},
                     createdTime=0,
                     id=null]
                     *
                     * 2) after create:
                     * DeviceCredentials
                     *     private DeviceId deviceId;
                     *     private DeviceCredentialsType credentialsType;
                     *     private String credentialsId;
                     *     private String credentialsValue;
                     * saveDeviceCredentials
                     * DeviceCredentials [
                     * deviceId=1c8c1360-3eef-11eb-90ef-d1a8958d2f78,
                     * credentialsType=LWM2M_CREDENTIALS,
                     * credentialsId=default_client_lwm2m_end_point_no_sec,
                     * credentialsValue={"client":{"securityConfigClientMode":"NO_SEC"},"bootstrap":{"bootstrapServer":{"securityMode":"NO_SEC","clientPublicKeyOrId":"","clientSecretKey":""},"lwm2mServer":{"securityMode":"NO_SEC","clientPublicKeyOrId":"","clientSecretKey":""}}},
                     * createdTime=1608048226210,
                     * id=1c8de820-3eef-11eb-90ef-d1a8958d2f78
                     * ]
                     *
                     */
//                    entity = restClientService.getRestClient().createDevice(entity, token);
//                    entity = restClientService.getRestClient().createDevice(entity);
                    entity = restClientService.getRestClient().saveDevice(entity, token);
                    Optional<DeviceCredentials> deviceCredentialsOpt = restClientService.getRestClient().getDeviceCredentialsByDeviceId(entity.getId());
                    if (deviceCredentialsOpt.isPresent()) {
                        DeviceCredentials deviceCredentials = deviceCredentialsOpt.get();
                        this.updateDeviceCredentials(deviceCredentials);

                        DeviceCredentials entityCredo = restClientService.getRestClient().saveDeviceCredentials(deviceCredentials);
                        result.add(entity);
                    }
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
                log.info("[{}] [{}] have been created so far...", count.get(), "lwm2m_" + profileName.type);
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);

        log.info("[{}] [{}] have been created successfully!", result.size(), "lwm2m");
    }

    /**
     * DeviceCredentials [
     * deviceId=1c8c1360-3eef-11eb-90ef-d1a8958d2f78,
     * credentialsType=LWM2M_CREDENTIALS,
     * credentialsId=default_client_lwm2m_end_point_no_sec,
     * credentialsValue={"client":{"securityConfigClientMode":"NO_SEC"},"bootstrap":{"bootstrapServer":{"securityMode":"NO_SEC","clientPublicKeyOrId":"","clientSecretKey":""},"lwm2mServer":{"securityMode":"NO_SEC","clientPublicKeyOrId":"","clientSecretKey":""}}},
     * createdTime=1608048226210,
     * id=1c8de820-3eef-11eb-90ef-d1a8958d2f78
     * ]
     *
     * @return
     */
    private void updateDeviceCredentials(DeviceCredentials deviceCredentials) {
        deviceCredentials.setCredentialsType(DeviceCredentialsType.LWM2M_CREDENTIALS);
        String credentialsValue = "{\"client\":{\"securityConfigClientMode\":\"NO_SEC\"},\"bootstrap\":{\"bootstrapServer\":{\"securityMode\":\"NO_SEC\",\"clientPublicKeyOrId\":\"\",\"clientSecretKey\":\"\"},\"lwm2mServer\":{\"securityMode\":\"NO_SEC\",\"clientPublicKeyOrId\":\"\",\"clientSecretKey\":\"\"}}}";
        deviceCredentials.setCredentialsValue(credentialsValue);
    }

    protected String getToken(boolean isGateway, boolean isLwm2m, int token) {
        return (isGateway ? "GW" : isLwm2m ? "LW" : "DW") + String.format("%8d", token).replace(" ", "0");
    }

    protected Msg getNextMessage(String deviceName, boolean alarmRequired) {
        return (telemetryTest ? tsMsgGenerator : attrMsgGenerator).getNextMessage(deviceName, alarmRequired);
    }
}
