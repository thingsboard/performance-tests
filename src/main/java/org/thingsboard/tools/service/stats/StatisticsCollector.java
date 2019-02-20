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
package org.thingsboard.tools.service.stats;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StatisticsCollector {

    /**
     * !!! IMPORTANT NOTE !!!
     * Update this value only in sync with root_rule_chain.json file
     * [TbMsgCountNode configuration, 'telemetryPrefix' value]
     */
    private static final String STATS_TELEMETRY_PREFIX = "perf_tests";

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    private long startTs;
    private long endTs;

    public void start() {
        startTs = System.currentTimeMillis();
    }

    public void end() {
        endTs = System.currentTimeMillis();
    }

    public void printResults() {
        RestClient restClient = new RestClient(restUrl);
        restClient.login(username, password);
        TenantId tenantId = getTenantId(restClient);
        List<String> keys = restClient.getRestTemplate().exchange(
                restUrl + "/api/plugins/telemetry/" + EntityType.TENANT.name() + "/" + tenantId + "/keys/timeseries",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<String>>() {
                }).getBody();

        List<String> perfTestsTelemetryKeys = keys.stream().filter(k -> k.startsWith(STATS_TELEMETRY_PREFIX)).collect(Collectors.toList());

        Map<String, List<Map<String, String>>> result =
                restClient.getRestTemplate().exchange(
                        restUrl + "/api/plugins/telemetry/" + EntityType.TENANT.name() + "/" + tenantId +
                                "/values/timeseries?keys=" + String.join(",", perfTestsTelemetryKeys) + "&startTs=" + startTs + "&endTs=" + endTs,
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, List<Map<String, String>>>>() {
                        }).getBody();
        double totalAvg = 0;
        int interval = 1;
        for (String telemetryKey : result.keySet()) {
            TreeMap<Long, Long> telemetryMap = new TreeMap<>();
            for (Map<String, String> entry : result.get(telemetryKey)) {
                long value = Long.parseLong(entry.get("value"));
                long ts = Long.parseLong(entry.get("ts"));
                telemetryMap.put(ts, value);
            }

            long intervalSum = 0;
            long total = 0;
            long count = 0;
            long prevTime = 0;
            long intervalCount = 0;
            for (Map.Entry<Long, Long> entry : telemetryMap.entrySet()) {
                long value = entry.getValue();
                long ts = entry.getKey();
                log.info("============ value [{}] TS [{}] ============", value, ts);
                total += value;
                count++;
                if (prevTime == 0) {
                    prevTime = ts;
                } else {
                    intervalSum += (ts - prevTime);
                    intervalCount++;
                    prevTime = ts;
                }
            }

            if (count > 1) {
                interval = (int) (intervalSum / intervalCount);
                String nodeName = telemetryKey.substring(STATS_TELEMETRY_PREFIX.length() + 1);
                double avg = new BigDecimal(((double) total) / count).setScale(2, RoundingMode.HALF_UP).doubleValue();
                totalAvg += avg;
                log.info("============ Node [{}] AVG is {} per {} millisecond(s) ============", nodeName, avg, interval);
            }
        }
        if (totalAvg > 0) {
            log.info("============ Total AVG is {} per {} millisecond(s) ============", totalAvg, interval);
        }
    }

    private TenantId getTenantId(RestClient restClient) {
        Device device = restClient.createDevice(RandomStringUtils.randomAlphanumeric(15), "default");
        TenantId tenantId = device.getTenantId();
        restClient.deleteDevice(device.getId());
        return tenantId;
    }

}
