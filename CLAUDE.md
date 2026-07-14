# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

ThingsBoard Performance Tests — a Spring Boot application that stress-tests ThingsBoard IoT platform by simulating massive concurrent device message publishing over MQTT, HTTP, and LwM2M protocols.

## Build & Run

**Build (Maven, Java 17 required):**
```bash
mvn clean package -DskipTests
```

**Run locally (after build):**
```bash
java -jar target/tb-ce-performance-tests.jar
```

**Build Docker image:**
```bash
cd docker
docker buildx build --no-cache --pull -t thingsboard/tb-ce-performance-test:latest .
```

**Run via Docker (most common usage):**
```bash
docker run -it --rm --network host --pull always --log-driver none \
  --env REST_URL=http://127.0.0.1:8080 \
  --env MQTT_HOST=127.0.0.1 \
  --env DEVICE_END_IDX=1000 \
  --env MESSAGES_PER_SECOND=50 \
  --env DURATION_IN_SECONDS=300 \
  --env TEST_PAYLOAD_TYPE=SMART_METER \
  thingsboard/tb-ce-performance-test:latest
```

Or with an env file:
```bash
docker run -it --env-file .env --name tb-perf-test thingsboard/tb-ce-performance-test:latest
```

## Configuration

All configuration is driven by environment variables mapped in `src/main/resources/tb-ce-performance-tests.yml`. Key variables:

| Variable | Default | Description |
|---|---|---|
| `REST_URL` | `http://localhost:8080` | ThingsBoard REST API URL |
| `REST_USERNAME` | `tenant@thingsboard.org` | TB login |
| `REST_PASSWORD` | `tenant` | TB password |
| `MQTT_HOST` | `localhost` | MQTT broker host |
| `MQTT_PORT` | `1883` | MQTT broker port (8883 for TLS) |
| `MQTT_SSL_ENABLED` | `false` | Enable MQTT TLS |
| `MQTT_KEEPALIVE_SEC` | `0` | MQTT keep-alive interval (seconds); only a positive value overrides, `0` = client default (60). Lower values send PINGREQ more often (keeps NLB idle timers fresh, surfaces dead sockets faster) |
| `MQTT_RECONNECT_MIN_DELAY_SEC` | `0` | Lower bound (seconds) of each client's reconnect delay after a drop. `min == max` (or `max = 0`) = constant delay for every client; both `0` = client-library default (1s). Whole-second granularity, 1s floor (netty-mqtt limits) |
| `MQTT_RECONNECT_MAX_DELAY_SEC` | `0` | Upper bound (seconds). When `> MIN`, each client draws an independent uniform-random delay in `[MIN, MAX]` — spreads a fleet's reconnects so a bounced transport pod refills instead of staying empty (avoids the instant-reconnect stampede) |
| `DEVICE_API` | `MQTT` | Device protocol: `MQTT`, `HTTP`, or `LWM2M` |
| `TEST_API` | `device` | Test mode: `device`, `gateway`, or `lwm2m` |
| `DEVICE_START_IDX` | `0` | First device index |
| `DEVICE_END_IDX` | `1000` | Last device index |
| `DEVICE_CREATE_ON_START` | `true` | Create devices before test |
| `DEVICE_DELETE_ON_COMPLETE` | `false` | Delete devices after test |
| `MESSAGES_PER_SECOND` | `1000` | Target message throughput |
| `DURATION_IN_SECONDS` | `300` | Test duration |
| `TEST_PAYLOAD_TYPE` | `SMART_METER` | Payload type: `DEFAULT`, `SMART_TRACKER`, `SMART_METER`, `INDUSTRIAL_PLC`, `CUSTOM` |
| `TEST_PAYLOAD_DATAPOINTS` | `60` | Datapoints per message (INDUSTRIAL_PLC only) |
| `TEST_PAYLOAD_TEMPLATE` | _(empty)_ | Filesystem path to a JSON payload template; required when `TEST_PAYLOAD_TYPE=CUSTOM` |
| `WARMUP_ENABLED` | `true` | Run warmup phase before test |
| `UPDATE_ROOT_RULE_CHAIN` | `false` | Replace TB root rule chain with a counter rule chain during test |
| `ALARMS_PER_SECOND` | `1` | Alarm messages per second |
| `DEVICE_NAME_FORMAT` | `DEFAULT` | Device name format: `DEFAULT` (`DW%08d`) or `UUID` (deterministic 36-char); gateways always `GW%08d` |
| `DEVICE_PROFILE` | _(empty)_ | Device-profile name to assign; empty = use `TEST_PAYLOAD_TYPE` |
| `DEVICE_PROFILE_PATH` | _(empty)_ | Filesystem dir of profile JSON files to load instead of the classpath `device/profile/`; lets profiles be mounted/edited |
| `GATEWAY_PROFILE` | _(empty)_ | Device-profile name to assign to gateways; empty = same profile as devices |
| `GATEWAY_NAME_PREFIX` | _(empty)_ | Per-tenant prefix prepended to gateway names/tokens to avoid globally-unique-token collisions across tenants; empty = legacy `GW%08d`. Devices are never prefixed |
| `GATEWAY_OVERWRITE_ACTIVITY_TIME` | `false` | Reconciles `additionalInfo.overwriteActivityTime` on gateway devices (created + existing) to this value; when `true`, the gateway's connection activity keeps its sub-devices Active without their own telemetry. Idempotent — only writes when the value differs. Never touches sub-devices |
| `GATEWAY_BATCH` | `false` | Gateway mode: one publish per gateway carrying all its devices (`MESSAGES_PER_SECOND` counts gateway publishes) |
| `STATS_LOG_ENABLED` | `true` | Master switch for periodic stats logging in every mode (device/gateway/RPC/ephemeral) |
| `STATS_LOG_INTERVAL_SEC` | `10` | Single interval (s) for all stat blocks; `<=0` disables |
| `EPHEMERAL_ENABLED` | `false` | With `GATEWAY_BATCH=true`: each gateway runs connect→publish→disconnect cycles (rate = `gatewayCount / EPHEMERAL_CYCLE_SEC`; `MESSAGES_PER_SECOND` ignored) |
| `EPHEMERAL_CYCLE_SEC` / `EPHEMERAL_JITTER_SEC` | `900` / `300` | Per-gateway cadence + one-sided `[0,jitter]` |
| `EPHEMERAL_FIRST_CONNECT_JITTER_SEC` | _(inherits `EPHEMERAL_JITTER_SEC`)_ | Spreads each gateway's first connect uniformly over `[0, this)` seconds at startup. Unset (`-1`) inherits `EPHEMERAL_JITTER_SEC`. `0` = all gateways connect simultaneously at `t=0`; set to `EPHEMERAL_CYCLE_SEC` to pre-diffuse the fleet for a flat aggregate rate from `t=0` |
| `EPHEMERAL_MAX_CONCURRENT_CONNECTS` | `auto` | Cap on in-flight connects; `auto` = `ceil(rate × connectTimeout × 2)` |
| `EPHEMERAL_GATEWAY_CONNECT` | `false` | Also publish `v1/gateway/connect` per sub-device each cycle |
| `EPHEMERAL_SCHEDULER_THREADS` | `2` | Dedicated timing-pool size for the ephemeral cycle scheduler |
| `EPHEMERAL_MAX_RETRIES` | `3` | With ephemeral mode: bounded app-level retries after a failed cycle (connect reset/refused/timeout or publish failure). Each retry is a fresh throwaway client (library reconnect stays off — no ghost sessions); after the bound the cycle is counted `lost` and falls back to the normal cadence. `0` = pre-feature single-attempt behaviour |
| `EPHEMERAL_RETRY_BACKOFF_MIN_MS` / `EPHEMERAL_RETRY_BACKOFF_MAX_MS` | `1000` / `5000` | Jittered exponential backoff window between retries (full-jitter over `min << attempt`, capped at max), so a fleet that dropped together does not retry in lockstep |
| `EPHEMERAL_RETRY_DEADLINE_MS` | `0` | Optional hard cap (ms) on total time a cycle may spend retrying; `0` = bounded only by `EPHEMERAL_MAX_RETRIES`. Worst-case permit hold ≈ initial connect + `MAX_RETRIES × (connectTimeout + backoffMax)` |
| `EPHEMERAL_PERMIT_WAIT_MS` | `250` | When all connect permits (`EPHEMERAL_MAX_CONCURRENT_CONNECTS`) are in use, a cycle is re-queued after a jittered `[1, this]` ms wait instead of blocking a scheduler thread on the permit. Blocking would deadlock the small `EPHEMERAL_SCHEDULER_THREADS` pool under a connect-failure burst (retrying cycles hold every permit while the tasks that release them can't get a thread) |
| `GATEWAY_RPC_ENABLED` | `false` | Persistent gateways subscribe to `v1/gateway/rpc`, handle inbound server-side RPC for their sub-devices, publish a response (two-way), and measure one-way delivery latency. Use `MESSAGES_PER_SECOND=0` for pure RPC (the metronome idles). Not supported with ephemeral mode |
| `GATEWAY_RPC_TOPIC` | `v1/gateway/rpc` | Gateway RPC subscription + response topic |
| `GATEWAY_RPC_RESPOND` | `true` | Publish a response to close the two-way RPC; set `false` only if the chain uses one-way RPC |
| `GATEWAY_RPC_RESPONSE_DELAY_MS` | `0` | Delay (ms) before publishing the device reply. `0` = reply immediately; `>0` exercises the delayed-response case. Scheduled on the client's netty event loop (no extra threads, inbound handler not blocked) |
| `GATEWAY_RPC_RESPONSE_TEMPLATE` | _(empty)_ | Filesystem path to a response template JSON (placeholders `${now}` and `${<dot.path>}` into the request); empty = built-in neutral `ACCEPTED` template |
| `GATEWAY_RPC_SEND_TS_PATH` | `data.params.sendTs` | Dot-path to the send-timestamp (epoch ms) the rule chain stamps into the RPC payload; the gateway computes latency = receiveTs − sendTs |
| `GATEWAY_RPC_DRAIN_QUIET_SEC` | `5` | After the load window ends, inbound RPC must be idle this long before the tail is considered settled (drain exits early). Only relevant with `GATEWAY_RPC_ENABLED=true` |
| `GATEWAY_RPC_DRAIN_MAX_SEC` | `0` (auto) | Hard cap on the post-window drain phase so it can never hang. `>0` = explicit seconds; `0` = auto (sender on: `GATEWAY_RPC_SENDER_TIMEOUT_MS + GATEWAY_RPC_RESPONSE_DELAY_MS + 5s`; sender off: `30s`), floored to `>= QUIET_SEC` |
| `GATEWAY_RPC_REPLY_RETRY_ENABLED` | `true` | Buffer a gateway RPC reply whose publish failed because the client's channel dropped mid-reply (e.g. a transport roll) and re-publish it when that client reconnects, so replies recoverable within the RPC's server-side expiry are delivered instead of expiring. Reply expiry = `GATEWAY_RPC_EXPIRY_MS`. `false` = legacy behaviour (a failed reply is immediately counted `lost`) |
| `GATEWAY_RPC_REPLY_RETRY_MAX_BUFFERED` | `64` | Per-client cap on replies buffered awaiting reconnect; overflow is dropped and counted `lost`. Steady buffer ≈ sub-devices per gateway (a client receives no new RPCs while its channel is down), so the default is generous headroom; it only bounds worst-case memory |
| `GATEWAY_RPC_EXPIRY_MS` | `120000` | Server-side RPC expiry (ms). Used as the reply-retry TTL and as the deadline the re-announce/resubscribe retry cap is sized to fit under. A reply/announce that lands after this is useless server-side |
| `GATEWAY_RPC_ACK_TIMEOUT_MS` | `5000` | Per-attempt wait for the **device-announce** PUBACK before that attempt is retried (also bounds a publish issued while the channel is momentarily `null`, whose future never completes); and the **reply orphan-capture** deadline. **Not used by subscribe** (observe-only, no timeout) |
| `GATEWAY_RPC_ACK_MAX_ATTEMPTS` | `5` | Bounded retries of announce/resubscribe until broker-confirmed; exhausted → `unconfirmed` (sub-device/subscription at risk of losing RPC routing) |
| `GATEWAY_RPC_ACK_BACKOFF_MIN_MS` / `GATEWAY_RPC_ACK_BACKOFF_MAX_MS` | `1000` / `5000` | Jittered-exponential backoff between announce/resubscribe retries. Sized so `MAX_ATTEMPTS × (ACK_TIMEOUT + BACKOFF_MAX)` stays under `GATEWAY_RPC_EXPIRY_MS` |
| `GATEWAY_RPC_ANNOUNCE_MAX_CONCURRENT` | `1000` | Global in-flight cap on device announces so a reconnect storm cannot self-amplify. Acquired non-blockingly (re-queued on the event loop when saturated — never a blocking acquire) |
| `GATEWAY_RPC_ANNOUNCE_PERMIT_WAIT_MS` | `250` | Jittered `[1,this]` ms re-queue wait when the announce concurrency cap is saturated |
| `GATEWAY_RPC_SENDER_ENABLED` | `false` | In-tool load driver: after warmup, fire boundary-aligned rule-engine RPC bursts for this instance's device range. Requires `GATEWAY_RPC_ENABLED`; use `MESSAGES_PER_SECOND=0` |
| `GATEWAY_RPC_SENDER_TEMPLATE` | _(empty)_ | Filesystem path to a `{method, params}` JSON command body (the sender appends the chunked `devices[]`); empty = built-in neutral default |
| `GATEWAY_RPC_SENDER_INTERVAL_SEC` | `60` | Burst cadence (s). Bursts fire on round clock times (multiples of this, e.g. every whole minute), so separate instances with accurate clocks fire together without coordinating |
| `GATEWAY_RPC_SENDER_START_DELAY_SEC` | `0` | `0` = auto (first burst at the next boundary after warmup); `>0` = extra settling margin |
| `GATEWAY_RPC_SENDER_CHUNK_SIZE` | `500` | Devices per rule-engine REST call (under the TBEL 300 KB result limit) |
| `GATEWAY_RPC_SENDER_QUEUE` | `RpcCalls` | Rule-engine queue name in the call URL |
| `GATEWAY_RPC_SENDER_TIMEOUT_MS` | `10000` | Rule-engine call timeout; **must equal the rule chain's hardcoded `TIMEOUT_MS`** (chain derives `sendTs = expirationTime − timeout`) |

## Architecture

### Entry Points
- `PerformanceTestApplication` — Spring Boot main class, loads `tb-ce-performance-tests.yml`
- `PerformanceTestRunner` — `ApplicationRunner` that calls `TestExecutor.runTest()` then exits the JVM

### Test Executor Hierarchy
`TestExecutor` (interface) → `BaseTestExecutor` (abstract) handles lifecycle:
1. Create device profiles
2. Create dashboards/customers (if configured)
3. `initEntities()` — create devices/gateways
4. Optionally update root rule chain
5. `runApiTests()` — execute the load test at `MESSAGES_PER_SECOND` rate for `DURATION_IN_SECONDS`
6. Cleanup entities and revert rule chain

Concrete executors:
- `DeviceBaseTestExecutor` → `MqttDeviceAPITest`, `HttpDeviceAPITest`, `Lwm2mDeviceAPITest`
- `GatewayBaseTestExecutor` → `MqttGatewayAPITest` → `MqttGatewayBatchAPITest` (`GATEWAY_BATCH=true`) → `MqttGatewayEphemeralAPITest` (`+ EPHEMERAL_ENABLED=true`); also `GatewayAPITest`
- `LwM2MClientBaseTestExecutor` → `Lwm2mDeviceAPITest`

The active gateway bean is selected by mutually-exclusive `@ConditionalOnExpression` on `gateway.batch` × `gateway.ephemeral.enabled`. Batch publishing delegates the per-publish decision to a `nextPublishTask` hook on `BaseMqttAPITest`; ephemeral mode drives its own per-gateway cycle scheduler (timing math in `EphemeralSchedule`) instead of the fixed-rate metronome.

Ephemeral cycle clients never auto-reconnect (the persistent fleet does): every client is created via the shared `BaseMqttAPITest.createClient`, which sets `config.setReconnect(autoReconnect())` — a policy hook that defaults to `true` (persistent gateways/devices re-subscribe on reconnect via `setReconnectAction`) and is overridden to `false` in `MqttGatewayEphemeralAPITest`. Without this, a server-side close mid-cycle (e.g. a `tb-mqtt-transport` roll) races with `finishCycle`'s `disconnect()`: netty-mqtt schedules a reconnect that fires after the disconnect and opens an untracked orphan session, so telemetry connections pile up above baseline for the whole roll. With reconnect off, a dropped cycle is simply counted as a failed publish and the gateway publishes again on its next scheduled cycle (matching the QoS-0 periodic-telemetry model), so connections return to baseline promptly. Consequently `MQTT_RECONNECT_MIN/MAX_DELAY_SEC` has no effect in ephemeral mode.

A cycle that fails mid-flight is not simply dropped: `MqttGatewayEphemeralAPITest` runs a bounded app-level retry (`EPHEMERAL_MAX_RETRIES`, jittered-exponential `EphemeralRetryBackoff`) — each attempt a fresh test-owned client, so recovery stays at the application layer and library reconnect stays off (no orphan sessions). A per-cycle `AtomicBoolean` finalizes exactly once (correct connect-permit accounting), and the `EPHEMERAL` stats block reports `retries` / `recovered` / `lost` (the last being the real missed-telemetry count). With the retry loop the `connectOk/connectFail/publishOk/publishFail` counts become per-attempt while `cycles`/`recovered`/`lost` stay per-cycle. `EPHEMERAL_MAX_RETRIES=0` restores single-attempt behaviour. Crucially, a scheduler thread never *blocks* on the connect permit: `runCycle` does a non-blocking `tryAcquire()` and, if the permits are saturated, re-queues the cycle after a jittered `EPHEMERAL_PERMIT_WAIT_MS` wait. Blocking there would deadlock the small `cycleScheduler` pool under a failure burst — every permit ends up held by a retrying cycle whose next `attemptOnce`/`finalizeCycle` (the only path that releases a permit) is queued on the same pool, while all pool threads are parked in the acquire. `tryAcquire`+reschedule keeps the release path runnable, so cycling self-heals once the burst subsides.

**Unified stats reporting (`StatsReporter`):** one periodic reporter, one interval
(`stats.log.enabled` / `stats.log.intervalSec`, env `STATS_LOG_ENABLED` / `STATS_LOG_INTERVAL_SEC`),
shared by every mode. Each active mode registers only the `StatsBlock`s relevant to it, and the
reporter logs every registered block once per interval, in a fixed order, on the shared log scheduler
(never the test metronome): `CONNECTIONS` for fixed-fleet MQTT gateways/devices (live/target vs.
disconnects/reconnects), `THROUGHPUT` whenever publishing is active (registered by the shared
`AbstractAPITest.runApiTests` metronome, so it also covers `HttpDeviceAPITest`), the three RPC lines
`RPC_SUBSCRIPTION` / `RPC_RECEIVE` / `RPC_PUBLISH` when `GATEWAY_RPC_ENABLED=true`, and `EPHEMERAL` for
churn-mode gateways. A block that throws is skipped for
that tick without affecting the others or the schedule. The old per-gateway `TB` `{"msgCount":0}`
publish is gone — stats reporting is log-only now.

**Gateway RPC receive (`GATEWAY_RPC_ENABLED`):** layered onto the persistent gateway modes (not a
separate executor). After connect, each gateway subscribes to `v1/gateway/rpc` via a reusable
`GatewayRpcReceiver` (package `service/gateway/rpc/`): inbound commands are parsed, one-way delivery
latency (`receiveTs − sendTs`, send-timestamp stamped by the rule chain) is recorded in a
commons-math3 `DescriptiveStatistics` accumulator (`RpcLatencyStats`, reported as mean/p50/p95/p99/max),
and a configurable response (`RpcResponseTemplate`) is published to close the two-way RPC. Ephemeral
mode rejects the flag (it can't hold a subscription). Measurement assumes NTP-synced clocks between
TB and the tool host. With `MESSAGES_PER_SECOND=0` (pure RPC) the publish metronome is
skipped and connections are just held open for the test duration. Clients that subscribe use a
dedicated off-event-loop MQTT handler executor. When the load window ends, the test stops the burst
sender and enters a bounded **drain** phase (`GatewayRpcReceiver.drain`) that holds the receiver open
until inbound RPC has been idle for `GATEWAY_RPC_DRAIN_QUIET_SEC` and no RPC is still
**recoverable-pending** (or a hard cap `GATEWAY_RPC_DRAIN_MAX_SEC` elapses), so the last burst's two-way
RPCs are answered instead of being cut off. Because a given-up (lost) RPC leaves the outstanding set,
drain quiesces promptly instead of burning the cap waiting on a dead RPC. A final `Gateway RPC drain
complete [...] quiesced=<bool>` line reports received-total/answered/lost/pending, and each RPC **given
up as lost** is logged `(device, requestId)` for DB `EXPIRED` correlation.

**Per-unique-RPC accounting + three stat lines.** The server legitimately re-pushes still-pending RPCs
on reconnect, so the same command arrives more than once. `RpcOutstandingTracker` keys inbound by
`(deviceName, requestId)` (atomic `putIfAbsent`): first receipt is tracked + answered, a repeat is
counted `duplicate` and dropped (the reply-retry flush already re-sends it on that reconnect — no
double-answer). `received` stays the raw per-delivery count; `unique = received − duplicate`.
**`pending` is the outstanding-set size** (distinct RPCs received but never confirmed answered) — not
`received − answered`, so redelivery duplicates can't poison it; `drain()` quiescence keys off it too.
Answered keys are evicted past `GATEWAY_RPC_EXPIRY_MS` to bound memory (no redelivery can arrive after
expiry); a definitively-**lost** RPC (past TTL or over the retry cap) is removed from the outstanding
set (so `pending` counts only still-recoverable RPCs and drain can quiesce) and recorded for the drain
lost-report. The stats are split into three lines per
`STATS_LOG_INTERVAL_SEC`: **`RPC Subscription`** (`acked/failed/unconfirmed`), **`RPC Receive`**
(`received`, `duplicate`, `unique`, latency percentiles), **`RPC Publish`**
(`sent/recovered/lost/retryQueued` window + `answered/pending/retryQueued` totals).

**Reply retry (`GATEWAY_RPC_REPLY_RETRY_ENABLED`, default on) — timer-driven.** A reply that is **not
confirmed sent** — either the publish future fails, or it **never completes** (the netty-mqtt orphan:
on channel close `lambda$connect$3` stops the publish's retransmit and clears `pendingPublishes`
without failing its promise, so a QoS-1 publish whose bytes flushed just before the drop is abandoned
forever) — is buffered per-client with the RPC's expiry (`receivedAt + GATEWAY_RPC_EXPIRY_MS`).
`publishReply` arms a `GATEWAY_RPC_ACK_TIMEOUT_MS` timeout to catch the orphan (there is a `TODO` in
code for the proper netty-mqtt `tryFailure` fix, deferred because it needs a TB-dep bump). Crucially the
re-send is **scheduled on the client's event loop after a jittered backoff (`gateway.rpc.ack.backoff*`),
firing during the load window on the live channel** — not waiting for a reconnect — repeating until the
reply confirms or its TTL passes. This matters because an orphan is usually buffered *after* the client
has already reconnected and run its one reconnect flush, so a reconnect-only retry would strand it until
it expired. Reconnect `flushReplies` and the drain loop's `flushAllReplies` remain as **backstops**;
all three paths claim a reply by removing it from the keyed per-client buffer, so exactly one re-sends
it (no double-send). Re-publishing is dup-safe (QoS 1; server keeps the first per request id); on the
confirming PUBACK the RPC is marked answered (idempotent, removed from the outstanding set). A reply
past expiry, over the per-client cap (`GATEWAY_RPC_REPLY_RETRY_MAX_BUFFERED`), or still buffered at
drain end is **terminally lost**: counted `lost` and its key removed from the outstanding set (so it
stops holding `pending`). **Not resolved here:** a reply whose orphaned publish the server actually
received (PUBACK lost) is re-sent as a harmless dup but, being a fresh success, is still counted
answered — the DB remains authoritative for the true `SUCCESSFUL`/`EXPIRED` split.

**Reliable re-announce + resubscribe (RPC only):** a sub-device's server-side RPC subscription is
created as a side effect of the gateway's *announce* (`v1/gateway/connect`, `onDeviceConnect` →
`SubscribeToRPC`), not the `v1/gateway/rpc` topic subscribe (that is only the delivery channel). On a
reconnect the old session and all sub-device registrations are gone (netty-mqtt clears every
subscription on channel close and does **not** auto-restore them), so RPC routing is restored only if
**both** the per-device announce and the resubscribe succeed. Previously both were fire-and-forget
QoS-0/untracked, so a drop mid-reconnect silently lost routing and the RPC `EXPIRED`. Now (only when
`GATEWAY_RPC_ENABLED=true`):

- **Announce** (`v1/gateway/connect`) goes through `AckedRetry` (package `service/gateway/`) on the
  client's netty event loop: QoS 1 (`GatewayDeviceAnnouncer`), success is the **PUBACK** (never a QoS-0
  "flushed to socket"), each attempt guarded by a scheduled `GATEWAY_RPC_ACK_TIMEOUT_MS` timeout (which
  also catches a publish issued while the channel is momentarily `null` — its future never completes),
  retried with jittered-exponential backoff up to `GATEWAY_RPC_ACK_MAX_ATTEMPTS`, then counted
  `unconfirmed`. Retry is safe here because an announce is an idempotent publish. Announces are bounded
  by a non-blocking global in-flight semaphore (`GATEWAY_RPC_ANNOUNCE_MAX_CONCURRENT`, `tryAcquire` +
  event-loop re-queue — never a blocking acquire, per the ephemeral deadlock lesson) so a reconnect
  storm cannot self-amplify. The same announcer serves the initial warm-up (via a `warmUpPublish` seam
  in `BaseMqttAPITest`; device/ephemeral modes keep the default QoS-0 warm-up) and reconnect re-announce.
- **Resubscribe** is **observe-only, no retry loop.** It uses **one stable handler per client** (reused
  across every reconnect — netty-mqtt keys pending handlers by `(handler, once)` record-equality, so a
  fresh handler would register a *second* subscription and double-deliver every RPC). A retry loop is
  intentionally absent: re-issuing `on()` for an already-confirmed topic also creates a duplicate
  subscription, and reliability is already covered by netty-mqtt's own SUBSCRIBE retransmission on a
  live channel plus our per-reconnect resubscribe. We track the SUBACK (`acked` / `failed`); `unconfirmed`
  is a **live gauge** — the count of clients whose current subscription has not been SUBACK-confirmed —
  **not** a timeout counter, so a slow-but-real SUBACK never becomes a false positive (the gauge
  self-clears when the SUBACK lands, whenever that is). `GATEWAY_RPC_ACK_TIMEOUT_MS` is used by the
  device-announce retry and the reply orphan-capture — **not** by subscribe.

Observability: a dedicated `GATEWAY_DEVICE_ANNOUNCE` stats block (`AnnounceStats`, label "Gateway
device announce") reports `acked / failed / retried / unconfirmed` per window (`unconfirmed` must stay
0), and the `RPC Subscription` line reports `acked / failed / unconfirmed`. Caveat: a broker ack
(PUBACK/SUBACK) confirms the transport received the op, not that the RPC was ultimately answered before
server expiry — the authoritative loss signal remains the platform-side `EXPIRED` count.

**Gateway RPC burst sender (`GATEWAY_RPC_SENDER_ENABLED`):** an in-tool load driver layered on the
persistent gateway mode (same instance receives + measures what it triggers). After warmup,
`RpcBurstSender` (package `service/gateway/rpc/`) resolves its own user id and fires
`POST /api/rule-engine/USER/<id>/<queue>/<timeout>` bursts for the instance's device range, chunked at
`CHUNK_SIZE`, repeating every `INTERVAL_SEC`. Bursts fire on **round clock times** (a multiple of
`INTERVAL_SEC`, e.g. every whole minute): each instance just waits for the next such time on its own
clock, so if several instances (one per tenant) run with accurate clocks they all fire at the same
instant with no coordinator. A single instance / local run simply fires on the next round time and
repeats. The command body comes from
`GATEWAY_RPC_SENDER_TEMPLATE` (or a built-in neutral default when empty). It runs on its own dedicated
executors — isolated from the MQTT event loop and RPC handler executor — so it never starves
inbound-RPC processing. The chain derives the send-timestamp from the call timeout, so
`GATEWAY_RPC_SENDER_TIMEOUT_MS` must match the chain's hardcoded `TIMEOUT_MS`.

### Message Generation
`MessageGenerator` implementations in `service/msg/`. Each returns a `NodeMsg` (Jackson `ObjectNode` +
alarm flag); `BaseMessageGenerator` serializes it to wire bytes, so generators are reusable for both
device payloads and gateway batches.
- `SmartMeterTelemetryGenerator` / `SmartMeterAttributesGenerator`
- `SmartTrackerTelemetryGenerator` / `SmartTrackerAttributesGenerator`
- `IndustrialPLCTelemetryGenerator` / `IndustrialPLCAttributesGenerator`
- `RandomTelemetryGenerator` / `RandomAttributesGenerator`
- `template/TemplateTelemetryGenerator` / `TemplateAttributesGenerator` — `TEST_PAYLOAD_TYPE=CUSTOM`; payload shape (static fields + randomized numeric ranges) is read from an external JSON template (`TEST_PAYLOAD_TEMPLATE`), keeping payload semantics out of code. See `src/main/resources/payloads/example.json`.

### Device Naming Convention
Devices are named `DW00000000` (prefix `DW` + zero-padded index), gateways use `GW` prefix. Names are
built by `EntityNames.toDeviceName(...)` / `EntityNames.toGatewayName(...)` (via `getToken`);
`DEVICE_NAME_FORMAT=UUID` switches device names to a deterministic 36-char UUID form (gateways stay
`GW%08d`). `GATEWAY_NAME_PREFIX` prepends a per-tenant prefix to gateway names/tokens only (e.g.
`tenant1-GW00000000`); devices are never prefixed.

### Key Services
- `DefaultRestClientService` — manages thread pools (HTTP executor + log scheduler), wraps TB REST client
- `DefaultDashboardManager` — creates dashboards from JSON files in `src/main/resources/`
- `DeviceProfileManagerImpl` — creates TB device profiles from JSON files in `src/main/resources/device/profile/` (or a mounted filesystem dir via `DEVICE_PROFILE_PATH`)
- `RuleChainManager` — can swap/revert the TB root rule chain for clean measurement

### LwM2M Support
The `lwm2m/` package contains a full Leshan-based LwM2M client implementation supporting NoSec, PSK, RPK, and X.509 security modes. LwM2M object models are in `src/main/resources/models/`.

### Kubernetes / Multi-instance
Partition one large run across N pods; each pod resolves its `instanceIdx` and takes a disjoint slice of the index ranges.
- **Instance index:** `INSTANCE_IDX` directly, or extracted from a source string (e.g. the pod hostname) via `USE_INSTANCE_IDX_REGEX` + `INSTANCE_IDX_REGEX` applied to `INSTANCE_IDX_REGEX_SOURCE`.
- **Sharding (`USE_INSTANCE_IDX=true`):** device range = `[DEVICE_COUNT × instanceIdx, +DEVICE_COUNT)`, gateway range = `[GATEWAY_COUNT × instanceIdx, +GATEWAY_COUNT)`. Otherwise the explicit `*_START_IDX` / `*_END_IDX` apply.
- **Multi-tenant:** give each pod distinct `REST_USERNAME` / `REST_PASSWORD` to drive a different tenant.
- **Gateway ranges must be disjoint across pods:** a gateway's MQTT access token *is* its name, and tokens are globally unique in ThingsBoard (device *names* are only per-tenant), so identical gateway ranges across tenants collide on the token. To reuse the same gateway index range across tenants, give each tenant a distinct `GATEWAY_NAME_PREFIX` (prefixes the name/token, keeping them globally unique). Sub-device ranges may overlap (sub-devices get auto-generated tokens).
- **Ephemeral mode:** the per-gateway cycle RNG is seeded per `instanceIdx` (`EphemeralSchedule.scheduleSeed`), so pods don't fire synchronized connection bursts.
