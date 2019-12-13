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
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RandomAttributesGenerator extends BaseRandomGenerator implements MessageGenerator {

    @Override
    public Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm) {
        int percent = random.nextInt(100);
        if (percent < 29) {
            return new Msg(getTinyRandomMessage(deviceName, shouldTriggerAlarm), true);
        } else if (percent < 59) {
            return new Msg(getSmallRandomMessage(deviceName));
        } else if (percent < 99) {
            return new Msg(getRandomMessage(deviceName));
        } else {
            return new Msg(getHugeRandomMessage(deviceName));
        }
    }

    private byte[] getTinyRandomMessage(String deviceName, boolean shouldTriggerAlarm) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode values;
            if (isGateway()) {
                values = data.putObject(deviceName);
            } else {
                values = data;
            }
            if (shouldTriggerAlarm) {
                values.put("a1", 100);
            } else {
                values.put("a1", random.nextInt(100));
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
            ObjectNode values;
            if (isGateway()) {
                values = data.putObject(deviceName);
            } else {
                values = data;
            }
            for (int i = 0; i < 20; i++) {
                values.put("a2_" + i, random.nextInt(100));
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
            ObjectNode values;
            if (isGateway()) {
                values = data.putObject(deviceName);
            } else {
                values = data;
            }
            values.put("a3", getValueToRandomMessage(100));
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getHugeRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode values;
            if (isGateway()) {
                values = data.putObject(deviceName);
            } else {
                values = data;
            }
            values.put("a4", getValueToRandomMessage(1000));
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
