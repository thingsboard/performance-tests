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
package org.thingsboard.tools.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.EntityGroupId;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.tools.service.gateway.DeviceGatewayClient;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DashboardManager {

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    @Value("${test.dashboardNames:}")
    private String dashboardNamesStr;

    private RestClient restClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private EntityGroupInfo deviceGroupAll;

    public void createDashboards(List<DeviceGatewayClient> devices, boolean deleteIfExists) {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);


        List<String> dashboardNames = Arrays.asList(dashboardNamesStr.split(","));

        // Deleting existing dashboards if needed
        List<DashboardInfo> dashboards = restClient.findTenantDashboards();
        for (String dashboardName : dashboardNames) {
            for (DashboardInfo existing : dashboards) {
                if (existing.getName().equalsIgnoreCase(dashboardName)) {
                    if (deleteIfExists) {
                        restClient.deleteDashboard(existing.getId());
                    } else {
                        log.warn("Duplicating dashboard with name: {}. Existing dashboard id: {}", dashboardName, existing.getId());
                    }
                }
            }
        }

        List<EntityGroupInfo> deviceGroups = restClient.findTenantEntityGroups(EntityType.DEVICE);
        deviceGroupAll = deviceGroups.stream().filter(g -> g.getName().equalsIgnoreCase("ALL")).findFirst().orElseThrow(() -> new RuntimeException("Group All is not found!"));

        // Loading new dashboards
        for (String dashboardName : dashboardNames) {
            loadDashboard(dashboardName, devices);
        }
    }

    private void loadDashboard(String dashboardName, List<DeviceGatewayClient> devices) {
        try {
            JsonNode dashboardConfig = objectMapper.readTree(this.getClass().getClassLoader().getResourceAsStream(dashboardName));
            String dashboardConfigStr = objectMapper.writeValueAsString(dashboardConfig);
            dashboardConfigStr = dashboardConfigStr.replaceAll("DEVICE_GROUP_ALL", deviceGroupAll.getId().getId().toString());
            dashboardConfig = objectMapper.readTree(dashboardConfigStr);
            Dashboard dashboard = objectMapper.treeToValue(dashboardConfig, Dashboard.class);
            restClient.createDashboard(dashboard);
            log.info("[{}] Created dashboard", dashboardName);
        } catch (Exception e) {
            log.warn("[{}] Failed to load dashboard", dashboardName, e);
        }

    }
}
