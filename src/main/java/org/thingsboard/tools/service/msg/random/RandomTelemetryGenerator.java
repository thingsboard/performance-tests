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
package org.thingsboard.tools.service.msg.random;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

@Slf4j
@Service(value = "randomTelemetryGenerator")
@ConditionalOnProperty(prefix = "test", value = "payloadType", havingValue = "RANDOM")
public class RandomTelemetryGenerator extends BaseMessageGenerator implements MessageGenerator {

    @Override
    public NodeMsg getNextNodeMessage(String deviceName, boolean shouldTriggerAlarm) {
        int percent = random.nextInt(100);
        if (percent < 29) {
            return new NodeMsg(getTinyRandomMessage(deviceName, shouldTriggerAlarm), shouldTriggerAlarm);
        } else if (percent < 59) {
            return new NodeMsg(getSmallRandomMessage(deviceName));
        } else if (percent < 99) {
            return new NodeMsg(getRandomMessage(deviceName));
        } else {
            return new NodeMsg(getHugeRandomMessage(deviceName));
        }
    }

    private ObjectNode getTinyRandomMessage(String deviceName, boolean shouldTriggerAlarm) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode tsNode;
            if (isGateway()) {
                ArrayNode array = data.putArray(deviceName);
                tsNode = array.addObject();
            } else {
                tsNode = data;
            }
            tsNode.put("ts", System.currentTimeMillis());
            ObjectNode values = tsNode.putObject("values");
            if (shouldTriggerAlarm) {
                values.put("t1", 100);
            } else {
                values.put("t1", random.nextInt(100));
            }
            return data;
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private ObjectNode getSmallRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode tsNode;
            if (isGateway()) {
                ArrayNode array = data.putArray(deviceName);
                tsNode = array.addObject();
            } else {
                tsNode = data;
            }
            tsNode.put("ts", System.currentTimeMillis());
            ObjectNode values = tsNode.putObject("values");
            for (int i = 0; i < 20; i++) {
                values.put("t2_" + i, random.nextInt(100));
            }
            return data;
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private ObjectNode getRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode tsNode;
            if (isGateway()) {
                ArrayNode array = data.putArray(deviceName);
                tsNode = array.addObject();
            } else {
                tsNode = data;
            }
            tsNode.put("ts", System.currentTimeMillis());
            ObjectNode values = tsNode.putObject("values");

            values.put("t3", getValueToRandomMessage(100));

            return data;
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private ObjectNode getHugeRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode tsNode;
            if (isGateway()) {
                ArrayNode array = data.putArray(deviceName);
                tsNode = array.addObject();
            } else {
                tsNode = data;
            }
            tsNode.put("ts", System.currentTimeMillis());
            ObjectNode values = tsNode.putObject("values");

            values.put("t4", getValueToRandomMessage(1000));

            return data;
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private String getValueToRandomMessage(int n) throws JsonProcessingException {
        ObjectNode values = mapper.createObjectNode();
        for (int i = 0; i < n; i++) {
            values.put("v" + i, random.nextInt(100));
        }
        return mapper.writeValueAsString(values);
    }
}
