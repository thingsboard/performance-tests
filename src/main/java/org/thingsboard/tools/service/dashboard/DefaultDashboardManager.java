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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.tools.service.shared.RestClientService;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DefaultDashboardManager implements DashboardManager {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private RestClientService restClientService;

    @Value("${dashboard.tenant:}")
    private String[] tenantDashboards;
    @Value("${dashboard.shared:}")
    private String sharedDashboardName;
    @Value("${dashboard.deleteIfExists:true}")
    private boolean deleteIfExists;

    private List<DashboardId> tenantDashboardIds = new ArrayList<>();
    @Getter
    private DashboardId sharedDashboardId;

    @PostConstruct
    public void init() {
    }

    @Override
    public void createDashboards() throws Exception {
        createDashboards(tenantDashboards, deleteIfExists, false);
        createDashboards(new String[]{sharedDashboardName}, deleteIfExists, true);
    }

    @Override
    public void removeDashboards() throws Exception {
        for (DashboardId tenantDashboardId : tenantDashboardIds) {
            getRestClient().deleteDashboard(tenantDashboardId);
        }
        getRestClient().deleteDashboard(sharedDashboardId);
    }

    private void createDashboards(String[] dashboardNames, boolean deleteIfExists, boolean shared) {
        // Deleting existing dashboards if needed
        List<DashboardInfo> dashboards = getRestClient().getTenantDashboards(new PageLink(1000)).getData();
        for (String dashboardName : dashboardNames) {
            for (DashboardInfo existing : dashboards) {
                if (existing.getName().equalsIgnoreCase(dashboardName.replace(".json", ""))) {
                    if (deleteIfExists) {
                        getRestClient().deleteDashboard(existing.getId());
                    } else {
                        log.warn("Duplicating dashboard with name: {}. Existing dashboard id: {}", dashboardName, existing.getId());
                    }
                }
            }
        }

        for (String dashboardName : dashboardNames) {
            Dashboard dashboard = loadDashboard(dashboardName);
            dashboard = getRestClient().saveDashboard(dashboard);
            if (shared) {
                sharedDashboardId = dashboard.getId();
            } else {
                tenantDashboardIds.add(dashboard.getId());
            }
        }
    }

    private Dashboard loadDashboard(String dashboardName) {
        try {
            JsonNode dashboardConfig = mapper.readTree(this.getClass().getClassLoader().getResourceAsStream(dashboardName));
            String dashboardConfigStr = mapper.writeValueAsString(dashboardConfig);
            dashboardConfig = mapper.readTree(dashboardConfigStr);
            return mapper.treeToValue(dashboardConfig, Dashboard.class);
        } catch (Exception e) {
            log.warn("[{}] Failed to load dashboard", dashboardName, e);
            throw new RuntimeException(e);
        }
    }

    private RestClient getRestClient() {
        return restClientService.getRestClient();
    }
}
