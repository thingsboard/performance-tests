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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.TenantId;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
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

    public void start(){
        startTs = System.currentTimeMillis();
    }

    public void end(){
        endTs = System.currentTimeMillis();
    }

    public void printResults() {
        RestClient restClient = new RestClient(restUrl);
        restClient.login(username, password);
        // hack to get tenant id
        Device device = restClient.createDevice("Dummy asset", "default");
        TenantId tenantId = device.getTenantId();
        restClient.deleteDevice(device.getId());
        List<String> keys = restClient.getRestTemplate().exchange(
                restUrl + "/api/plugins/telemetry/" + EntityType.TENANT.name() + "/" + tenantId + "/keys/timeseries",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<String>>() {}).getBody();

        List<String> perfTestsTelemetryKeys = keys.stream().filter(k -> k.startsWith("perf_tests")).collect(Collectors.toList());

        Map<String, List<Map<String, String>>> result =
                restClient.getRestTemplate().exchange(
                        restUrl + "/api/plugins/telemetry/" + EntityType.TENANT.name() + "/" + tenantId +
                                "/values/timeseries?keys=" + String.join(",", perfTestsTelemetryKeys) + "&startTs=" + startTs + "&endTs=" + endTs,
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, List<Map<String, String>>>>() {}).getBody();
        double totalAvg = 0;
        int intervalInSeconds = 1;
        for (String telemetryKey : result.keySet()) {
            long intervalSum = 0;
            long total = 0;
            long count = 0;
            long prevTime = 0;
            for (Map<String, String> entry : result.get(telemetryKey)) {
                long tmpValue = Long.parseLong(entry.get("value"));
                long ts = Long.parseLong(entry.get("ts"));
                log.info("============ TMP value [{}] TS [{}] ============", tmpValue, ts);
                if (tmpValue > 0) {
                    log.debug("Telemetry value {}" + tmpValue);
                    total += tmpValue;
                    count++;
                }
                long currTime = Long.parseLong(entry.get("ts"));
                if (prevTime == 0) {
                    prevTime = currTime;
                } else {
                    intervalSum += (prevTime - currTime);
                    prevTime = currTime;
                }
            }
            if (count > 0) {
                intervalInSeconds = (int) (intervalSum / 1000 / (count - 1));
                String nodeName = telemetryKey.substring(STATS_TELEMETRY_PREFIX.length() + 1);
                double avg = new BigDecimal(((double) total) / count).setScale(2, RoundingMode.HALF_UP).doubleValue();
                totalAvg += avg;
                log.info("============ Node [{}] AVG is {} per {} second ============", nodeName, avg, intervalInSeconds);
            }
        }
        if (totalAvg > 0) {
            log.info("============ Total AVG is {} per {} second ============", totalAvg, intervalInSeconds);
        }
    }

}
