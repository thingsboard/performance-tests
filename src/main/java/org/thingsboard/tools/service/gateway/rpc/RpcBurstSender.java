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
package org.thingsboard.tools.service.gateway.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.User;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class RpcBurstSender {

    /** RPC dispatch shape within each interval. */
    public enum Mode {
        /** All chunks fired at the interval boundary — a synchronized fleet-wide burst (peak/resilience test). */
        BURST,
        /** Chunks staggered evenly across the interval — a steady, sustained per-device cadence. */
        SPREAD;

        /** Parse GATEWAY_RPC_SENDER_MODE: null/blank -> BURST; case-insensitive; unknown values fail fast. */
        public static Mode fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return BURST;
            }
            return Mode.valueOf(value.trim().toUpperCase());
        }
    }

    /** SPREAD tick period: the interval divided evenly across its chunks, never below 1ms. */
    static long spreadTickMillis(long intervalMs, int numChunks) {
        return Math.max(1L, intervalMs / Math.max(1, numChunks));
    }

    /** SPREAD chunk selection: rotate through the chunk list so each chunk fires once per sweep. */
    static int chunkIndexForTick(long tickNumber, int numChunks) {
        return (int) (tickNumber % numChunks);
    }

    private final RestClient restClient;
    private final String restUrl;
    private final List<String> deviceNames;
    private final JsonNode commandTemplate;
    private final String queue;
    private final int timeoutMs;
    private final int chunkSize;
    private final int intervalSec;
    private final int startDelaySec;
    private final Mode mode;
    private final int maxFireThreads;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledExecutorService scheduler;
    private ExecutorService firePool;
    private ScheduledFuture<?> burstFuture;
    private List<List<String>> chunks;
    private String ruleEngineUrl;
    private long spreadTick;    // SPREAD cursor; advanced only on the single scheduler thread
    private long spreadTickMs;  // SPREAD inter-chunk period, set in start()
    private final AtomicLong burstsFired = new AtomicLong();
    private final AtomicLong devicesDispatched = new AtomicLong();

    public RpcBurstSender(RestClient restClient, String restUrl, List<String> deviceNames,
                          JsonNode commandTemplate, String queue, int timeoutMs, int chunkSize,
                          int intervalSec, int startDelaySec, Mode mode, int maxFireThreads) {
        this.restClient = restClient;
        this.restUrl = restUrl;
        this.deviceNames = deviceNames;
        this.commandTemplate = commandTemplate;
        this.queue = queue;
        this.timeoutMs = timeoutMs;
        this.chunkSize = chunkSize;
        this.intervalSec = intervalSec;
        this.startDelaySec = startDelaySec;
        this.mode = mode;
        this.maxFireThreads = maxFireThreads;
    }

    public void start() {
        if (scheduler != null) {
            throw new IllegalStateException("RpcBurstSender already started");
        }
        if (commandTemplate == null || !commandTemplate.isObject()) {
            throw new IllegalStateException("RPC sender command template must be a JSON object (with method/params)");
        }
        User user = restClient.getUser().orElseThrow(
                () -> new IllegalStateException("Cannot resolve current user for RPC burst sender"));
        String userId = user.getId().getId().toString();
        ruleEngineUrl = restUrl + "/api/rule-engine/USER/" + userId + "/" + queue + "/" + timeoutMs;
        chunks = chunk(deviceNames, chunkSize);

        int fireThreads = Math.min(Math.max(1, chunks.size()), Math.max(1, maxFireThreads));
        firePool = Executors.newFixedThreadPool(fireThreads, ThingsBoardThreadFactory.forName("rpc-burst-fire"));
        log.info("RPC sender fire pool: {} threads (cap {}, chunks {}) — each post blocks a thread until the rule engine replies, so this bounds in-flight submissions",
                fireThreads, maxFireThreads, chunks.size());
        scheduler = Executors.newSingleThreadScheduledExecutor(ThingsBoardThreadFactory.forName("rpc-burst-sched"));

        long intervalMs = intervalSec * 1000L;
        long now = System.currentTimeMillis();
        long minStart = now + startDelaySec * 1000L;
        if (mode == Mode.SPREAD) {
            int numChunks = chunks.size();
            if (numChunks == 0) {
                log.warn("RPC spread sender: no devices — nothing to send");
                return;
            }
            spreadTickMs = spreadTickMillis(intervalMs, numChunks);
            // No boundary alignment: SPREAD deliberately de-synchronizes (one chunk per tick, staggered).
            long initialDelay = Math.max(0L, minStart - now);
            log.info("RPC spread sender: {} devices in {} chunks of {}, one chunk every {}ms (full sweep ~{}s), first chunk in {}ms (url {})",
                    deviceNames.size(), numChunks, chunkSize, spreadTickMs, intervalSec, initialDelay, ruleEngineUrl);
            burstFuture = scheduler.scheduleAtFixedRate(this::fireNextSpreadChunk, initialDelay, spreadTickMs, TimeUnit.MILLISECONDS);
        } else {
            long first = nextBoundaryMillis(now, intervalMs, minStart);
            long initialDelay = first - now;
            log.info("RPC burst sender: {} devices in {} chunks of {}, every {}s, first burst in {}ms (url {})",
                    deviceNames.size(), chunks.size(), chunkSize, intervalSec, initialDelay, ruleEngineUrl);
            burstFuture = scheduler.scheduleAtFixedRate(this::fireBurst, initialDelay, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void fireBurst() {
        recordBurstFired();
        long startedAt = System.currentTimeMillis();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (List<String> deviceChunk : chunks) {
            futures.add(firePool.submit(() -> {
                try {
                    restClient.getRestTemplate().postForEntity(ruleEngineUrl, buildBody(MAPPER, commandTemplate, deviceChunk), String.class);
                    ok.incrementAndGet();
                    recordDispatched(deviceChunk.size()); // count dispatched only after a successful post (D4)
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("RPC burst chunk failed ({} devices): {}", deviceChunk.size(), e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(timeoutMs + 5000L, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("RPC burst chunk did not complete in time", e);
            }
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > intervalSec * 1000L) {
            log.warn("RPC burst took {}ms, exceeding the {}s interval — burst cadence will slip", elapsed, intervalSec);
        }
        log.info("RPC burst: devices={}, chunks ok={}, failed={}, elapsed={}ms",
                deviceNames.size(), ok.get(), failed.get(), elapsed);
    }

    /**
     * SPREAD mode: fire ONE chunk per tick, rotating through the chunk list so each chunk is sent once
     * per sweep (= one interval), staggered rather than all at once. Posts are async (firePool) so a slow
     * REST call never delays the next tick; a full sweep is counted as one "burst" for the stats.
     */
    private void fireNextSpreadChunk() {
        int numChunks = chunks.size();
        List<String> deviceChunk = chunks.get(chunkIndexForTick(spreadTick, numChunks));
        firePool.submit(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                restClient.getRestTemplate().postForEntity(ruleEngineUrl, buildBody(MAPPER, commandTemplate, deviceChunk), String.class);
                recordDispatched(deviceChunk.size()); // count dispatched only after a successful post (D4)
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed > spreadTickMs) {
                    log.warn("RPC spread chunk post took {}ms > {}ms tick — drip falling behind", elapsed, spreadTickMs);
                }
            } catch (Exception e) {
                log.warn("RPC spread chunk failed ({} devices): {}", deviceChunk.size(), e.getMessage());
            }
        });
        spreadTick++;
        if (chunkIndexForTick(spreadTick, numChunks) == 0) { // wrapped: a full sweep of all chunks = one interval
            recordBurstFired();
            log.info("RPC spread: sweep {} dispatched ({} device-RPCs total so far)", burstsFired.get(), devicesDispatched.get());
        }
    }

    void recordBurstFired() {
        burstsFired.incrementAndGet();
    }

    /** Devices whose chunk was successfully posted to the rule engine — excludes failed chunks, so
     *  {@code devicesDispatched} is a true "sent" count if ever used as a baseline (D4). */
    void recordDispatched(int deviceCount) {
        devicesDispatched.addAndGet(deviceCount);
    }

    String dispatchSummary() {
        return String.format("RPC burst sender stopped: %d bursts fired, %d device-RPCs dispatched",
                burstsFired.get(), devicesDispatched.get());
    }

    public void stop() {
        log.info(dispatchSummary());
        if (burstFuture != null) {
            burstFuture.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (firePool != null) {
            firePool.shutdownNow();
        }
    }

    /** Split names into chunks of at most chunkSize; final chunk may be shorter. */
    static List<List<String>> chunk(List<String> names, int chunkSize) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i += chunkSize) {
            out.add(new ArrayList<>(names.subList(i, Math.min(i + chunkSize, names.size()))));
        }
        return out;
    }

    /**
     * Smallest multiple of intervalMs that is >= max(nowMs, minStartMs) — i.e. the next "round" clock
     * time (e.g. the next whole minute for a 60s interval). Each instance rounds its first send up to
     * the next such time, so when several instances run with accurate clocks they all land on the same
     * instant and fire together — regardless of when each one started, and with no coordination.
     */
    static long nextBoundaryMillis(long nowMs, long intervalMs, long minStartMs) {
        long floor = Math.max(nowMs, minStartMs);
        long rem = floor % intervalMs;
        return rem == 0 ? floor : floor + (intervalMs - rem);
    }

    /**
     * Deep-copy the {method, params} template and append the chunk as a "devices" array of
     * {@code {"name": <deviceName>, "rpcId": <uuid>}} objects. The caller-assigned {@code rpcId}
     * (a fresh {@link UUID} per device per call) lets a consuming rule chain adopt it as the
     * persistent RPC's {@code requestUUID}, so the id is caller-owned and stable for that command's
     * lifetime (internal re-delivery reuses the id already carried in the queued message).
     */
    static ObjectNode buildBody(ObjectMapper mapper, JsonNode template, List<String> deviceChunk) {
        ObjectNode body = (ObjectNode) template.deepCopy();
        ArrayNode devices = body.putArray("devices");
        for (String name : deviceChunk) {
            ObjectNode device = devices.addObject();
            device.put("name", name);
            device.put("rpcId", UUID.randomUUID().toString());
        }
        return body;
    }

    /**
     * Load the {method, params} command template: the classpath default {@code rpc/sender-default.json}
     * when pathOrEmpty is blank, otherwise the JSON file at that path.
     */
    public static JsonNode loadCommandTemplate(String pathOrEmpty) {
        try {
            if (pathOrEmpty == null || pathOrEmpty.isBlank()) {
                try (InputStream in = RpcBurstSender.class.getClassLoader().getResourceAsStream("rpc/sender-default.json")) {
                    if (in == null) {
                        throw new IllegalStateException("Default RPC sender template not found on classpath: rpc/sender-default.json");
                    }
                    return MAPPER.readTree(in.readAllBytes());
                }
            }
            return MAPPER.readTree(new File(pathOrEmpty));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load RPC sender command template: " + pathOrEmpty, e);
        }
    }
}
