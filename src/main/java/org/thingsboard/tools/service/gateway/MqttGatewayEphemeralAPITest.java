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
import org.thingsboard.tools.service.shared.StatsBlock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Per-cycle retry state; finalized exactly once via the AtomicBoolean guard. */
    private static final class CycleAttempt {
        final GatewayTarget target;
        final long cycleStart;
        // Attempts are strictly sequential and hop between the netty event loop and the cycle
        // scheduler; each executor handoff already provides a happens-before edge, but volatile makes
        // the cross-thread visibility of the running retry count self-evident.
        volatile int retryCount; // retries performed so far (0 on the first attempt)
        final AtomicBoolean finalized = new AtomicBoolean();

        CycleAttempt(GatewayTarget target, long cycleStart) {
            this.target = target;
            this.cycleStart = cycleStart;
        }
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
    @Value("${gateway.ephemeral.maxRetries:3}")
    protected int maxRetries;
    @Value("${gateway.ephemeral.retryBackoffMinMs:1000}")
    protected long retryBackoffMinMs;
    @Value("${gateway.ephemeral.retryBackoffMaxMs:5000}")
    protected long retryBackoffMaxMs;
    @Value("${gateway.ephemeral.retryDeadlineMs:0}")
    protected long retryDeadlineMs;
    @Value("${gateway.ephemeral.permitWaitMs:250}")
    protected long permitWaitMs;

    private static final int HEADROOM = 2;

    // --- metrics ---
    protected final EphemeralStats ephemeralStats = new EphemeralStats();

    // --- runtime state (initialised in runApiTests) ---
    protected volatile List<GatewayTarget> targets;
    protected Semaphore connectPermits;
    protected ScheduledExecutorService cycleScheduler;
    protected volatile boolean running;
    protected Random scheduleRandom = new Random();

    @PostConstruct
    protected void rejectRpcInEphemeralMode() {
        if (rpcEnabled) {
            throw new IllegalStateException(
                    "GATEWAY_RPC_ENABLED is not supported with ephemeral mode: ephemeral gateways "
                            + "connect→publish→disconnect and cannot hold an RPC subscription. "
                            + "Run RPC on the persistent gateway mode (GATEWAY_BATCH=false or batch without EPHEMERAL_ENABLED).");
        }
    }

    /** Ephemeral cycle clients are throwaway: connect→publish→disconnect. They must never
     *  auto-reconnect — a server close mid-cycle (e.g. a transport roll) would otherwise race with
     *  finishCycle's disconnect() and open an untracked orphan session that accumulates above
     *  baseline for the whole roll. */
    @Override
    protected boolean autoReconnect() {
        return false;
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
        // First cycle per gateway spread uniformly over [0, firstConnectJitter): a synchronized fleet powers on
        // within that window, then each gateway reconnects on the cycle + jitter cadence. 0 => all connect at t=0.
        for (GatewayTarget target : all) {
            long offset = EphemeralSchedule.firstOffsetMillis(scheduleRandom, firstSpanMillis);
            cycleScheduler.schedule(() -> runCycle(target), offset, TimeUnit.MILLISECONDS);
        }

        statsReporter().register(StatsBlock.EPHEMERAL, ephemeralStats::summaryAndReset);
        statsReporter().start();

        Thread.sleep(testDurationInSec * 1000L);

        this.running = false;
        cycleScheduler.shutdownNow();
        cycleScheduler.awaitTermination(CONNECT_TIMEOUT + 5L, TimeUnit.SECONDS);
        statsReporter().reportOnce();
        statsReporter().stop();
        log.info("Ephemeral mode finished.");
    }

    /** One connect -> publish -> disconnect cycle, with a bounded app-level retry on failure.
     *  Non-blocking: returns after wiring the async listeners for the current attempt. */
    protected void runCycle(GatewayTarget target) {
        if (!running && cycleScheduler != null) {
            return; // stop spawning new cycles after the test window closes
        }
        // Non-blocking acquire: a scheduler thread must NEVER park on the permit. The tasks that
        // release permits (retry attemptOnce, finalizeCycle) run on this same bounded pool, so a
        // blocking acquire here deadlocks the pool under a connect-failure burst — every permit ends
        // up held by a retrying cycle whose release task can no longer get a thread. Instead, if no
        // permit is free, re-queue this cycle and let the thread go free so the release path can run.
        if (!connectPermits.tryAcquire()) {
            rescheduleForPermit(target);
            return;
        }
        attemptOnce(new CycleAttempt(target, System.currentTimeMillis()));
    }

    /** Re-queues a cycle that could not get a connect permit, after a short jittered wait. Guarded by
     *  the window so it stops with the test; never blocks the calling scheduler thread. */
    protected void rescheduleForPermit(GatewayTarget target) {
        if (running && cycleScheduler != null) {
            cycleScheduler.schedule(() -> runCycle(target), permitWaitMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Full-jitter [1, permitWaitMs] wait before re-checking for a free permit, so a fleet that
     *  saturated the permits together does not re-poll in lockstep. */
    protected long permitWaitMillis() {
        return 1L + scheduleRandom.nextInt(Math.max(1, (int) permitWaitMs));
    }

    private void attemptOnce(CycleAttempt ctx) {
        MqttClient client = null;
        Future<MqttConnectResult> connectFuture;
        try {
            client = createClient(ctx.target.gatewayName());
            connectFuture = connectAsync(client);
        } catch (Exception e) {
            // A synchronous setup failure (client build / connect initiation) would otherwise escape
            // into a swallowed scheduler task, leaking the permit and stalling this gateway forever.
            // Route it through the normal failure path so the cycle still retries/finalizes cleanly.
            log.debug("Ephemeral connect setup failed for {}", ctx.target.gatewayName(), e);
            ephemeralStats.onConnectFail();
            onAttemptFailure(client, ctx); // client may be null (guarded in disconnectAndForget)
            return;
        }
        final MqttClient connectedClient = client;
        if (cycleScheduler != null) {
            // per-attempt watchdog: a hung connect must not occupy the permit forever
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
                ephemeralStats.onConnectOk();
                publishBatch(connectedClient, ctx);
            } else {
                ephemeralStats.onConnectFail();
                onAttemptFailure(connectedClient, ctx);
            }
        });
    }

    private void publishBatch(MqttClient client, CycleAttempt ctx) {
        try {
            if (gatewayConnect) {
                for (String deviceName : ctx.target.deviceNames()) {
                    byte[] connectMsg = ("{\"device\":\"" + deviceName + "\"}").getBytes(StandardCharsets.UTF_8);
                    client.publish("v1/gateway/connect", Unpooled.wrappedBuffer(connectMsg), MqttQoS.AT_MOST_ONCE);
                }
            }
            byte[] payload = buildBatch(ctx.target.deviceNames(), false);
            client.publish(getTestTopic(), Unpooled.wrappedBuffer(payload), MqttQoS.AT_MOST_ONCE)
                    .addListener(pf -> {
                        if (pf.isSuccess()) {
                            ephemeralStats.onPublishOk();
                            finalizeCycle(ctx, client, true);
                        } else {
                            ephemeralStats.onPublishFail();
                            onAttemptFailure(client, ctx);
                        }
                    });
        } catch (Exception e) {
            ephemeralStats.onPublishFail();
            onAttemptFailure(client, ctx);
        }
    }

    /** Tear down the failed attempt's client, then either schedule a bounded retry or finalize as lost. */
    private void onAttemptFailure(MqttClient client, CycleAttempt ctx) {
        disconnectAndForget(client);
        boolean deadlineHit = retryDeadlineMs > 0
                && (System.currentTimeMillis() - ctx.cycleStart) >= retryDeadlineMs;
        if (ctx.retryCount >= maxRetries || deadlineHit) {
            finalizeCycle(ctx, null, false);
            return;
        }
        ctx.retryCount++;
        ephemeralStats.onRetryAttempt();
        long backoff = EphemeralRetryBackoff.resolveMillis(ctx.retryCount, retryBackoffMinMs, retryBackoffMaxMs, scheduleRandom);
        if (!scheduleRetry(ctx.target, () -> attemptOnce(ctx), backoff)) {
            finalizeCycle(ctx, null, false); // window closed: release the permit rather than leak it
        }
    }

    /** Runs exactly once per cycle: record outcome + wall time, release the permit, schedule next cycle. */
    private void finalizeCycle(CycleAttempt ctx, MqttClient successClient, boolean success) {
        if (!ctx.finalized.compareAndSet(false, true)) {
            return;
        }
        if (success) {
            disconnectAndForget(successClient);
            if (ctx.retryCount > 0) {
                ephemeralStats.onCycleRecoveredAfterRetry();
            }
        } else {
            ephemeralStats.onCycleFailedAfterRetries();
        }
        ephemeralStats.onCycleComplete(System.currentTimeMillis() - ctx.cycleStart);
        connectPermits.release();
        scheduleNext(ctx.target);
    }

    private void disconnectAndForget(MqttClient client) {
        if (client == null) {
            return; // setup failed before a client existed
        }
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
        // Drop the throwaway cycle client from the shared callback map so it does not grow unbounded.
        connectionCallbacks.remove(client);
    }

    /** Schedules {@code attempt} after {@code backoffMillis} on the cycle scheduler; false if the
     *  window has closed (caller then finalizes so the permit is not leaked). */
    protected boolean scheduleRetry(GatewayTarget target, Runnable attempt, long backoffMillis) {
        if (running && cycleScheduler != null) {
            cycleScheduler.schedule(attempt, backoffMillis, TimeUnit.MILLISECONDS);
            return true;
        }
        return false;
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
}
