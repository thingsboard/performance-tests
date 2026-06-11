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
| `GATEWAY_BATCH` | `false` | Gateway mode: one publish per gateway carrying all its devices (`MESSAGES_PER_SECOND` counts gateway publishes) |
| `GATEWAY_STATS_REPORT` | `TB` | Gateway stats reporter: `TB` (publish), `LOG` (one aggregated log line), `NONE` |
| `GATEWAY_STATS_REPORT_INTERVAL_SEC` | `300` | Interval (s) for the gateway stats reporter |
| `EPHEMERAL_ENABLED` | `false` | With `GATEWAY_BATCH=true`: each gateway runs connect→publish→disconnect cycles (rate = `gatewayCount / EPHEMERAL_CYCLE_SEC`; `MESSAGES_PER_SECOND` ignored) |
| `EPHEMERAL_CYCLE_SEC` / `EPHEMERAL_JITTER_SEC` | `900` / `300` | Per-gateway cadence + one-sided `[0,jitter]` |
| `EPHEMERAL_MAX_CONCURRENT_CONNECTS` | `auto` | Cap on in-flight connects; `auto` = `ceil(rate × connectTimeout × 2)` |
| `EPHEMERAL_GATEWAY_CONNECT` | `false` | Also publish `v1/gateway/connect` per sub-device each cycle |
| `EPHEMERAL_SCHEDULER_THREADS` | `2` | Dedicated timing-pool size for the ephemeral cycle scheduler |

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
