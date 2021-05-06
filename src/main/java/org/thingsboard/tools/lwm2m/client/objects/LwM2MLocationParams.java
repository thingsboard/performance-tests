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
package org.thingsboard.tools.lwm2m.client.objects;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.tools.lwm2m.client.LwM2MClientContext;

import javax.annotation.PostConstruct;

@Slf4j
@Data
@Component("LwM2MLocationParams")
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MLocationParams {

    private Float latitude;
    private Float longitude;
    private Float scaleFactor;

    @Autowired
    private LwM2MClientContext context;

    @PostConstruct
    public void init() {
        getPos();
        intScaleFactor();
    }

    private void getPos() {
        this.latitude = null;
        this.longitude = null;
        String error = "Position must be a set of two floats separated by a colon, e.g. 50.4501:30.5234";
        try {
            if (context.getLocationPos().isEmpty() || context.getLocationPos().indexOf(':') <= 0) {
                if (context.getLocationPos().length() > 0 ) log.error(error);
            } else {
                int c = context.getLocationPos().indexOf(':');
                this.latitude = Float.valueOf(context.getLocationPos().substring(0, c));
                this.longitude = Float.valueOf(context.getLocationPos().substring(c + 1));
            }
        } catch (NumberFormatException e) {
            log.error(error);
        }
    }

    private void intScaleFactor() {
        this.scaleFactor = 1.0f;
        try {
            if (context.getLocationScaleFactor() > 0) {
                this.scaleFactor = context.getLocationScaleFactor();
            }
        } catch (NumberFormatException e) {
            log.error("Scale factor must be a float, e.g. 1.0 or 0.01");
        }
    }
}
