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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileInfo;
import org.thingsboard.server.common.data.DeviceProfileProvisionType;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MBootstrapCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MDeviceCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.NoSecClientCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.NoSecServerCredentials;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.device.profile.DisabledDeviceProfileProvisionConfiguration;
import org.thingsboard.server.common.data.device.profile.Lwm2mDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.tools.service.lwm2m.LwM2MClient;
import org.thingsboard.tools.service.lwm2m.LwM2MDeviceClient;
import org.thingsboard.tools.service.shared.AbstractLwM2MAPITest;
import org.thingsboard.tools.service.shared.DefaultRestClientService;
import org.thingsboard.tools.service.shared.DeviceClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MDeviceAPITest extends AbstractLwM2MAPITest implements DeviceAPITest {

    protected final String TRANSPORT_CONFIGURATION = "{\n" +
            "  \"type\": \"LWM2M\",\n" +
            "  \"observeAttr\": {\n" +
            "    \"keyName\": {\n" +
            "      \"/19_1.0/0/0\": \"data\"\n" +
            "    },\n" +
            "    \"observe\": [\n" +
            "      \"/19_1.0/0/0\"\n" +
            "    ],\n" +
            "    \"attribute\": [\n" +
            "    ],\n" +
            "    \"telemetry\": [\n" +
            "      \"/19_1.0/0/0\"\n" +
            "    ],\n" +
            "    \"attributeLwm2m\": {}\n" +
            "  },\n" +
            "  \"bootstrap\": {\n" +
            "    \"servers\": {\n" +
            "      \"binding\": \"U\",\n" +
            "      \"shortId\": 123,\n" +
            "      \"lifetime\": 300,\n" +
            "      \"notifIfDisabled\": true,\n" +
            "      \"defaultMinPeriod\": 1\n" +
            "    },\n" +
            "    \"lwm2mServer\": {\n" +
            "      \"host\": \"localhost\",\n" +
            "      \"port\": 5686,\n" +
            "      \"serverId\": 123,\n" +
            "      \"serverPublicKey\": \"\",\n" +
            "      \"bootstrapServerIs\": false,\n" +
            "      \"clientHoldOffTime\": 1,\n" +
            "      \"bootstrapServerAccountTimeout\": 0\n" +
            "    },\n" +
            "    \"bootstrapServer\": {\n" +
            "      \"host\": \"localhost\",\n" +
            "      \"port\": 5687,\n" +
            "      \"serverId\": 111,\n" +
            "      \"securityMode\": \"NO_SEC\",\n" +
            "      \"serverPublicKey\": \"\",\n" +
            "      \"bootstrapServerIs\": true,\n" +
            "      \"clientHoldOffTime\": 1,\n" +
            "      \"bootstrapServerAccountTimeout\": 0\n" +
            "    }\n" +
            "  },\n" +
            "  \"clientLwM2mSettings\": {\n" +
            "    \"clientOnlyObserveAfterConnect\": 1,\n" +
            "    \"fwUpdateStrategy\": 1,\n" +
            "    \"swUpdateStrategy\": 1\n" +
            "  }\n" +
            "}";

    static String dataAsStr = "{\"t1\":73}";
    static byte[] data = dataAsStr.getBytes(StandardCharsets.UTF_8);

    @Override
    public void createDevices() throws Exception {
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), false);
    }

    @Override
    public void runApiTests() throws InterruptedException {
        super.runApiTests(lwM2MClients.size());
    }

    @Override
    protected byte[] getData(String deviceName) {
        return data;
    }

    @Override
    protected List<Device> createEntities(int startIdx, int endIdx, boolean isGateway, boolean setCredentials) throws InterruptedException {
        List<Device> result = Collections.synchronizedList(new ArrayList<>(1024 * 1024));
        int entityCount = endIdx - startIdx;
        log.info("Creating {} {}...", entityCount, "devices");
        CountDownLatch latch = new CountDownLatch(entityCount);
        AtomicInteger count = new AtomicInteger();
        List<CustomerId> customerIds = customerManager.getCustomerIds();

        DeviceProfileId deviceProfileId = createDeviceProfile("device");

        for (int i = startIdx; i < endIdx; i++) {
            final int endpointNumber = i;
            restClientService.getHttpExecutor().submit(() -> {
                Device entity = new Device();
                RestClient restClient = restClientService.getRestClient();
                try {
                    String endpoint = getToken(false, endpointNumber);
                    entity.setName(endpoint);
                    entity.setDeviceProfileId(deviceProfileId);

                    if (!customerIds.isEmpty()) {
                        int entityIdx = endpointNumber - startIdx;
                        int customerIdx = entityIdx % customerIds.size();
                        CustomerId customerId = customerIds.get(customerIdx);
                        entity.setOwnerId(customerId);
                    }

                    entity = restClient.saveDevice(entity);

                    DeviceCredentials deviceCredentials = restClient.getDeviceCredentialsByDeviceId(entity.getId()).get();

                    deviceCredentials.setCredentialsType(DeviceCredentialsType.LWM2M_CREDENTIALS);

                    LwM2MDeviceCredentials credentials = new LwM2MDeviceCredentials();
                    NoSecClientCredentials clientCredentials = new NoSecClientCredentials();
                    clientCredentials.setEndpoint(endpoint);

                    LwM2MBootstrapCredentials defaultBootstrapCredentials = new LwM2MBootstrapCredentials();

                    NoSecServerCredentials serverCredentials = new NoSecServerCredentials();

                    defaultBootstrapCredentials.setBootstrapServer(serverCredentials);
                    defaultBootstrapCredentials.setLwm2mServer(serverCredentials);

                    credentials.setClient(clientCredentials);
                    credentials.setBootstrap(defaultBootstrapCredentials);

                    deviceCredentials.setCredentialsValue(JacksonUtil.toString(credentials));
                    restClient.saveDeviceCredentials(deviceCredentials);

                    result.add(entity);

                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity", e);
                    if (entity != null && entity.getId() != null) {
                        restClient.deleteDevice(entity.getId());
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

    @Override
    protected DeviceProfileId createDeviceProfile(String name) {
        List<DeviceProfileInfo> data = restClientService.getRestClient().getDeviceProfileInfos(
                new PageLink(1, 0, name), DeviceTransportType.DEFAULT).getData();
        if (!CollectionUtils.isEmpty(data)) {
            return (DeviceProfileId) data.get(0).getId();
        }

        DeviceProfile deviceProfile = new DeviceProfile();

        deviceProfile.setName(name);
        deviceProfile.setType(DeviceProfileType.DEFAULT);
        deviceProfile.setTransportType(DeviceTransportType.LWM2M);
        deviceProfile.setProvisionType(DeviceProfileProvisionType.DISABLED);
        deviceProfile.setDescription(deviceProfile.getName());

        DeviceProfileData deviceProfileData = new DeviceProfileData();
        deviceProfileData.setConfiguration(new DefaultDeviceProfileConfiguration());
        deviceProfileData.setProvisionConfiguration(new DisabledDeviceProfileProvisionConfiguration(null));
        deviceProfileData.setTransportConfiguration(JacksonUtil.fromString(TRANSPORT_CONFIGURATION, Lwm2mDeviceProfileTransportConfiguration.class));
        deviceProfile.setProfileData(deviceProfileData);

        return restClientService.getRestClient().saveDeviceProfile(deviceProfile).getId();
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        log.info("[{}] Starting performance iteration for {} {}...", iteration, lwM2MClients.size(), "devices");
        super.runApiTestIteration(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, testDurationLatch);
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] Message was successfully published to device: {}", iteration, client.getDeviceName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Throwable t) {
        log.error("[{}] Error while publishing message to device: {}", iteration, client.getDeviceName(), t);
    }

    @Override
    public void connectDevices() throws InterruptedException {
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> devicesNames;
        if (!devices.isEmpty()) {
            devicesNames = devices.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            devicesNames = new ArrayList<>();
            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
                devicesNames.add(getToken(false, i));
            }
        }
        for (String device : devicesNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(device);
            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, false);
                Thread.sleep(100 + new Random().nextInt(100));
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectDevices(pack, totalConnectedCount, false);
        }
        mapDevicesToDeviceClientConnections();
    }

    private void mapDevicesToDeviceClientConnections() {
        for (LwM2MClient lwM2MClient : lwM2MClients) {
            LwM2MDeviceClient client = new LwM2MDeviceClient();
            client.setDeviceName(lwM2MClient.getName());
            client.setClient(lwM2MClient);
            deviceClients.add(client);
        }
    }
}
