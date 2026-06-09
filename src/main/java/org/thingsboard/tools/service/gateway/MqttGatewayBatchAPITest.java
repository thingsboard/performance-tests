/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
package org.thingsboard.tools.service.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gateway batch mode (GATEWAY_BATCH=true): one MQTT publish per gateway carrying all of its
 * mapped devices, matching a real gateway/Edge App (Gateway API multi-device batch).
 * MESSAGES_PER_SECOND counts gateway publishes. Everything except the per-publish
 * decision is inherited unchanged.
 */
@Slf4j
@Service
@ConditionalOnExpression("'${device.api}' == 'MQTT' && '${gateway.batch:false}' == 'true'")
public class MqttGatewayBatchAPITest extends MqttGatewayAPITest {

    /** logClient is a synthetic publish-target + log context (one per gateway), not a real device. */
    record GatewayTarget(DeviceClient logClient, List<String> deviceNames) {
    }

    private List<GatewayTarget> targets;

    /**
     * Grouped view of the inherited deviceClients, one entry per gateway connection.
     * Built lazily on first use. No locking needed: nextPublishTask (the only caller) runs solely
     * on the single scheduler thread, after connectGateways has fully populated deviceClients.
     */
    private List<GatewayTarget> targets() {
        if (targets == null) {
            Map<MqttClient, GatewayTarget> grouped = new LinkedHashMap<>();
            for (DeviceClient dc : deviceClients) {
                GatewayTarget target = grouped.computeIfAbsent(dc.getMqttClient(), client -> {
                    DeviceClient logClient = new DeviceClient();
                    logClient.setMqttClient(client);
                    logClient.setGatewayName(dc.getGatewayName());
                    return new GatewayTarget(logClient, new ArrayList<>());
                });
                target.deviceNames().add(dc.getDeviceName());
            }
            grouped.values().forEach(t ->
                    t.logClient().setDeviceName("batch[" + t.deviceNames().size() + " devices]"));
            targets = List.copyOf(grouped.values());
            log.info("Gateway batch mode: {} gateways, {} devices total", targets.size(), deviceClients.size());
        }
        return targets;
    }

    @Override
    protected PublishTask nextPublishTask(int iteration, int msgOffsetIdx, boolean alarmRequired, Set<Object> iterationTargets) throws Exception {
        List<GatewayTarget> all = targets();
        int gwCount = all.size();
        GatewayTarget target;
        if (sequentialTest) {
            target = all.get((iteration * testMessagesPerSecond + msgOffsetIdx) % gwCount);
        } else {
            if (iterationTargets.size() >= gwCount) {
                iterationTargets.clear(); // mps > gateway count: reset instead of spinning forever
            }
            do {
                target = all.get(random.nextInt(gwCount));
            } while (!iterationTargets.add(target));
        }
        ObjectNode batch = mapper.createObjectNode();
        int alarms = 0;
        for (String deviceName : target.deviceNames()) {
            // at most one alarm-triggering entry per publish, so the per-second budget
            // (alarmsPerSecond, enforced by the base loop) keeps its meaning
            NodeMsg nodeMsg = getNextNodeMessage(deviceName, alarmRequired && alarms == 0);
            if (nodeMsg.isTriggersAlarm()) {
                alarms++;
            }
            batch.setAll(nodeMsg.getNode());
        }
        return new PublishTask(target.logClient(), mapper.writeValueAsBytes(batch), alarms);
    }
}
