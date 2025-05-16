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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.tools.service.shared.RestClientService;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Service
public class DeviceProfileManagerImpl implements DeviceProfileManager {

    public static final String DEVICE_PROFILE_RESOURCE_PATH = "device/profile/";
    final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private RestClientService restClientService;

    @Value("${deviceProfile.createOnStart:true}")
    private boolean createOnStart;

    @Value("${deviceProfile.deleteOnComplete:false}")
    private boolean deleteOnComplete;

    @Value("${deviceProfile.deleteIfExists:false}")
    private boolean deleteIfExists;

    @Getter
    private final ConcurrentMap<String, DeviceProfile> deviceProfiles = new ConcurrentHashMap<>();
    private final List<DeviceProfile> deviceProfilesCreated = new ArrayList<>();

    @PostConstruct
    public void init() {
    }


    List<String> getFiles() throws IOException {
        return IOUtils.readLines(this.getClass().getClassLoader().getResourceAsStream(DEVICE_PROFILE_RESOURCE_PATH), UTF_8);
    }

    @Override
    public void createDeviceProfiles() throws Exception {
        List<String> files = getFiles();
        List<DeviceProfile> existedDeviceProfileList = getRestClient().getDeviceProfiles(new PageLink(1000))
                .getData();
        Map<String, DeviceProfile> existedDeviceProfileMap = existedDeviceProfileList.stream()
                .collect(Collectors.toMap(DeviceProfile::getName, v -> v));

        for (String file : files) {
            DeviceProfile deviceProfile = loadDeviceProfile(file);
            String name = deviceProfile.getName();
            if (existedDeviceProfileMap.containsKey(name)) {
                log.info("Device profile [{}] already exists", name);
                deviceProfiles.put(name, existedDeviceProfileMap.get(name));
            } else {
                if (createOnStart) {
                    DeviceProfile deviceProfileSaved = getRestClient().saveDeviceProfile(deviceProfile);
                    log.info("Device profile [{}] have been created", name);
                    deviceProfiles.put(name, deviceProfileSaved);
                    deviceProfilesCreated.add(deviceProfileSaved);
                } else {
                    throw new RuntimeException("Device profile " + name + " not found, but create on start is not allowed");
                }
            }
        }

    }

    @Override
    public void removeDeviceProfiles() throws Exception {
        if (!deleteOnComplete) {
            return;
        }
        for (DeviceProfile deviceProfile : deviceProfilesCreated) {
            getRestClient().deleteDeviceProfile(deviceProfile.getId());
            log.info("Device profile [{}] have been deleted", deviceProfile.getName());
        }
    }

    @Override
    public DeviceProfile getByName(String name){
        DeviceProfile deviceProfile = deviceProfiles.get(name);
        if (deviceProfile == null) {
            throw new RuntimeException("device profile not found for name " + name);
        }
        return deviceProfile;
    }

    DeviceProfile loadDeviceProfile(String filename) {
        try {
            String content = getFile(filename);
            return mapper.readValue(content, DeviceProfile.class);
        } catch (Exception e) {
            log.error("[{}] Failed to load device profile", filename, e);
            throw new RuntimeException(e);
        }
    }

    String getFile(String file) throws IOException {
        return new String(IOUtils.resourceToByteArray(DEVICE_PROFILE_RESOURCE_PATH + file, this.getClass().getClassLoader()), UTF_8);
    }

    RestClient getRestClient() {
        return restClientService.getRestClient();
    }
}
