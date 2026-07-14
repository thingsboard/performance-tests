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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.gateway.rpc.GatewayRpcReceiver;
import org.thingsboard.tools.service.gateway.rpc.RpcBurstSender;
import org.thingsboard.tools.service.gateway.rpc.RpcLatencyStats;
import org.thingsboard.tools.service.gateway.rpc.RpcMessageProcessor;
import org.thingsboard.tools.service.gateway.rpc.RpcResponseTemplate;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;
import org.thingsboard.tools.service.shared.StatsBlock;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @Value("${gateway.rpc.responseDelayMs:0}")
    long rpcResponseDelayMs;
    @Value("${gateway.rpc.responseTemplate:}")
    String rpcResponseTemplate;
    @Value("${gateway.rpc.sendTsPath:data.params.sendTs}")
    String rpcSendTsPath;

    @Value("${gateway.rpc.drain.quietSec:5}")
    int rpcDrainQuietSec;
    @Value("${gateway.rpc.drain.maxSec:0}")
    long rpcDrainMaxSecConfig;

    @Value("${gateway.rpc.replyRetryEnabled:true}")
    boolean rpcReplyRetryEnabled;
    @Value("${gateway.rpc.replyRetryMaxBuffered:64}")
    int rpcReplyRetryMaxBuffered;
    @Value("${gateway.rpc.expiryMs:120000}")
    long rpcExpiryMs;

    // Reliable re-announce / resubscribe (only active when RPC is enabled — it protects RPC routing).
    @Value("${gateway.rpc.ack.timeoutMs:5000}")
    long gatewayAckTimeoutMs;
    @Value("${gateway.rpc.ack.maxAttempts:5}")
    int gatewayAckMaxAttempts;
    @Value("${gateway.rpc.ack.backoffMinMs:1000}")
    long gatewayAckBackoffMinMs;
    @Value("${gateway.rpc.ack.backoffMaxMs:5000}")
    long gatewayAckBackoffMaxMs;
    @Value("${gateway.rpc.announce.maxConcurrent:1000}")
    int gatewayAnnounceMaxConcurrent;
    @Value("${gateway.rpc.announce.permitWaitMs:250}")
    long gatewayAnnouncePermitWaitMs;

    protected final AnnounceStats announceStats = new AnnounceStats();
    protected GatewayDeviceAnnouncer deviceAnnouncer;

    @Value("${gateway.rpc.sender.enabled:false}")
    boolean rpcSenderEnabled;
    @Value("${gateway.rpc.sender.template:}")
    String rpcSenderTemplate;
    @Value("${gateway.rpc.sender.intervalSec:60}")
    int rpcSenderIntervalSec;
    @Value("${gateway.rpc.sender.startDelaySec:0}")
    int rpcSenderStartDelaySec;
    @Value("${gateway.rpc.sender.chunkSize:500}")
    int rpcSenderChunkSize;
    @Value("${gateway.rpc.sender.queue:RpcCalls}")
    String rpcSenderQueue;
    @Value("${gateway.rpc.sender.timeoutMs:10000}")
    int rpcSenderTimeoutMs;
    @Value("${rest.url}")
    String restUrl;

    private RpcBurstSender rpcBurstSender;

    @Override
    protected boolean isInboundHandlingEnabled() {
        return rpcEnabled;
    }

    private final RpcLatencyStats rpcLatencyStats = new RpcLatencyStats();
    private GatewayRpcReceiver rpcReceiver;

    private List<Device> gateways = Collections.synchronizedList(new ArrayList<>(1024));

    protected int gatewayStartIdx;
    protected int gatewayEndIdx;

    // Gateway client -> its sub-device names, for re-announcing sub-devices on reconnect.
    private final Map<MqttClient, List<String>> gatewayDeviceNames = new HashMap<>();


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
        // Fixed persistent fleet: register the Connections gauge (live=<n>/<target>).
        // Ephemeral overrides connectGateways and does not call this, so churn mode omits it.
        registerConnectionStats();
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
            gatewayDeviceNames.computeIfAbsent(gatewayClient, k -> new ArrayList<>())
                    .add(client.getDeviceName());
        }
    }

    /** Re-announce a reconnected gateway's sub-devices so the server re-routes their RPC through it.
     *  Reuses the gateway connect topic and payload used at warm-up. */
    private void reannounceDevices(MqttClient gatewayClient) {
        List<String> deviceNames = gatewayDeviceNames.get(gatewayClient);
        if (deviceNames == null) {
            return;
        }
        for (String deviceName : deviceNames) {
            if (deviceAnnouncer != null) {
                // QoS-1, PUBACK-confirmed, retried until the sub-device's RPC routing is re-established.
                deviceAnnouncer.announce(gatewayClient, getData(deviceName));
            } else {
                gatewayClient.publish(getWarmUpTopic(), Unpooled.wrappedBuffer(getData(deviceName)),
                        MqttQoS.AT_MOST_ONCE);
            }
        }
    }

    @Override
    protected Future<Void> warmUpPublish(DeviceClient deviceClient) {
        if (deviceAnnouncer != null) {
            // Initial sub-device announce also goes through the reliable path when RPC is enabled.
            return deviceAnnouncer.announce(deviceClient.getMqttClient(), getData(deviceClient.getDeviceName()));
        }
        return super.warmUpPublish(deviceClient); // RPC off: legacy QoS-0 warm-up
    }

    @Override
    public void runApiTests() throws InterruptedException {
        if (rpcSenderEnabled) {
            startRpcBurstSender();
        }
        try {
            super.runApiTests(deviceClients.size());
        } finally {
            // Stop firing new bursts BEFORE draining so the tail can settle without fresh inbound.
            if (rpcBurstSender != null) {
                rpcBurstSender.stop();
            }
            if (rpcEnabled && rpcReceiver != null) {
                long quietMs = rpcDrainQuietSec * 1000L;
                long maxMs = GatewayRpcReceiver.resolveDrainMaxMs(
                        rpcDrainMaxSecConfig, rpcSenderEnabled, rpcSenderTimeoutMs, rpcResponseDelayMs, rpcDrainQuietSec);
                log.info("Gateway RPC drain: waiting for in-flight RPCs to settle (quietSec={}, maxSec={})...",
                        rpcDrainQuietSec, maxMs / 1000);
                GatewayRpcReceiver.DrainResult result = rpcReceiver.drain(quietMs, maxMs, rpcRespond);
                rpcReceiver.finalizeLostReplies(); // replies still buffered (client never reconnected) are lost
                String summary = rpcReceiver.drainSummary(result.elapsedMs, result.quiesced);
                if (result.quiesced) {
                    log.info(summary);
                } else {
                    log.warn(summary);
                }
            }
        }
    }

    private void startRpcBurstSender() {
        if (!rpcEnabled) {
            throw new IllegalStateException("GATEWAY_RPC_SENDER_ENABLED requires GATEWAY_RPC_ENABLED=true: the test instance must receive and measure the RPCs it triggers");
        }
        if (testMessagesPerSecond > 0) {
            log.warn("GATEWAY_RPC_SENDER_ENABLED with MESSAGES_PER_SECOND={} (>0): telemetry publishing runs alongside the RPC bursts and will contaminate the command-distribution measurement; set MESSAGES_PER_SECOND=0 for a clean RPC test", testMessagesPerSecond);
        }
        JsonNode template = RpcBurstSender.loadCommandTemplate(rpcSenderTemplate);
        List<String> deviceNames = deviceClients.stream()
                .map(DeviceClient::getDeviceName)
                .collect(Collectors.toList());
        rpcBurstSender = new RpcBurstSender(
                restClientService.getRestClient(), restUrl, deviceNames, template,
                rpcSenderQueue, rpcSenderTimeoutMs, rpcSenderChunkSize,
                rpcSenderIntervalSec, rpcSenderStartDelaySec);
        rpcBurstSender.start();
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
        AckedRetryConfig ackCfg = new AckedRetryConfig(gatewayAckMaxAttempts, gatewayAckTimeoutMs, gatewayAckBackoffMinMs, gatewayAckBackoffMaxMs);
        Random ackRng = new Random(seed + instanceIdx);
        rpcReceiver = new GatewayRpcReceiver(rpcTopic, MqttQoS.AT_LEAST_ONCE, processor, rpcLatencyStats, rpcResponseDelayMs,
                rpcReplyRetryEnabled, rpcExpiryMs, rpcReplyRetryMaxBuffered, ackCfg, ackRng);
        deviceAnnouncer = new GatewayDeviceAnnouncer(announceStats, ackCfg, ackRng,
                gatewayAnnounceMaxConcurrent, gatewayAnnouncePermitWaitMs);
        statsReporter().register(StatsBlock.GATEWAY_DEVICE_ANNOUNCE, announceStats::summaryAndReset);
        rpcReceiver.attach(mqttClients);
        // On reconnect, a gateway loses its RPC subscription (cleanSession) and its server-side
        // sub-device routing; restore both so RPC delivery resumes instead of silently dropping.
        // Also flush any replies buffered while the channel was down so they land within the RPC expiry.
        for (MqttClient client : mqttClients) {
            setReconnectAction(client, () -> {
                rpcReceiver.resubscribe(client);
                reannounceDevices(client);
                rpcReceiver.flushReplies(client);
            });
        }
        statsReporter().register(StatsBlock.RPC, rpcReceiver::statsSummaryAndReset);
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
