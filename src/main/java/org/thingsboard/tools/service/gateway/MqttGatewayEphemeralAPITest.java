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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.tools.service.msg.NodeMsg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ephemeral gateway mode (GATEWAY_BATCH=true AND EPHEMERAL_ENABLED=true): each gateway repeats
 * connect -> publish one telemetry batch -> clean disconnect on a jittered cadence, instead of holding
 * a persistent connection. Exercises connection-establishment / reconnect load (one-way TLS + token).
 */
@Slf4j
@Service
@ConditionalOnExpression("'${device.api}' == 'MQTT' && '${gateway.batch:false}' == 'true' && '${gateway.ephemeral.enabled:false}' == 'true'")
public class MqttGatewayEphemeralAPITest extends MqttGatewayBatchAPITest {

    /** Name-based gateway target: a gateway name + the device names it carries. No live MqttClient. */
    public record GatewayTarget(String gatewayName, List<String> deviceNames) {
    }

    @Value("${gateway.ephemeral.cycleLengthSec:900}")
    protected int cycleLengthSec;
    @Value("${gateway.ephemeral.jitterSec:300}")
    protected int jitterSec;
    @Value("${gateway.ephemeral.firstConnectJitterSec:-1}")
    protected int firstConnectJitterSecConfig;
    @Value("${gateway.ephemeral.maxConcurrentConnects:auto}")
    protected String maxConcurrentConnectsConfig;
    @Value("${gateway.ephemeral.schedulerThreads:2}")
    protected int schedulerThreads;
    @Value("${gateway.ephemeral.gatewayConnect:false}")
    protected boolean gatewayConnect;

    private static final int HEADROOM = 2;

    // --- metrics ---
    protected final AtomicInteger connectAttempts = new AtomicInteger();
    protected final AtomicInteger connectSuccess = new AtomicInteger();
    protected final AtomicInteger connectFailed = new AtomicInteger();
    protected final AtomicInteger publishSuccess = new AtomicInteger();
    protected final AtomicInteger publishFailed = new AtomicInteger();
    protected final AtomicInteger liveConnections = new AtomicInteger();
    protected final AtomicInteger peakConnections = new AtomicInteger();
    protected final AtomicLong cycleWallMillisTotal = new AtomicLong();
    protected final AtomicInteger cyclesCompleted = new AtomicInteger();

    // --- runtime state (initialised in runApiTests) ---
    protected volatile List<GatewayTarget> targets;
    protected Semaphore connectPermits;
    protected ScheduledExecutorService cycleScheduler;
    protected volatile boolean running;
    protected Random scheduleRandom = new Random();
    protected volatile long testStartMillis;

    @PostConstruct
    protected void rejectRpcInEphemeralMode() {
        if (rpcEnabled) {
            throw new IllegalStateException(
                    "GATEWAY_RPC_ENABLED is not supported with ephemeral mode: ephemeral gateways "
                            + "connect→publish→disconnect and cannot hold an RPC subscription. "
                            + "Run RPC on the persistent gateway mode (GATEWAY_BATCH=false or batch without EPHEMERAL_ENABLED).");
        }
    }

    /**
     * Builds gatewayName -> device names from the configured index ranges and the same round-robin
     * assignment mapDevicesToGatewayClientConnections uses, but WITHOUT any connected clients.
     */
    protected List<GatewayTarget> buildTargets() {
        int gwCount = gatewayEndIdx - gatewayStartIdx;
        List<List<String>> byGateway = new ArrayList<>(gwCount);
        for (int g = 0; g < gwCount; g++) {
            byGateway.add(new ArrayList<>());
        }
        for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
            int g = (i - deviceStartIdx) % gwCount;
            byGateway.get(g).add(getToken(false, i));
        }
        List<GatewayTarget> result = new ArrayList<>(gwCount);
        for (int g = 0; g < gwCount; g++) {
            result.add(new GatewayTarget(getToken(true, gatewayStartIdx + g), byGateway.get(g)));
        }
        return result;
    }

    /** Builds one gateway-batch telemetry payload over the given device names (array shape per device). */
    protected byte[] buildBatch(List<String> deviceNames, boolean alarmRequired) throws Exception {
        ObjectNode batch = mapper.createObjectNode();
        boolean alarmUsed = false;
        for (String deviceName : deviceNames) {
            NodeMsg nodeMsg = getNextNodeMessage(deviceName, alarmRequired && !alarmUsed);
            if (nodeMsg.isTriggersAlarm()) {
                alarmUsed = true;
            }
            batch.setAll(nodeMsg.getNode());
        }
        return mapper.writeValueAsBytes(batch);
    }

    @Override
    public void connectGateways() {
        // Ephemeral mode never pre-connects and never schedules the persistent stats reporter
        // (there is no stable connection set). Just prepare the name-based target map.
        this.targets = buildTargets();
        log.info("Ephemeral mode: prepared {} gateway targets, {} devices total",
                targets.size(), deviceEndIdx - deviceStartIdx);
    }

    @Override
    public void runApiTests() throws InterruptedException {
        List<GatewayTarget> all = this.targets != null ? this.targets : buildTargets();
        this.targets = all;
        int gatewayCount = all.size();
        int cap = resolveMaxConcurrentConnects(gatewayCount);
        this.connectPermits = new Semaphore(cap);
        this.scheduleRandom = new Random(EphemeralSchedule.scheduleSeed(seed, instanceIdx));
        this.cycleScheduler = Executors.newScheduledThreadPool(Math.max(1, schedulerThreads));

        double rate = EphemeralSchedule.connectsPerSecond(gatewayCount, cycleLengthSec);
        int firstConnectJitterSec = EphemeralSchedule.firstConnectJitterSec(firstConnectJitterSecConfig, jitterSec);
        long firstSpanMillis = firstConnectJitterSec * 1000L;
        log.info("Ephemeral mode starting: {} gateways, cycle {}s + jitter {}s, firstConnectJitter {}s (first connect over [0,{}s)), ~{} connects/s, maxConcurrentConnects={} ({})",
                gatewayCount, cycleLengthSec, jitterSec, firstConnectJitterSec, firstConnectJitterSec,
                String.format("%.1f", rate), cap,
                ("auto".equalsIgnoreCase(maxConcurrentConnectsConfig) ? "auto" : "override"));

        this.running = true;
        this.testStartMillis = System.currentTimeMillis();
        // First cycle per gateway spread uniformly over [0, firstConnectJitter): a synchronized fleet powers on
        // within that window, then each gateway reconnects on the cycle + jitter cadence. 0 => all connect at t=0.
        for (GatewayTarget target : all) {
            long offset = EphemeralSchedule.firstOffsetMillis(scheduleRandom, firstSpanMillis);
            cycleScheduler.schedule(() -> runCycle(target), offset, TimeUnit.MILLISECONDS);
        }

        ScheduledExecutorService statsLog = Executors.newSingleThreadScheduledExecutor();
        statsLog.scheduleAtFixedRate(this::logStats, 10, 10, TimeUnit.SECONDS);

        Thread.sleep(testDurationInSec * 1000L);

        this.running = false;
        statsLog.shutdownNow();
        cycleScheduler.shutdownNow();
        cycleScheduler.awaitTermination(CONNECT_TIMEOUT + 5L, TimeUnit.SECONDS);
        logStats();
        log.info("Ephemeral mode finished.");
    }

    /** One connect -> publish -> disconnect cycle. Non-blocking: returns after wiring the async listeners. */
    protected void runCycle(GatewayTarget target) {
        if (!running && cycleScheduler != null) {
            return; // stop spawning new cycles after the test window closes
        }
        connectAttempts.incrementAndGet();
        connectPermits.acquireUninterruptibly();
        updatePeak(liveConnections.incrementAndGet());
        final long start = System.currentTimeMillis();
        final MqttClient client = createClient(target.gatewayName());
        final Future<MqttConnectResult> connectFuture = connectAsync(client);
        if (cycleScheduler != null) {
            // watchdog: a hung connect must not occupy a permit forever
            cycleScheduler.schedule(() -> {
                if (!connectFuture.isDone()) {
                    connectFuture.cancel(true);
                }
            }, CONNECT_TIMEOUT, TimeUnit.SECONDS);
        }
        connectFuture.addListener(f -> {
            Object now = f.getNow();
            boolean ok = f.isSuccess() && now instanceof MqttConnectResult && ((MqttConnectResult) now).isSuccess();
            if (ok) {
                connectSuccess.incrementAndGet();
                publishBatch(client, target, start);
            } else {
                connectFailed.incrementAndGet();
                finishCycle(client, target, start);
            }
        });
    }

    private void publishBatch(MqttClient client, GatewayTarget target, long start) {
        try {
            if (gatewayConnect) {
                for (String deviceName : target.deviceNames()) {
                    byte[] connectMsg = ("{\"device\":\"" + deviceName + "\"}").getBytes(StandardCharsets.UTF_8);
                    client.publish("v1/gateway/connect", Unpooled.wrappedBuffer(connectMsg), MqttQoS.AT_MOST_ONCE);
                }
            }
            byte[] payload = buildBatch(target.deviceNames(), false);
            client.publish(getTestTopic(), Unpooled.wrappedBuffer(payload), MqttQoS.AT_MOST_ONCE)
                    .addListener(pf -> {
                        if (pf.isSuccess()) {
                            publishSuccess.incrementAndGet();
                        } else {
                            publishFailed.incrementAndGet();
                        }
                        finishCycle(client, target, start);
                    });
        } catch (Exception e) {
            publishFailed.incrementAndGet();
            finishCycle(client, target, start);
        }
    }

    /** Always runs exactly once per cycle: record wall time, disconnect, release the permit, reschedule. */
    private void finishCycle(MqttClient client, GatewayTarget target, long start) {
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
        cycleWallMillisTotal.addAndGet(System.currentTimeMillis() - start);
        cyclesCompleted.incrementAndGet();
        liveConnections.decrementAndGet();
        connectPermits.release();
        scheduleNext(target);
    }

    /** Schedules this gateway's next cycle. */
    protected void scheduleNext(GatewayTarget target) {
        if (running && cycleScheduler != null) {
            long delay = EphemeralSchedule.nextDelayMillis(scheduleRandom, cycleLengthMillis(), jitterMillis());
            cycleScheduler.schedule(() -> runCycle(target), delay, TimeUnit.MILLISECONDS);
        }
    }

    protected int resolveMaxConcurrentConnects(int gatewayCount) {
        if (maxConcurrentConnectsConfig != null && !"auto".equalsIgnoreCase(maxConcurrentConnectsConfig.trim())) {
            return Math.max(1, Integer.parseInt(maxConcurrentConnectsConfig.trim()));
        }
        return EphemeralSchedule.autoMaxConcurrentConnects(gatewayCount, cycleLengthSec, CONNECT_TIMEOUT, HEADROOM);
    }

    protected long cycleLengthMillis() {
        return cycleLengthSec * 1000L;
    }

    protected long jitterMillis() {
        return jitterSec * 1000L;
    }

    private void updatePeak(int live) {
        peakConnections.accumulateAndGet(live, Math::max);
    }

    protected void logStats() {
        int completed = cyclesCompleted.get();
        long elapsedSec = Math.max(1, (System.currentTimeMillis() - testStartMillis) / 1000);
        double connectsPerSec = (double) connectAttempts.get() / elapsedSec;
        long avgCycleWallMs = completed == 0 ? 0 : cycleWallMillisTotal.get() / completed;
        log.info("Ephemeral stats: attempts={}, connectOk={}, connectFail={}, publishOk={}, publishFail={}, live={}, peak={}, ~{} connects/s, avgCycleWall={}ms",
                connectAttempts.get(), connectSuccess.get(), connectFailed.get(),
                publishSuccess.get(), publishFailed.get(), liveConnections.get(), peakConnections.get(),
                String.format("%.1f", connectsPerSec), avgCycleWallMs);
    }
}
