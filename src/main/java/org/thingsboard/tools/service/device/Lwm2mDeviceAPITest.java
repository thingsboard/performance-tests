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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.tools.lwm2m.client.LwM2MClientConfiguration;
import org.thingsboard.tools.lwm2m.client.LwM2MClientContext;
import org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode;
import org.thingsboard.tools.lwm2m.client.objects.LwM2MLocationParams;
import org.thingsboard.tools.service.shared.BaseLwm2mAPITest;
import org.thingsboard.tools.service.shared.DefaultRestClientService;
import org.thingsboard.tools.service.shared.Lwm2mProfile;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class Lwm2mDeviceAPITest extends BaseLwm2mAPITest implements DeviceAPITest {

    @Autowired
    private LwM2MClientContext context;

    @Autowired
    private LwM2MLocationParams locationParams;

    @Override
    public void createDevices() throws Exception {
        List<Device> entities = this.createEntities();
        devices = Collections.synchronizedList(entities);
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroy lwm2m clients...");
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), "lwm2m");
    }

    @Override
    public void warmUpDevices() throws InterruptedException {

    }

    @Override
    public void runApiTests() throws InterruptedException {
        log.info("runApiTests...");
    }

    @Override
    public void connectDevices() throws InterruptedException {
        Set<String> clients = this.connectEntities();
        clientTryingToConnect = Collections.synchronizedSet(clients);
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {

    }


    protected List<Device> createEntities() throws InterruptedException {
        List<Device> result = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
        int entityCount = deviceEndIdx - deviceStartIdx;
        log.info("Creating on one SecurityMode [{}] lwm2m devices...", entityCount);
        if (this.lwm2mNoSecEnabled) this.createEntitiesLwm2m(result, LwM2MSecurityMode.NO_SEC);
        if (this.lwm2mPSKEnabled) this.createEntitiesLwm2m(result, LwM2MSecurityMode.PSK);
        if (this.lwm2mRPKEnabled) this.createEntitiesLwm2m(result, LwM2MSecurityMode.RPK);
        if (this.lwm2mX509Enabled) this.createEntitiesLwm2m(result, LwM2MSecurityMode.X509);
        return result;
    }

    protected Set<String> connectEntities() throws InterruptedException {
        Set<String> result = ConcurrentHashMap.newKeySet(1024 * 1024);
        int nextPortNumber = deviceStartIdx;
        if (this.lwm2mNoSecEnabled)
            nextPortNumber = this.connectEntitiesLwm2m(result, LwM2MSecurityMode.NO_SEC, nextPortNumber);
        if (this.lwm2mPSKEnabled)
            nextPortNumber = this.connectEntitiesLwm2m(result, LwM2MSecurityMode.PSK, nextPortNumber);
        if (this.lwm2mRPKEnabled)
            nextPortNumber = this.connectEntitiesLwm2m(result, LwM2MSecurityMode.RPK, nextPortNumber);
        if (this.lwm2mX509Enabled)
            nextPortNumber = this.connectEntitiesLwm2m(result, LwM2MSecurityMode.X509, nextPortNumber);
        log.info("Trying to  connected [{}] lwm2m clients... nextPortNumber [{}]", result.size(), nextPortNumber);
        return result;
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
    private void createEntitiesLwm2m(List<Device> result, LwM2MSecurityMode mode) throws InterruptedException {
        int entityCount = deviceEndIdx - deviceStartIdx;
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        Lwm2mProfile profileName = Lwm2mProfile.valueOf(mode.name());
        AtomicInteger numberPoint = new AtomicInteger();
        numberPoint.addAndGet(deviceStartIdx);
//        int tokenNumber = 0;
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
//            int finalTokenNumber = tokenNumber;
            int finalI = i;
            restClientService.getLwm2mExecutor().submit(() -> {
                Device entity = new Device();
                try {
                    String token = this.getToken(finalI, mode);
                    entity.setName(token);
                    entity.setType(profileName.profileName);
                    DeviceCredentials credentials = this.getDeviceCredentials(mode, entity.getName());
                    entity = restClientService.getRestClient().saveDeviceWithCredentials(entity, credentials).get();
                    result.add(entity);
                    count.getAndIncrement();
                    numberPoint.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity [{}] [{}]", entity.getName(), getHttpErrorException(e));
                    if (entity != null && entity.getId() != null) {
                        restClientService.getRestClient().deleteDevice(entity.getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
//            tokenNumber++;
        }


        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("[{}] [{}] [{}] have been created so far...", count.get(), numberPoint.get(), "lwm2m_" + profileName.profileName);
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        log.info("[{}] [{}] have been created successfully!", result.size(), "lwm2m");
    }

    private DeviceCredentials getDeviceCredentials(LwM2MSecurityMode mode, String credentialsId) throws IOException {
        DeviceCredentials deviceCredentials = new DeviceCredentials();
        deviceCredentials.setCredentialsType(DeviceCredentialsType.LWM2M_CREDENTIALS);
        String credentialsEndpoint = mode == LwM2MSecurityMode.PSK ? credentialsId + this.getLwm2mPSKIdentitySub() : credentialsId;
        deviceCredentials.setCredentialsId(credentialsEndpoint);
        deviceCredentials.setCredentialsValue(getDeviceCredentialsConfig(mode, credentialsId));
        return deviceCredentials;
    }

    private String getDeviceCredentialsConfig(LwM2MSecurityMode mode, String credentialsId) throws IOException {
        String publicKey = nodeConfigKeys.get(mode.name()).get("clientPublicKeyOrId").asText();
        String privateKey = nodeConfigKeys.get(mode.name()).get("clientSecretKey").asText();
//        JsonNode nodeConfigClient = mapper.createObjectNode();
        JsonNode nodeConfigClient = mapper.valueToTree(nodeConfig);
        ((ObjectNode) nodeConfigClient.get("client")).put("securityConfigClientMode", mode.name());
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("bootstrapServer")).put("securityMode", mode.name());
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("bootstrapServer")).put("clientPublicKeyOrId", publicKey);
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("bootstrapServer")).put("clientSecretKey", privateKey);
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("lwm2mServer")).put("securityMode", mode.name());
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("lwm2mServer")).put("clientPublicKeyOrId", publicKey);
        ((ObjectNode) nodeConfigClient.get("bootstrap").get("lwm2mServer")).put("clientSecretKey", privateKey);
        switch (mode) {
            case NO_SEC:
                break;
            case PSK:
                ((ObjectNode) nodeConfigClient.get("client")).put("endpoint", credentialsId);
                ((ObjectNode) nodeConfigClient.get("client")).put("identity", credentialsId + this.getLwm2mPSKIdentitySub());
                ((ObjectNode) nodeConfigClient.get("client")).put("key", privateKey);
                break;
            case RPK:
                ((ObjectNode) nodeConfigClient.get("client")).put("key", publicKey);
                break;
            case X509:
                ((ObjectNode) nodeConfigClient.get("client")).put("x509", true);
        }
        return mapper.writeValueAsString(nodeConfigClient);
    }

    private int connectEntitiesLwm2m(Set<String> result, LwM2MSecurityMode mode, int nextPortNumber)  throws InterruptedException {
        try {
            int entityCount = deviceEndIdx - deviceStartIdx;
            CountDownLatch latch = new CountDownLatch(entityCount);
            AtomicInteger count = new AtomicInteger();
            int countFor = 2500;
            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
//            int finalTokenNumber = tokenNumber;
                int finalI = i;
                int finalNextPortNumber = nextPortNumber;
                restClientService.getLwm2mExecutor().submit(() -> {
                    try {

                        String endPoint = this.getToken(finalI, mode);
                        LwM2MClientConfiguration clientConfiguration = new LwM2MClientConfiguration(context, locationParams, endPoint, finalNextPortNumber, mode, restClientService.getSchedulerCoapConfig());
                        clientConfiguration.init(clientAccessConnect);

                        result.add(endPoint);
                        count.incrementAndGet();
                    } catch (Throwable e) {
                        log.error("[{}][{}] Throwable [{}]", count, finalNextPortNumber, e.toString());
                        e.printStackTrace();

                    } finally {
                        latch.countDown();
                    }
                });
                nextPortNumber++;
                if (finalI > 0 && finalI == finalI / countFor * countFor) {
                    Thread.sleep(180000); // wait for registration sent to server
                }
            }

            ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
                try {
                    log.info("[{}] [{}] have been connected so far...", count.get(), "lwm2m_" + mode.modeName);
                } catch (Exception ignored) {
                }
            }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);
            latch.await();
            logScheduleFuture.cancel(true);
            log.info("Trying to register to coap [{}] lwm2m clients... nextPortNumber [{}]", count, nextPortNumber);
            return nextPortNumber;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException();
        }
    }

    protected String getToken(int token, LwM2MSecurityMode mode) {
        return "Lw" + LwM2MSecurityMode.fromNameCamelCase(mode.code) + String.format("%8d", token).replace(" ", "0");
    }

}