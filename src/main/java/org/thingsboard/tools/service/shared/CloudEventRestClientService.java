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
package org.thingsboard.tools.service.shared;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Getter @Slf4j
@Service
@ConditionalOnExpression("'${device.api:null}'=='events_MQTT'")
public class CloudEventRestClientService extends DefaultRestClientService implements RestClientService {
    protected final ScheduledExecutorService changeNameScheduler = Executors.newScheduledThreadPool(1, ThingsBoardThreadFactory.forName("scheduler"));

    @Override
    public void destroy() {
        super.destroy();
        if (!this.changeNameScheduler.isShutdown()) {
            this.changeNameScheduler.shutdownNow();
        }
    }

}