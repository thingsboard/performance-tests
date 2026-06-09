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
package org.thingsboard.tools.service.msg.template;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.msg.BaseMessageGenerator;
import org.thingsboard.tools.service.msg.MessageGenerator;
import org.thingsboard.tools.service.msg.NodeMsg;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * Generic telemetry generator: payload "values" shape is defined entirely by an external
 * JSON template (test.payloadTemplate). No payload semantics live in code.
 */
@Slf4j
@Service(value = "randomTelemetryGenerator")
@ConditionalOnProperty(prefix = "test", value = "payloadType", havingValue = "CUSTOM")
public class TemplateTelemetryGenerator extends BaseMessageGenerator implements MessageGenerator {

    @Value("${test.payloadTemplate:}")
    private String templatePath;

    private PayloadTemplate template;

    @PostConstruct
    void init() throws IOException {
        template = PayloadTemplate.load(templatePath);
        log.info("Loaded payload template from {}", templatePath);
    }

    @Override
    public NodeMsg getNextNodeMessage(String deviceName, boolean shouldTriggerAlarm) {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode tsNode;
        if (isGateway()) {
            tsNode = data.putArray(deviceName).addObject();
        } else {
            tsNode = data;
        }
        tsNode.put("ts", System.currentTimeMillis());
        ObjectNode values = tsNode.putObject("values");
        template.populate(values, random);
        return new NodeMsg(data, false);
    }
}
