/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.tools.service.msg.smartMeter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

import java.util.Random;

@Slf4j
@Service(value = "randomAttributesGenerator")
@ConditionalOnProperty(prefix = "test", value = "payloadType", havingValue = "SMART_METER")
public class MeterMeterAttributesGenerator extends BaseMessageGenerator implements MessageGenerator {

    @Override
    public Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm) {
        byte[] payload;
        try {
            ObjectNode data = mapper.createObjectNode();
            ObjectNode values;
            if (isGateway()) {
                values = data.putObject(deviceName);
            } else {
                values = data;
            }
            values.put("pulseCounter", random.nextInt(1000000));
            values.put("leakage", random.nextInt(100) > 1);  // leakage true in 1% cases
            values.put("batteryLevel", random.nextInt(100));
            payload = mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
        return new Msg(payload);
    }
}
