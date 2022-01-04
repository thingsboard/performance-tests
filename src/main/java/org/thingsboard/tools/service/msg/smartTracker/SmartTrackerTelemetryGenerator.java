/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.tools.service.msg.smartTracker;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.Msg;

@Slf4j
@Service(value = "randomTelemetryGenerator")
@ConditionalOnProperty(prefix = "test", value = "payloadType", havingValue = "SMART_TRACKER")
public class SmartTrackerTelemetryGenerator extends BaseSmartTrackerGenerator implements MessageGenerator {

    @Override
    public Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm) {
        byte[] payload;
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
            values.put("latitude", latLngFormat.format(random.nextDouble() * 100));
            values.put("longitude", latLngFormat.format(random.nextDouble() * 100));
            values.put("speed", speedFormat.format(random.nextDouble() * 100));
            values.put("fuel", random.nextInt(100));
            values.put("batteryLevel", random.nextInt(100));
            payload = mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
        return new Msg(payload);
    }
}
