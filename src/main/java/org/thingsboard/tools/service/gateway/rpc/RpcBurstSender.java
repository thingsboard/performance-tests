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

    private static final int MAX_FIRE_THREADS = 16;

    private final RestClient restClient;
    private final String restUrl;
    private final List<String> deviceNames;
    private final JsonNode commandTemplate;
    private final String queue;
    private final int timeoutMs;
    private final int chunkSize;
    private final int intervalSec;
    private final int startDelaySec;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledExecutorService scheduler;
    private ExecutorService firePool;
    private ScheduledFuture<?> burstFuture;
    private List<List<String>> chunks;
    private String ruleEngineUrl;
    private final AtomicLong burstsFired = new AtomicLong();
    private final AtomicLong devicesDispatched = new AtomicLong();

    public RpcBurstSender(RestClient restClient, String restUrl, List<String> deviceNames,
                          JsonNode commandTemplate, String queue, int timeoutMs, int chunkSize,
                          int intervalSec, int startDelaySec) {
        this.restClient = restClient;
        this.restUrl = restUrl;
        this.deviceNames = deviceNames;
        this.commandTemplate = commandTemplate;
        this.queue = queue;
        this.timeoutMs = timeoutMs;
        this.chunkSize = chunkSize;
        this.intervalSec = intervalSec;
        this.startDelaySec = startDelaySec;
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

        int fireThreads = Math.min(Math.max(1, chunks.size()), MAX_FIRE_THREADS);
        firePool = Executors.newFixedThreadPool(fireThreads, ThingsBoardThreadFactory.forName("rpc-burst-fire"));
        scheduler = Executors.newSingleThreadScheduledExecutor(ThingsBoardThreadFactory.forName("rpc-burst-sched"));

        long intervalMs = intervalSec * 1000L;
        long now = System.currentTimeMillis();
        long minStart = now + startDelaySec * 1000L;
        long first = nextBoundaryMillis(now, intervalMs, minStart);
        long initialDelay = first - now;
        log.info("RPC burst sender: {} devices in {} chunks of {}, every {}s, first burst in {}ms (url {})",
                deviceNames.size(), chunks.size(), chunkSize, intervalSec, initialDelay, ruleEngineUrl);
        burstFuture = scheduler.scheduleAtFixedRate(this::fireBurst, initialDelay, intervalMs, TimeUnit.MILLISECONDS);
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

    /** Deep-copy the {method, params} template and append the chunk as a "devices" array. */
    static ObjectNode buildBody(ObjectMapper mapper, JsonNode template, List<String> deviceChunk) {
        ObjectNode body = (ObjectNode) template.deepCopy();
        ArrayNode devices = body.putArray("devices");
        for (String name : deviceChunk) {
            devices.add(name);
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
