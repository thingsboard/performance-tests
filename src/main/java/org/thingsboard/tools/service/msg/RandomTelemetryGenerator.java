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
package org.thingsboard.tools.service.msg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RandomTelemetryGenerator extends BaseRandomGenerator implements MessageGenerator {

    @Override
    public Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm) {
//        int percent = random.nextInt(100);
//        if (percent < 29) {
//            return new Msg(getTinyRandomMessage(deviceName, shouldTriggerAlarm), shouldTriggerAlarm);
//        } else if (percent < 59) {
//            return new Msg(getSmallRandomMessage(deviceName));
//        } else if (percent < 99) {
            return new Msg(getRandomMessage(deviceName));
//        } else {
//            return new Msg(getHugeRandomMessage(deviceName));
//        }
    }

    private byte[] getTinyRandomMessage(String deviceName, boolean shouldTriggerAlarm) {
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
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getSmallRandomMessage(String deviceName) {
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
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getRandomMessage(String deviceName) {
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

            values.put("v1", random.nextInt(100));
            values.put("v2", random.nextFloat() * 100);
            values.put("v3", random.nextBoolean());
            values.put("v4", random.nextInt(100));
            values.put("v5", RandomStringUtils.randomAlphabetic(5));

            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getHugeRandomMessage(String deviceName) {
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

            return mapper.writeValueAsBytes(data);
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
