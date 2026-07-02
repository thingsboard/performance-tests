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
| `MQTT_RECONNECT_DELAY_MS` | `0` | Delay (ms) before the client auto-reconnects after a drop; only a positive value overrides, `0` = client default |
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
| `GATEWAY_STATS_REPORT` | `TB` | Gateway stats reporter: `TB` (publish), `LOG` (one aggregated log line), `NONE` |
| `GATEWAY_STATS_REPORT_INTERVAL_SEC` | `300` | Interval (s) for the gateway stats reporter |
| `EPHEMERAL_ENABLED` | `false` | With `GATEWAY_BATCH=true`: each gateway runs connect→publish→disconnect cycles (rate = `gatewayCount / EPHEMERAL_CYCLE_SEC`; `MESSAGES_PER_SECOND` ignored) |
| `EPHEMERAL_CYCLE_SEC` / `EPHEMERAL_JITTER_SEC` | `900` / `300` | Per-gateway cadence + one-sided `[0,jitter]` |
| `EPHEMERAL_FIRST_CONNECT_JITTER_SEC` | _(inherits `EPHEMERAL_JITTER_SEC`)_ | Spreads each gateway's first connect uniformly over `[0, this)` seconds at startup. Unset (`-1`) inherits `EPHEMERAL_JITTER_SEC`. `0` = all gateways connect simultaneously at `t=0`; set to `EPHEMERAL_CYCLE_SEC` to pre-diffuse the fleet for a flat aggregate rate from `t=0` |
| `EPHEMERAL_MAX_CONCURRENT_CONNECTS` | `auto` | Cap on in-flight connects; `auto` = `ceil(rate × connectTimeout × 2)` |
| `EPHEMERAL_GATEWAY_CONNECT` | `false` | Also publish `v1/gateway/connect` per sub-device each cycle |
| `EPHEMERAL_SCHEDULER_THREADS` | `2` | Dedicated timing-pool size for the ephemeral cycle scheduler |
| `GATEWAY_RPC_ENABLED` | `false` | Persistent gateways subscribe to `v1/gateway/rpc`, handle inbound server-side RPC for their sub-devices, publish a response (two-way), and measure one-way delivery latency. Use `MESSAGES_PER_SECOND=0` for pure RPC (the metronome idles). Not supported with ephemeral mode |
| `GATEWAY_RPC_TOPIC` | `v1/gateway/rpc` | Gateway RPC subscription + response topic |
| `GATEWAY_RPC_RESPOND` | `true` | Publish a response to close the two-way RPC; set `false` only if the chain uses one-way RPC |
| `GATEWAY_RPC_RESPONSE_DELAY_MS` | `0` | Delay (ms) before publishing the device reply. `0` = reply immediately; `>0` exercises the delayed-response case. Scheduled on the client's netty event loop (no extra threads, inbound handler not blocked) |
| `GATEWAY_RPC_RESPONSE_TEMPLATE` | _(empty)_ | Filesystem path to a response template JSON (placeholders `${now}` and `${<dot.path>}` into the request); empty = built-in neutral `ACCEPTED` template |
| `GATEWAY_RPC_SEND_TS_PATH` | `data.params.sendTs` | Dot-path to the send-timestamp (epoch ms) the rule chain stamps into the RPC payload; the gateway computes latency = receiveTs − sendTs |
| `GATEWAY_RPC_STATS_REPORT_INTERVAL_SEC` | `10` | Interval (s) for the RPC latency log line; always logs while `GATEWAY_RPC_ENABLED` (independent of `GATEWAY_STATS_REPORT`); `<=0` disables |
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

**Gateway RPC receive (`GATEWAY_RPC_ENABLED`):** layered onto the persistent gateway modes (not a
separate executor). After connect, each gateway subscribes to `v1/gateway/rpc` via a reusable
`GatewayRpcReceiver` (package `service/gateway/rpc/`): inbound commands are parsed, one-way delivery
latency (`receiveTs − sendTs`, send-timestamp stamped by the rule chain) is recorded in a
commons-math3 `DescriptiveStatistics` accumulator (`RpcLatencyStats`, reported as mean/p50/p95/p99/max),
and a configurable response (`RpcResponseTemplate`) is published to close the two-way RPC. Ephemeral
mode rejects the flag (it can't hold a subscription). Measurement assumes NTP-synced clocks between
TB and the tool host. RPC latency is logged every `GATEWAY_RPC_STATS_REPORT_INTERVAL_SEC` (default
10s) whenever RPC is enabled — independent of `GATEWAY_STATS_REPORT`; `<=0` disables. With
`MESSAGES_PER_SECOND=0` (pure RPC) the publish metronome is skipped and connections are just held open
for the test duration. Clients that subscribe use a dedicated off-event-loop MQTT handler executor.

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
