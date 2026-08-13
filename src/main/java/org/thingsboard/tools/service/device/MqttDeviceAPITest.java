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
package org.thingsboard.tools.service.device;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.tools.service.gateway.EphemeralSchedule;
import org.thingsboard.tools.service.msg.Msg;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.BaseMqttAPITest;
import org.thingsboard.tools.service.shared.StatsBlock;
import org.thingsboard.tools.service.shared.ThroughputStats;
import org.thingsboard.tools.service.shared.onboarding.EntityLifecycle;
import org.thingsboard.tools.service.shared.onboarding.StaggeredOnboardingEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "MQTT")
public class MqttDeviceAPITest extends BaseMqttAPITest implements DeviceAPITest {

    static String dataAsStr = "{\"t1\":73}";
    static byte[] data = dataAsStr.getBytes(StandardCharsets.UTF_8);

    // STAGGERED onboarding (onboard.mode=STAGGERED): PHASED (default) never touches any of these.
    private StaggeredOnboardingEngine onboardingEngine;
    // Precomputed device-index -> name model, built once by prepareStaggeredModel(). Unlike PHASED's
    // mapDevicesToDeviceClientConnections (which derives each device's name from its already-connected
    // MqttClient's username), this is index-based so onboard(idx) can look up its own name before any
    // connection exists.
    // Package-private (not private): MqttDeviceAPITestTest reads/exercises these directly, following the
    // existing broker-free unit-test idiom (test class extends the SUT and touches its own members).
    List<String> staggeredDeviceNames;
    // Per-device telemetry timers started by the STAGGERED path (one per onboarded device); cancelled
    // when the test duration elapses.
    final List<ScheduledFuture<?>> deviceTelemetryTimers = Collections.synchronizedList(new ArrayList<>());

    private boolean staggered() {
        return "STAGGERED".equalsIgnoreCase(onboardMode);
    }

    @Override
    public void createDevices() throws Exception {
        createDevices(true);
    }

    @Override
    public void removeDevices() throws Exception {
        removeEntities(devices.stream().map(IdBased::getId).collect(Collectors.toList()), "devices");
    }

    @Override
    public void warmUpDevices() throws InterruptedException {
        if (staggered()) {
            // STAGGERED: no separate warm-up phase; each device's own periodic telemetry timer (started
            // by EntityLifecycle.onboard(), driven from runApiTests()) sends its own first message.
            return;
        }
        super.warmUpDevices();
    }

    @Override
    public void runApiTests() throws InterruptedException {
        if (!staggered()) {
            super.runApiTests(mqttClients.size());
            return;
        }
        runStaggeredApiTests();
    }

    /**
     * STAGGERED: ramp devices in through the engine (each onboard connects and schedules its own
     * telemetry timer — see {@link #connectAndScheduleDevice(int)}), then hold for the test duration.
     * Direct-device mode has no RPC subscribe step (RPC is a gateway-mode concept) — STAGGERED here is
     * telemetry-only, matching what {@link MqttDeviceAPITest} already supports in PHASED.
     */
    private void runStaggeredApiTests() throws InterruptedException {
        // Register the THROUGHPUT stats source BEFORE starting the reporter (same order + same block
        // AbstractAPITest.runApiTests(int) uses for PHASED) — StatsReporter.start() snapshots
        // sources.isEmpty() once at call time and never reschedules if it was empty then, so without this
        // the reporter would log "no active sources" and stay inert for the whole run (direct-device
        // STAGGERED registers nothing else).
        if (testMessagesPerSecond > 0) {
            statsReporter().register(StatsBlock.THROUGHPUT,
                    new ThroughputStats(totalSuccessPublishedCount, totalFailedPublishedCount)::summaryAndReset);
        }
        statsReporter().start();
        AtomicBoolean rampCompleted = new AtomicBoolean(false);
        onboardingEngine = new StaggeredOnboardingEngine(
                deviceLifecycle(), onboardMaxConcurrent, onboardFirstJitterSec, /*schedulerThreads*/ 2, seed);
        onboardingEngine.start((onboarded, failed) -> {
            rampCompleted.set(true);
            log.info("STAGGERED device ramp complete: {} onboarded, {} failed", onboarded, failed);
        });
        try {
            Thread.sleep(testDurationInSec * 1000L);
        } finally {
            if (!rampCompleted.get()) {
                log.warn("STAGGERED: test.duration ({}s) elapsed before the onboarding ramp completed — "
                                + "not every device may have onboarded or started publishing telemetry. "
                                + "Consider raising DURATION_IN_SECONDS or lowering ONBOARD_MAX_CONCURRENT/ONBOARD_FIRST_JITTER_SEC.",
                        testDurationInSec);
            }
            for (ScheduledFuture<?> timer : deviceTelemetryTimers) {
                timer.cancel(false);
            }
            if (onboardingEngine != null) {
                onboardingEngine.stop();
            }
        }
    }

    private EntityLifecycle deviceLifecycle() {
        return new EntityLifecycle() {
            @Override
            public int entityCount() {
                return deviceEndIdx - deviceStartIdx;
            }

            @Override
            public void onboard(int idx) throws Exception {
                int devIdx = deviceStartIdx + idx;
                // 1) connect this device's client (persistent, autoReconnect via createClient)
                // 2) schedule this device's telemetry timer
                connectAndScheduleDevice(devIdx);
            }
        };
    }

    /**
     * One STAGGERED device's full onboarding step, composed entirely from existing pieces: the same
     * connect sequence {@link #initClientBlocking} performs for PHASED's bulk connect. Synchronous:
     * throws on any failure so the engine counts this device as failed rather than onboarded.
     * <p>Nothing is registered into the shared {@code mqttClients}/{@code deviceClients} collections —
     * which the telemetry scheduler reads from — until the connect above has succeeded; a device that
     * fails to connect is closed (by {@link #initClientBlocking}) and left out of every shared collection
     * entirely.
     */
    private void connectAndScheduleDevice(int devIdx) throws Exception {
        int localIdx = devIdx - deviceStartIdx;
        String deviceName = staggeredDeviceNames.get(localIdx);

        // 1) connect (persistent; createClient() applies autoReconnect() same as every other device client)
        MqttClient client = initClientBlocking(deviceName);

        // Onboarding succeeded: only now commit this device's client into the shared collections and
        // start its telemetry timer.
        mqttClients.add(client);
        clientNames.put(client, deviceName);
        DeviceClient dc = new DeviceClient();
        dc.setMqttClient(client);
        dc.setDeviceName(deviceName);
        deviceClients.add(dc);

        // 2) schedule this device's own telemetry timer, starting immediately (its onboarding time is
        // already spread by the engine's ramp jitter; an extra small per-device startup offset avoids
        // every device's tick landing on the exact same millisecond).
        scheduleDeviceTelemetry(devIdx, client, deviceName);
    }

    /** Blocking connect for one device token, identical in behavior to the private {@code initClient} used
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
            throw new RuntimeException(String.format("STAGGERED: timed out connecting device [%s]", token), ex);
        }
        if (!result.isSuccess()) {
            connectFuture.cancel(true);
            client.disconnect();
            throw new RuntimeException(String.format("STAGGERED: failed to connect device [%s]. Result code: %s", token, result.getReturnCode()));
        }
        return client;
    }

    /** Schedules this device's own periodic telemetry publish (one MQTT publish per device, same message
     *  construction {@link org.thingsboard.tools.service.shared.BaseMqttAPITest#nextPublishTask} uses for
     *  PHASED's per-device publish), independent of every other device's timer. No-op when publishing is
     *  disabled (MESSAGES_PER_SECOND=0).
     *  <p>Period is MPS-derived so STAGGERED's steady-state aggregate matches PHASED's: today's metronome
     *  does {@code testMessagesPerSecond} single-device publishes/sec by sweeping the whole fleet, i.e.
     *  each device publishes once every {@code entityCount / testMessagesPerSecond} seconds — so each
     *  independent per-device timer here fires on that same period, jittered so the first fires aren't
     *  synchronized across devices.
     *  <p>Package-private (not private): exercised directly by MqttDeviceAPITestTest. */
    void scheduleDeviceTelemetry(int devIdx, MqttClient client, String deviceName) {
        if (testMessagesPerSecond <= 0) {
            return;
        }
        DeviceClient logClient = new DeviceClient();
        logClient.setMqttClient(client);
        logClient.setDeviceName(deviceName);
        AtomicInteger tick = new AtomicInteger();
        int entityCount = deviceEndIdx - deviceStartIdx;
        long periodMs = Math.max(1L, (entityCount * 1000L) / testMessagesPerSecond);
        long initialJitterMs = EphemeralSchedule.firstOffsetMillis(new Random(seed + devIdx), periodMs);
        ScheduledFuture<?> timer = restClientService.getScheduler().scheduleAtFixedRate(() -> {
            try {
                Msg message = getNextMessage(deviceName, false);
                int iteration = tick.incrementAndGet();
                client.publish(getTestTopic(), Unpooled.wrappedBuffer(message.getData()), MqttQoS.AT_MOST_ONCE)
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
                log.warn("STAGGERED telemetry publish failed for device [{}]", deviceName, e);
            }
        }, initialJitterMs, periodMs, TimeUnit.MILLISECONDS);
        deviceTelemetryTimers.add(timer);
    }

    /**
     * Fails fast on an unsupported STAGGERED configuration, instead of silently diverging from it.
     * {@link #scheduleDeviceTelemetry} always publishes one plain per-device message and never injects an
     * alarm (needs {@code test.alarms.aps <= 0}). Deliberately narrow: STAGGERED currently supports
     * exactly the no-alarms scenario this mode targets.
     */
    void checkStaggeredSupported() {
        if (alarmsPerSecond > 0) {
            String msg = String.format(
                    "onboard.mode=STAGGERED currently supports NO alarms for direct-device mode; "
                            + "test.alarms.aps=%d (> 0) is not supported yet. Set ALARMS_PER_SECOND=0, or use onboard.mode=PHASED.",
                    alarmsPerSecond);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    @Override
    protected String getWarmUpTopic() {
        return "v1/devices/me/telemetry";
    }

    @Override
    protected byte[] getData(String deviceName) {
        return data;
    }

    @Override
    protected void runApiTestIteration(int iteration, AtomicInteger totalSuccessPublishedCount, AtomicInteger totalFailedPublishedCount, CountDownLatch testDurationLatch) {
        runApiTestIteration(iteration, totalSuccessPublishedCount, totalFailedPublishedCount, testDurationLatch, false);
    }

    @Override
    protected String getTestTopic() {
        return telemetryTest ? "v1/devices/me/telemetry" : "v1/devices/me/attributes";
    }

    @Override
    protected void logSuccessTestMessage(int iteration, DeviceClient client) {
        log.debug("[{}] Message was successfully published to device: {}", iteration, client.getDeviceName());
    }

    @Override
    protected void logFailureTestMessage(int iteration, DeviceClient client, Future<?> future) {
        log.error("[{}] Error while publishing message to device: [{}] {}", iteration, client.getDeviceName(), future.cause().getMessage());
    }

    @Override
    public void connectDevices() throws InterruptedException {
        if (staggered()) {
            // STAGGERED: no bulk connect here. Build the device name model only; the engine (driven from
            // runApiTests()) connects + schedules each device's telemetry timer on its own paced schedule.
            checkStaggeredSupported();
            prepareStaggeredModel();
            return;
        }
        AtomicInteger totalConnectedCount = new AtomicInteger();
        List<String> pack = null;
        List<String> devicesNames;
        if (!devices.isEmpty()) {
            devicesNames = devices.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            devicesNames = new ArrayList<>();
            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
                devicesNames.add(getToken(false, i));
            }
        }
        for (String device : devicesNames) {
            if (pack == null) {
                pack = new ArrayList<>(warmUpPackSize);
            }
            pack.add(device);
            if (pack.size() == warmUpPackSize) {
                connectDevices(pack, totalConnectedCount, false);
                Thread.sleep(1 + random.nextInt(100));
                pack = null;
            }
        }
        if (pack != null && !pack.isEmpty()) {
            connectDevices(pack, totalConnectedCount, false);
        }
        mapDevicesToDeviceClientConnections();
        registerConnectionStats();
    }

    @Override
    public void generationX509() {

    }

    /**
     * STAGGERED-only: same device-name resolution as this method's PHASED body above, but index-based
     * (0-based, local to this instance's [deviceStartIdx, deviceEndIdx)) instead of derived from each
     * already-connected {@link MqttClient}'s username — so the assignment exists before any device has
     * connected, and {@code onboard(idx)} can look up its own name deterministically regardless of
     * connect order/timing.
     */
    // Package-private (not private): exercised directly by MqttDeviceAPITestTest.
    void prepareStaggeredModel() {
        List<String> devicesNames;
        if (!devices.isEmpty()) {
            devicesNames = devices.stream().map(Device::getName).collect(Collectors.toList());
        } else {
            devicesNames = new ArrayList<>();
            for (int i = deviceStartIdx; i < deviceEndIdx; i++) {
                devicesNames.add(getToken(false, i));
            }
        }
        this.staggeredDeviceNames = devicesNames;
        log.info("STAGGERED model prepared: {} devices", devicesNames.size());
    }

    private void mapDevicesToDeviceClientConnections() {
        for (MqttClient mqttClient : mqttClients) {
            DeviceClient client = new DeviceClient();
            client.setMqttClient(mqttClient);
            client.setDeviceName(mqttClient.getClientConfig().getUsername());
            deviceClients.add(client);
        }
        log.info("Sorting device clients...");
        deviceClients.sort(Comparator.comparing(DeviceClient::getDeviceName));
        log.info("Shuffling device clients...");
        Collections.shuffle(deviceClients, random);
        log.info("Mapping devices to device client connections done");
    }
}
