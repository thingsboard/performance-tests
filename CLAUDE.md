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
| `TEST_PAYLOAD_TYPE` | `SMART_METER` | Payload type: `DEFAULT`, `SMART_TRACKER`, `SMART_METER`, `INDUSTRIAL_PLC` |
| `TEST_PAYLOAD_DATAPOINTS` | `60` | Datapoints per message (INDUSTRIAL_PLC only) |
| `WARMUP_ENABLED` | `true` | Run warmup phase before test |
| `UPDATE_ROOT_RULE_CHAIN` | `false` | Replace TB root rule chain with a counter rule chain during test |
| `ALARMS_PER_SECOND` | `1` | Alarm messages per second |

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
- `GatewayBaseTestExecutor` → `MqttGatewayAPITest`, `GatewayAPITest`
- `LwM2MClientBaseTestExecutor` → `Lwm2mDeviceAPITest`

### Message Generation
`MessageGenerator` implementations in `service/msg/`:
- `SmartMeterTelemetryGenerator` / `SmartMeterAttributesGenerator`
- `SmartTrackerTelemetryGenerator` / `SmartTrackerAttributesGenerator`
- `IndustrialPLCTelemetryGenerator` / `IndustrialPLCAttributesGenerator`
- `RandomTelemetryGenerator` / `RandomAttributesGenerator`

### Device Naming Convention
Devices are named `DW00000000` (prefix `DW` + zero-padded index), gateways use `GW` prefix.

### Key Services
- `DefaultRestClientService` — manages thread pools (HTTP executor + log scheduler), wraps TB REST client
- `DefaultDashboardManager` — creates dashboards from JSON files in `src/main/resources/`
- `DeviceProfileManagerImpl` — creates TB device profiles from JSON files in `src/main/resources/device/profile/`
- `RuleChainManager` — can swap/revert the TB root rule chain for clean measurement

### LwM2M Support
The `lwm2m/` package contains a full Leshan-based LwM2M client implementation supporting NoSec, PSK, RPK, and X.509 security modes. LwM2M object models are in `src/main/resources/models/`.

### Kubernetes / Multi-instance
Supports sharding across instances via `INSTANCE_IDX` / `USE_INSTANCE_IDX` / `DEVICE_COUNT` to partition the device range among multiple pods.
