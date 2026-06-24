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
package org.thingsboard.tools.service.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.gateway.rpc.GatewayRpcReceiver;
import org.thingsboard.tools.service.gateway.rpc.RpcLatencyStats;
import org.thingsboard.tools.service.gateway.rpc.RpcMessageProcessor;
import org.thingsboard.tools.service.gateway.rpc.RpcResponseTemplate;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnExpression("'${device.api}' == 'MQTT' && '${gateway.batch:false}' != 'true'")
public class MqttGatewayAPITest extends BaseMqttAPITest implements GatewayAPITest {

    @Value("${gateway.startIdx}")
    int gatewayStartIdxConfig;
    @Value("${gateway.endIdx}")
    int gatewayEndIdxConfig;
    @Value("${gateway.count}")
    int gatewayCount;

    @Value("${gateway.rpc.enabled:false}")
    boolean rpcEnabled;
    @Value("${gateway.rpc.topic:v1/gateway/rpc}")
    String rpcTopic;
    @Value("${gateway.rpc.respond:true}")
    boolean rpcRespond;
    @Value("${gateway.rpc.responseTemplate:}")
    String rpcResponseTemplate;
    @Value("${gateway.rpc.sendTsPath:data.params.sendTs}")
    String rpcSendTsPath;
    @Value("${gateway.rpc.statsReportIntervalSec:10}")
    int rpcStatsReportIntervalSec;

    @Override
    protected boolean isInboundHandlingEnabled() {
        return rpcEnabled;
    }

    private final RpcLatencyStats rpcLatencyStats = new RpcLatencyStats();
    private GatewayRpcReceiver rpcReceiver;
    private java.util.concurrent.ScheduledFuture<?> rpcStatsReportFuture;

    private List<Device> gateways = Collections.synchronizedList(new ArrayList<>(1024));

    protected int gatewayStartIdx;
    protected int gatewayEndIdx;


    @PostConstruct
    protected void init() {
        super.init();
        if (this.useInstanceIdx) {
            this.gatewayStartIdx = this.gatewayCount * this.instanceIdx;
            this.gatewayEndIdx = this.gatewayStartIdx + this.gatewayCount;
        } else {
            this.gatewayStartIdx = this.gatewayStartIdxConfig;
            this.gatewayEndIdx = this.gatewayEndIdxConfig;
        }
        log.info("Initialized with gatewayStartIdx [{}], gatewayEndIdx [{}]", this.gatewayStartIdx, this.gatewayEndIdx);
    }

    @Override
    public void createDevices() throws Exception {
        createDevices(false);
    }

    @Override
    public void createGateways() throws Exception {
        List<Device> entities = createEntities(gatewayStartIdx, gatewayEndIdx, true,true);
        gateways = Collections.synchronizedList(entities);
    }

    @Override
    public void connectGateways() throws InterruptedException {
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> gatewayNames;
        if (!gateways.isEmpty()) {
            gatewayNames = gateways.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            gatewayNames = new ArrayList<>();
            for (int i = gatewayStartIdx; i < gatewayEndIdx; i++) {
                gatewayNames.add(getToken(true, i));
            }
        }
        for (String gateway : gatewayNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(gateway);
            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, true);
                Thread.sleep(100 + new Random().nextInt(100));
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectDevices(pack, totalConnectedCount, true);
        }
        scheduleGatewayStatsReporting();
        mapDevicesToGatewayClientConnections();
        if (rpcEnabled) {
            attachRpcReceiver();
        }
    }

    private void mapDevicesToGatewayClientConnections() {
        int gatewayCount = mqttClients.size();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int deviceIdx = i - deviceStartIdx;
            int gatewayIdx = deviceIdx % gatewayCount;
            MqttClient gatewayClient = mqttClients.get(gatewayIdx);
            DeviceClient client = new DeviceClient();
            client.setMqttClient(gatewayClient);
            client.setDeviceName(getToken(false, i));
            client.setGatewayName(clientNames.get(gatewayClient));
            deviceClients.add(client);
        }
    }

    @Override
    public void runApiTests() throws InterruptedException {
        super.runApiTests(deviceClients.size());
    }


    @Override
    protected String getWarmUpTopic() {
        return "v1/gateway/connect";
    }

    @Override
    protected byte[] getData(String deviceName) {
        return ("{\"device\":\"" + deviceName + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        runApiTestIteration(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, testDurationLatch, true);
    }

    @Override
    protected String getTestTopic() {
        return telemetryTest ? "v1/gateway/telemetry" : "v1/gateway/attributes";
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] Message was successfully published to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.error("[{}] Error while publishing message to device: {} and gateway: {}", iteration, client.getDeviceName(), client.getGatewayName(),
                future.cause());
    }

    protected void attachRpcReceiver() {
        ObjectMapper mapper = new ObjectMapper();
        RpcResponseTemplate template = rpcRespond ? RpcResponseTemplate.load(rpcResponseTemplate) : null;
        RpcMessageProcessor processor = new RpcMessageProcessor(mapper, rpcSendTsPath, rpcRespond, template, rpcLatencyStats);
        rpcReceiver = new GatewayRpcReceiver(rpcTopic, io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE, processor, rpcLatencyStats);
        rpcReceiver.attach(mqttClients);
        scheduleRpcStatsReporting();
    }

    protected void scheduleRpcStatsReporting() {
        if (rpcStatsReportIntervalSec <= 0) {
            log.info("Gateway RPC stats reporting disabled (gateway.rpc.statsReportIntervalSec <= 0)");
            return;
        }
        // RPC stats always log when RPC is enabled, independent of GATEWAY_STATS_REPORT. Emitted on the
        // log scheduler (separate pool from the test metronome); stored so it can be cancelled if a
        // teardown path is added — today it stops on JVM exit after the test.
        rpcStatsReportFuture = restClientService.getLogScheduler().scheduleAtFixedRate(
                () -> log.info(rpcReceiver.statsSummaryAndReset(rpcStatsReportIntervalSec)),
                rpcStatsReportIntervalSec, rpcStatsReportIntervalSec, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void removeGateways() throws Exception {
        removeEntities(gateways.stream().map(Device::getId).collect(Collectors.toList()), "gateways");
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), "devices");
    }
}
