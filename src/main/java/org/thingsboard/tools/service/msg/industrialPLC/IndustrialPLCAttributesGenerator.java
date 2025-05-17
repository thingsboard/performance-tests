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
package org.thingsboard.tools.service.msg.industrialPLC;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Slf4j
@Service(value = "randomAttributesGenerator")
@ConditionalOnProperty(prefix = "test", value = "payloadType", havingValue = "INDUSTRIAL_PLC")
@Validated
public class IndustrialPLCAttributesGenerator extends BaseMessageGenerator implements MessageGenerator {

    @Min(1)
    @Max(999)
    @Value("${test.payloadDatapoints:60}")
    int payloadDatapoints;

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
            for (int i = 0; i < payloadDatapoints; i++) {
                values.put(String.format("line%03d", i), random.nextDouble()*100);
            }
            payload = mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
        return new Msg(payload);
    }
}
