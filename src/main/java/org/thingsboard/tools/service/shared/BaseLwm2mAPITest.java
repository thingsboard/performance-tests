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
package org.thingsboard.tools.service.shared;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Data
public abstract class BaseLwm2mAPITest extends AbstractAPITest {

    private static final int CONNECT_TIMEOUT = 5;
    private EventLoopGroup EVENT_LOOP_GROUP;
    protected static final String deviceConfigNoSec = "credentials/deviceConfigNoSec.json";
    protected static final String deviceConfigKeys = "credentials/deviceConfigKeys.json";

    @Getter
    @Value("${lwm2m.noSec.enabled:}")
    protected boolean lwm2mNoSecEnabled;

    @Getter
    @Value("${lwm2m.psk.enabled:}")
    protected boolean lwm2mPSKEnabled;

    @Getter
    @Value("${lwm2m.psk.identity_sub:}")
    protected String lwm2mPSKIdentitySub;

    @Getter
    @Value("${lwm2m.rpk.enabled:}")
    protected boolean lwm2mRPKEnabled;

    @Getter
    @Value("${lwm2m.x509.enabled:}")
    protected boolean lwm2mX509Enabled;

    @Getter
    protected JsonNode nodeConfig = getConfigNoSec();

    @Getter
    protected JsonNode nodeConfigKeys = getDeviceConfigKeys();

    @PostConstruct
    protected void init() {
        super.init();
        EVENT_LOOP_GROUP = new NioEventLoopGroup();
    }

    @PreDestroy
    public void destroy() {
        super.destroy();
        if (EVENT_LOOP_GROUP != null && !EVENT_LOOP_GROUP.isShutdown()) {
            EVENT_LOOP_GROUP.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }


    protected void runApiTestIteration(int iteration,
                                       AtomicInteger totalSuccessPublishedCount,
                                       AtomicInteger totalFailedPublishedCount,
                                       CountDownLatch testDurationLatch) {
    }

    private JsonNode getConfigNoSec ()  {
        try {
            return loadJsonResource(deviceConfigNoSec, JsonNode.class);
        } catch (IOException e) {
            log.error("Error read ConfigNoSec from [{}] [{}]", deviceConfigNoSec, e.toString());
            return null;
        }
    }

    private JsonNode getDeviceConfigKeys() {
        try {
            return loadJsonResource(deviceConfigKeys, JsonNode.class);
        } catch (IOException e) {
            log.error("Error read ConfigKeys from [{}] [{}]", deviceConfigKeys, e.toString());
            return null;
        }
    }
}
