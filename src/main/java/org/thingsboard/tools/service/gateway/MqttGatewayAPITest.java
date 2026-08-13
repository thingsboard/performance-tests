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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.gateway.rpc.GatewayRpcReceiver;
import org.thingsboard.tools.service.gateway.rpc.RpcBurstSender;
import org.thingsboard.tools.service.gateway.rpc.RpcLatencyStats;
import org.thingsboard.tools.service.gateway.rpc.RpcMessageProcessor;
import org.thingsboard.tools.service.gateway.rpc.RpcResponseTemplate;
import org.thingsboard.tools.service.msg.NodeMsg;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;
import org.thingsboard.tools.service.shared.StatsBlock;
import org.thingsboard.tools.service.shared.ThroughputStats;
import org.thingsboard.tools.service.shared.onboarding.EntityLifecycle;
import org.thingsboard.tools.service.shared.onboarding.StaggeredOnboardingEngine;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // STAGGERED-only support check (see checkStaggeredSupported()): mirrors the same property that
    // selects which bean loads (this class when gateway.batch!=true, MqttGatewayBatchAPITest when it's
    // true), read directly here rather than inferred from the bean type.
    @Value("${gateway.batch:false}")
    boolean gatewayBatchEnabled;

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

    // Ack timing for the device-announce retry and the reply orphan-capture (only active with RPC).
    // NOTE: resubscribe is observe-only and does not use these — it neither retries nor times out.
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
    @Value("${gateway.rpc.sender.mode:BURST}")
    String rpcSenderMode;
    @Value("${rest.url}")
    String restUrl;

    private RpcBurstSender rpcBurstSender;

    // STAGGERED onboarding (onboard.mode=STAGGERED): PHASED (default) never touches any of these.
    private StaggeredOnboardingEngine onboardingEngine;
    // Precomputed gateway-index -> name / sub-device-names model, built once by prepareStaggeredModel().
    // Unlike PHASED's mapDevicesToGatewayClientConnections (which derives the mapping from mqttClients'
    // connect order, established only after ALL gateways connect), this is index-based so onboard(idx)
    // can look up its own assignment before any connection exists.
    // Package-private (not private): MqttGatewayAPITestTest reads/exercises these directly, following
    // the existing broker-free unit-test idiom (test class extends the SUT and touches its own members).
    List<String> staggeredGatewayNames;
    Map<Integer, List<String>> staggeredGatewayDeviceNames;
    // Per-gateway telemetry timers started by the STAGGERED path (one per onboarded gateway); cancelled
    // when the test duration elapses.
    final List<ScheduledFuture<?>> gatewayTelemetryTimers = Collections.synchronizedList(new ArrayList<>());

    private boolean staggered() {
        return "STAGGERED".equalsIgnoreCase(onboardMode);
    }

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
    // ConcurrentHashMap (not HashMap): populated on the main thread before start, then read from reconnect
    // callbacks on netty event-loop threads — safe-publish without relying on incidental happens-before.
    // Package-private (not private): MqttGatewayAPITestTest asserts on this directly (commit-on-success-only).
    final Map<MqttClient, List<String>> gatewayDeviceNames = new ConcurrentHashMap<>();


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
        if (staggered()) {
            // STAGGERED: no bulk connect here. Build the gateway/device name model only; the engine
            // (driven from runApiTests()) connects + announces + subscribes each gateway on its own
            // paced schedule.
            checkStaggeredSupported();
            prepareStaggeredModel();
            return;
        }
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

    // Package-private (not private): exercised directly by MqttGatewayAPITestTest for parity with
    // prepareStaggeredModel().
    void mapDevicesToGatewayClientConnections() {
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

    /**
     * STAGGERED-only: same gateway-name resolution and deviceIdx % gatewayCount assignment as
     * {@link #mapDevicesToGatewayClientConnections()}, but keyed by gateway INDEX (0-based, local to
     * this instance's [gatewayStartIdx, gatewayEndIdx)) instead of by connected {@link MqttClient} —
     * so the assignment exists before any gateway has connected, and {@code onboard(idx)} can look up
     * its own sub-device names deterministically regardless of connect order/timing.
     */
    // Package-private (not private): exercised directly by MqttGatewayAPITestTest.
    void prepareStaggeredModel() {
        List<String> gatewayNames;
        if (!gateways.isEmpty()) {
            gatewayNames = gateways.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            gatewayNames = new ArrayList<>();
            for (int i = gatewayStartIdx; i < gatewayEndIdx; i++) {
                gatewayNames.add(getToken(true, i));
            }
        }
        this.staggeredGatewayNames = gatewayNames;
        int gatewayCount = gatewayNames.size();
        Map<Integer, List<String>> byGatewayIdx = new ConcurrentHashMap<>();
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int deviceIdx = i - deviceStartIdx;
            int gatewayIdx = deviceIdx % gatewayCount;
            byGatewayIdx.computeIfAbsent(gatewayIdx, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(getToken(false, i));
        }
        this.staggeredGatewayDeviceNames = byGatewayIdx;
        log.info("STAGGERED model prepared: {} gateways, {} devices", gatewayCount, deviceEndIdx - deviceStartIdx);
    }

    /**
     * Fails fast on an unsupported STAGGERED configuration, instead of silently diverging from it.
     * {@link #scheduleGatewayTelemetry} always publishes a whole-gateway batch (needs {@code
     * gateway.batch=true}) and never injects an alarm (needs {@code test.alarms.aps <= 0}). Deliberately
     * narrow: STAGGERED currently supports exactly the persistent gateway-batch, no-alarms scenario this
     * mode targets.
     */
    void checkStaggeredSupported() {
        if (!gatewayBatchEnabled) {
            String msg = "onboard.mode=STAGGERED currently supports gateway.batch=true only (with no alarms); "
                    + "gateway.batch=false is not supported yet. Set GATEWAY_BATCH=true, or use onboard.mode=PHASED.";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        if (alarmsPerSecond > 0) {
            String msg = String.format(
                    "onboard.mode=STAGGERED currently supports gateway.batch=true with NO alarms; "
                            + "test.alarms.aps=%d (> 0) is not supported yet. Set ALARMS_PER_SECOND=0, or use onboard.mode=PHASED.",
                    alarmsPerSecond);
            log.error(msg);
            throw new IllegalStateException(msg);
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
    public void warmUpDevices() throws InterruptedException {
        if (staggered()) {
            return; // STAGGERED: announcement happens inside EntityLifecycle.onboard(), driven from runApiTests()
        }
        super.warmUpDevices();
    }

    @Override
    public void runApiTests() throws InterruptedException {
        if (!staggered()) {
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
                    rpcReceiver.logPending();      // name the distinct still-unanswered RPCs for DB EXPIRED correlation
                    String drainLine = String.format("Gateway RPC drain complete [drained %.1fs, quiesced=%b]",
                            result.elapsedMs / 1000.0, result.quiesced);
                    if (result.quiesced) {
                        log.info(drainLine);
                    } else {
                        log.warn(drainLine);
                    }
                    log.info(rpcReceiver.inTotalSummary());   // RPC In  [total]: publish=… (new …, redelivered …)
                    log.info(rpcReceiver.outTotalSummary());  // RPC Out [total]: publish=…, pubAck=…, failed=…, recovered=…, lost=…

                }
            }
            return;
        }
        runStaggeredApiTests();
    }

    /**
     * STAGGERED: ramp gateways in through the engine (each onboard connects + announces + subscribes +
     * schedules its own telemetry timer — see {@link #connectAnnounceSubscribeAndSchedule(int)}), start
     * the RPC sender only once the ramp completes (PHASED starts it up-front, before any connection
     * exists), then hold for the test duration. Reuses the same RPC drain/summary block as the PHASED
     * {@code finally} above.
     */
    private void runStaggeredApiTests() throws InterruptedException {
        // Register stats sources BEFORE starting the reporter — StatsReporter.start() snapshots
        // sources.isEmpty() once at call time; if it's empty then, it logs "no active sources" and never
        // schedules, so a source registered afterward would never print periodically (matches PHASED's
        // order: connectGateways()'s attachRpcReceiver()/initRpcReceiver() always runs, via connectGateways(),
        // before runApiTests() reaches statsReporter().start()).
        if (rpcEnabled) {
            initRpcReceiver();
        }
        // Same THROUGHPUT registration AbstractAPITest.runApiTests(int) does for PHASED — otherwise
        // STAGGERED's per-gateway telemetry timers (which feed the same totalSuccess/totalFailed counters)
        // have no periodic reporter block at all.
        if (testMessagesPerSecond > 0) {
            statsReporter().register(StatsBlock.THROUGHPUT,
                    new ThroughputStats(totalSuccessPublishedCount, totalFailedPublishedCount)::summaryAndReset);
        }
        statsReporter().start();
        AtomicBoolean rampCompleted = new AtomicBoolean(false);
        onboardingEngine = new StaggeredOnboardingEngine(
                gatewayLifecycle(), onboardMaxConcurrent, onboardFirstJitterSec, /*schedulerThreads*/ 2, seed);
        onboardingEngine.start((onboarded, failed) -> {
            rampCompleted.set(true);
            log.info("STAGGERED gateway ramp complete: {} onboarded, {} failed — starting RPC sender", onboarded, failed);
            if (rpcSenderEnabled) {
                startRpcBurstSender();   // existing method, unchanged: full device list
            }
        });
        try {
            Thread.sleep(testDurationInSec * 1000L);
        } finally {
            if (!rampCompleted.get()) {
                log.warn("STAGGERED: test.duration ({}s) elapsed before the onboarding ramp completed — "
                                + "not every gateway may have onboarded, and the RPC sender (if enabled) never started. "
                                + "Consider raising DURATION_IN_SECONDS or lowering ONBOARD_MAX_CONCURRENT/ONBOARD_FIRST_JITTER_SEC.",
                        testDurationInSec);
            }
            for (ScheduledFuture<?> timer : gatewayTelemetryTimers) {
                timer.cancel(false);
            }
            if (onboardingEngine != null) {
                onboardingEngine.stop();
            }
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
                rpcReceiver.finalizeLostReplies();
                rpcReceiver.logPending();
                String drainLine = String.format("Gateway RPC drain complete [drained %.1fs, quiesced=%b]",
                        result.elapsedMs / 1000.0, result.quiesced);
                if (result.quiesced) {
                    log.info(drainLine);
                } else {
                    log.warn(drainLine);
                }
                log.info(rpcReceiver.inTotalSummary());
                log.info(rpcReceiver.outTotalSummary());
            }
        }
    }

    private EntityLifecycle gatewayLifecycle() {
        return new EntityLifecycle() {
            @Override
            public int entityCount() {
                return gatewayEndIdx - gatewayStartIdx;
            }

            @Override
            public void onboard(int idx) throws Exception {
                int gwIdx = gatewayStartIdx + idx;
                // 1) connect this gateway's client (persistent, autoReconnect via createClient)
                // 2) announce its sub-devices through deviceAnnouncer.announce(...) (via the inherited warmUpPublish)
                // 3) subscribe RPC for this client via rpcReceiver (single-client attach) + wire reconnect recovery
                // 4) schedule this gateway's batch-telemetry timer
                connectAnnounceSubscribeAndSchedule(gwIdx);
            }
        };
    }

    /**
     * One STAGGERED gateway's full onboarding step, composed entirely from existing pieces: the same
     * connect sequence {@link #initClientBlocking} performs for PHASED's bulk connect, the same
     * per-device announce path ({@link #warmUpPublish}) PHASED's warm-up uses, and the same
     * subscribe+reconnect wiring {@link #attachClientsToRpc} performs for PHASED's bulk attach — in that
     * order (connect -> announce -> subscribe), per the {@link EntityLifecycle#onboard} contract.
     * Synchronous: throws on any failure so the engine counts this gateway as failed rather than
     * onboarded. The connect step is isolated here; everything after it (the part with a commit-on-success
     * invariant to preserve) lives in {@link #onboardConnectedGateway}.
     */
    private void connectAnnounceSubscribeAndSchedule(int gwIdx) throws Exception {
        int localIdx = gwIdx - gatewayStartIdx;
        String gatewayName = staggeredGatewayNames.get(localIdx);
        List<String> deviceNames = staggeredGatewayDeviceNames.getOrDefault(localIdx, Collections.emptyList());

        // 1) connect (persistent; createClient() applies autoReconnect() same as every other gateway client)
        MqttClient client = initClientBlocking(gatewayName);
        onboardConnectedGateway(gwIdx, client, gatewayName, deviceNames);
    }

    /**
     * Everything after "the client is already connected": announce -> subscribe -> commit-on-success ->
     * schedule telemetry. Split out of {@link #connectAnnounceSubscribeAndSchedule} purely so the
     * commit-on-success invariant is unit-testable with a mocked, already-"connected" {@link MqttClient}
     * (no real broker needed) — the production call site above still invokes this immediately after a
     * real connect, so behavior is unchanged.
     * <p>Nothing is registered into the shared {@code mqttClients}/{@code deviceClients}/
     * {@code gatewayDeviceNames}/{@code clientNames} collections — which {@link #startRpcBurstSender()},
     * the telemetry scheduler, and reconnect recovery all read from wholesale — until the ENTIRE sequence
     * below has succeeded. A gateway that fails partway (e.g. its subscribe throws after announce
     * succeeded) is closed and left out of every shared collection entirely, so a partial onboarding can
     * never leak un-announced/un-subscribed devices into the RPC-outcome measurement.
     */
    void onboardConnectedGateway(int gwIdx, MqttClient client, String gatewayName, List<String> deviceNames) throws Exception {
        try {
            // 2) announce sub-devices through the same reliable (RPC on) / legacy QoS-0 (RPC off) path
            // warm-up uses. No timeout here: an announce under retry can legitimately take longer than
            // CONNECT_TIMEOUT; GatewayDeviceAnnouncer always eventually settles the future (acked or
            // unconfirmed-after-retries).
            for (String deviceName : deviceNames) {
                DeviceClient dc = new DeviceClient();
                dc.setMqttClient(client);
                dc.setDeviceName(deviceName);
                warmUpPublish(dc).get();
            }

            // 3) subscribe RPC for this client alone + wire its reconnect recovery (mirrors
            // attachRpcReceiver's per-client wiring, done here per-gateway instead of once in bulk over
            // mqttClients).
            if (rpcEnabled) {
                attachClientsToRpc(Collections.singletonList(client), 0);
            }
        } catch (Exception e) {
            client.disconnect();
            throw e;
        }

        // Onboarding succeeded end-to-end: only now commit this gateway's client + devices into the
        // shared collections and start its telemetry timer.
        mqttClients.add(client);
        clientNames.put(client, gatewayName);
        gatewayDeviceNames.put(client, Collections.synchronizedList(new ArrayList<>(deviceNames)));
        List<DeviceClient> newDeviceClients = new ArrayList<>(deviceNames.size());
        for (String deviceName : deviceNames) {
            DeviceClient dc = new DeviceClient();
            dc.setMqttClient(client);
            dc.setDeviceName(deviceName);
            dc.setGatewayName(gatewayName);
            newDeviceClients.add(dc);
        }
        deviceClients.addAll(newDeviceClients);

        // 4) schedule this gateway's own batch-telemetry timer, starting immediately (its onboarding time
        // is already spread by the engine's ramp jitter; an extra small per-gateway startup offset avoids
        // every gateway's tick landing on the exact same millisecond).
        scheduleGatewayTelemetry(gwIdx, client, gatewayName, deviceNames);
    }

    /** Blocking connect for one gateway token, identical in behavior to the private {@code initClient} used
     *  by PHASED's {@code connectDevices} — reconstructed here (rather than reused) only because that method
     *  is {@code private} in {@link org.thingsboard.tools.service.shared.BaseMqttAPITest}. */
    private MqttClient initClientBlocking(String token) throws Exception {
        MqttClient client = createClient(token);
        Future<MqttConnectResult> connectFuture = connectAsync(client);
        MqttConnectResult result;
        try {
            result = connectFuture.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("STAGGERED: timed out connecting gateway [%s]", token), ex);
        }
        if (!result.isSuccess()) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("STAGGERED: failed to connect gateway [%s]. Result code: %s", token, result.getReturnCode()));
        }
        return client;
    }

    /** Schedules this gateway's own periodic batch-telemetry publish (one MQTT publish carrying all of its
     *  sub-devices' next messages, same construction as {@link MqttGatewayBatchAPITest#nextPublishTask}),
     *  independent of every other gateway's timer. No-op when publishing is disabled (MESSAGES_PER_SECOND=0)
     *  or this gateway has no sub-devices.
     *  <p>Period is MPS-derived so STAGGERED's steady-state aggregate matches PHASED's: today's metronome
     *  does {@code testMessagesPerSecond} gateway-batch publishes/sec by sweeping the whole fleet, i.e.
     *  each gateway publishes once every {@code entityCount / testMessagesPerSecond} seconds — so each
     *  independent per-gateway timer here fires on that same period, jittered so the first fires aren't
     *  synchronized across gateways.
     *  <p>Package-private (not private): exercised directly by MqttGatewayAPITestTest. */
    void scheduleGatewayTelemetry(int gwIdx, MqttClient client, String gatewayName, List<String> deviceNames) {
        if (testMessagesPerSecond <= 0 || deviceNames.isEmpty()) {
            return;
        }
        DeviceClient logClient = new DeviceClient();
        logClient.setMqttClient(client);
        logClient.setGatewayName(gatewayName);
        logClient.setDeviceName("batch[" + deviceNames.size() + " devices]");
        AtomicInteger tick = new AtomicInteger();
        int entityCount = gatewayEndIdx - gatewayStartIdx;
        long periodMs = Math.max(1L, (entityCount * 1000L) / testMessagesPerSecond);
        long initialJitterMs = EphemeralSchedule.firstOffsetMillis(new Random(seed + gwIdx), periodMs);
        ScheduledFuture<?> timer = restClientService.getScheduler().scheduleAtFixedRate(() -> {
            try {
                ObjectNode batch = mapper.createObjectNode();
                for (String deviceName : deviceNames) {
                    NodeMsg nodeMsg = getNextNodeMessage(deviceName, false);
                    batch.setAll(nodeMsg.getNode());
                }
                byte[] data = mapper.writeValueAsBytes(batch);
                int iteration = tick.incrementAndGet();
                client.publish(getTestTopic(), Unpooled.wrappedBuffer(data), MqttQoS.AT_MOST_ONCE)
                        .addListener(f -> {
                            if (f.isSuccess()) {
                                totalSuccessPublishedCount.incrementAndGet();
                                logSuccessTestMessage(iteration, logClient);
                            } else {
                                totalFailedPublishedCount.incrementAndGet();
                                logFailureTestMessage(iteration, logClient, f);
                            }
                        });
            } catch (Exception e) {
                log.warn("STAGGERED telemetry publish failed for gateway [{}]", gatewayName, e);
            }
        }, initialJitterMs, periodMs, TimeUnit.MILLISECONDS);
        gatewayTelemetryTimers.add(timer);
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
                rpcSenderIntervalSec, rpcSenderStartDelaySec, RpcBurstSender.Mode.fromConfig(rpcSenderMode));
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

    protected void attachRpcReceiver() throws InterruptedException {
        initRpcReceiver();
        attachClientsToRpc(mqttClients, warmUpPackSize);
    }

    /** Builds {@code rpcReceiver}/{@code deviceAnnouncer} and registers their stats blocks. Split out of
     *  {@link #attachRpcReceiver()} (which still does exactly this + the bulk attach below, unchanged)
     *  so STAGGERED can construct these once, up front, before any gateway has connected. */
    private void initRpcReceiver() {
        ObjectMapper mapper = new ObjectMapper();
        RpcResponseTemplate template = rpcRespond ? RpcResponseTemplate.load(rpcResponseTemplate) : null;
        RpcMessageProcessor processor = new RpcMessageProcessor(mapper, rpcSendTsPath, rpcRespond, template);
        AckedRetryConfig ackCfg = new AckedRetryConfig(gatewayAckMaxAttempts, gatewayAckTimeoutMs, gatewayAckBackoffMinMs, gatewayAckBackoffMaxMs);
        Random ackRng = new Random(seed + instanceIdx);
        rpcReceiver = new GatewayRpcReceiver(rpcTopic, MqttQoS.AT_LEAST_ONCE, processor, rpcLatencyStats, rpcResponseDelayMs,
                rpcReplyRetryEnabled, rpcExpiryMs, rpcReplyRetryMaxBuffered, gatewayAckTimeoutMs,
                gatewayAckBackoffMinMs, gatewayAckBackoffMaxMs);
        deviceAnnouncer = new GatewayDeviceAnnouncer(announceStats, ackCfg, ackRng,
                gatewayAnnounceMaxConcurrent, gatewayAnnouncePermitWaitMs);
        statsReporter().register(StatsBlock.GATEWAY_DEVICE_ANNOUNCE, announceStats::summaryAndReset);
        statsReporter().register(StatsBlock.RPC_SUBSCRIPTION, rpcReceiver::subscriptionSummary);
        statsReporter().register(StatsBlock.RPC_IN, rpcReceiver::inSummary);
        statsReporter().register(StatsBlock.RPC_OUT, rpcReceiver::outSummary);
    }

    /** Subscribes the given clients to the RPC topic and wires each one's reconnect recovery. Split out
     *  of {@link #attachRpcReceiver()} (unchanged for PHASED: called once with {@code mqttClients} +
     *  {@code warmUpPackSize}) so STAGGERED can call it per-gateway with a singleton list + packSize=0
     *  (pacing is meaningless for a single client). */
    private void attachClientsToRpc(List<MqttClient> clients, int packSize) throws InterruptedException {
        rpcReceiver.attach(clients, packSize);
        // On reconnect, a gateway loses its RPC subscription (cleanSession) and its server-side
        // sub-device routing; restore both so RPC delivery resumes instead of silently dropping.
        // Also flush any replies buffered while the channel was down so they land within the RPC expiry.
        for (MqttClient client : clients) {
            setReconnectAction(client, () -> {
                rpcReceiver.resubscribe(client);
                reannounceDevices(client);
                rpcReceiver.flushReplies(client);
            });
        }
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
