# STAGGERED onboarding — smoke-test runbook

This is a manual verification procedure for `ONBOARD_MODE=STAGGERED` against a real MQTT broker /
ThingsBoard instance. It has **not been executed** by this task — no live broker was available in the
environment that produced this document. Run it yourself against a local/dev ThingsBoard instance before
relying on STAGGERED at scale. All log lines quoted below are copied verbatim from the current source
(`StaggeredOnboardingEngine`, `MqttGatewayAPITest`, `MqttDeviceAPITest`, `RpcBurstSender`) — grep the code
if a line doesn't show up; it may mean the guard in front of it (see "Known gaps" below) suppressed it.

## 1. Environment (small, fast, observable)

Gateway mode, RPC on, so the "RPC sender starts only after ramp-complete" behavior is exercised:

```bash
TEST_API=gateway
REST_URL=http://127.0.0.1:8080
MQTT_HOST=127.0.0.1
REST_USERNAME=tenant@thingsboard.org
REST_PASSWORD=tenant

# small fleet: 6 gateways x 2 sub-devices
GATEWAY_START_IDX=0
GATEWAY_END_IDX=6
DEVICE_START_IDX=0
DEVICE_END_IDX=12
GATEWAY_CREATE_ON_START=true
GATEWAY_DELETE_ON_COMPLETE=true

# STAGGERED currently requires these two (checkStaggeredSupported fails fast otherwise)
GATEWAY_BATCH=true
ALARMS_PER_SECOND=0

ONBOARD_MODE=STAGGERED
ONBOARD_MAX_CONCURRENT=2      # low cap relative to 6 gateways so pacing is visible
ONBOARD_FIRST_JITTER_SEC=20   # short but long enough to see the ramp spread out

MESSAGES_PER_SECOND=6
DURATION_IN_SECONDS=90

GATEWAY_RPC_ENABLED=true
GATEWAY_RPC_SENDER_ENABLED=true
GATEWAY_RPC_SENDER_INTERVAL_SEC=60

STATS_LOG_ENABLED=true
STATS_LOG_INTERVAL_SEC=10
```

For a **device**-mode smoke instead, set `TEST_API=device`, drop the gateway/RPC keys, and use
`DEVICE_START_IDX`/`DEVICE_END_IDX` for the fleet size — direct-device STAGGERED has no RPC step.

## 2. Expected log sequence

All lines below are `INFO` unless noted; `%` placeholders are the actual `{}` slots from the code.

1. **Model built (gateway mode only; runs inside `connectGateways()`, before the engine starts):**
   ```
   STAGGERED model prepared: 6 gateways, 12 devices
   ```
   (`MqttGatewayAPITest.prepareStaggeredModel()`). Device mode logs the device-only equivalent:
   ```
   STAGGERED model prepared: 12 devices
   ```

2. **Ramp starts** (`StaggeredOnboardingEngine.start()`):
   ```
   Staggered onboarding starting: 6 entities, maxConcurrent=2, firstJitter=20000ms
   ```
   Confirm `maxConcurrent` and `firstJitter` match the env above.

3. **Paced onboarding, peak concurrency ≤ cap.** There is no per-success log line at INFO (only
   per-*failure* is logged — see below), so pacing is verified structurally + by timing rather than by
   counting a log line:
   - The engine bounds concurrent onboards with a `Semaphore(ONBOARD_MAX_CONCURRENT)` — this is enforced
     in code (`StaggeredOnboardingEngine.onboardOne`), not just logged, so "≤ cap" holds by construction
     as long as `Ramp complete` (step 5) doesn't fire suspiciously fast.
   - With `STATS_LOG_ENABLED=true`, watch for periodic `THROUGHPUT`/telemetry `DEBUG` lines (enable
     `logging.level.org.thingsboard.tools=DEBUG` to see them) — each gateway's own
     `[N] Message was successfully published to device: ... and gateway: ...` line should start appearing
     at different wall-clock times as each gateway finishes its own onboard, not all at once. With
     `ONBOARD_MAX_CONCURRENT=2` and 6 gateways, expect the *last* gateway's first telemetry line noticeably
     later than the first gateway's — never all 6 appearing within the same second.
   - Sanity bound: `Ramp complete` (step 5) should land at least `ONBOARD_FIRST_JITTER_SEC` after step 2
     (the jitter alone spreads first-attempt times over that window), and later still if any gateway had
     to queue for a permit.
   - A connect/announce/subscribe failure for one entity logs (`WARN`, from the engine, not the caller):
     ```
     Onboard failed for entity 3: java.lang.RuntimeException: ...
     ```
     None expected in a clean smoke run against a healthy broker.

4. **Per-gateway telemetry throughout.** Each gateway's own batch-telemetry timer starts immediately after
   *that* gateway's onboarding succeeds (not after the whole ramp) — `MqttGatewayAPITest.
   scheduleGatewayTelemetry()`. At `DEBUG`:
   ```
   [1] Message was successfully published to device: batch[2 devices] and gateway: GW00000000
   ```
   one such line per gateway per publish tick, ticks starting at different times per gateway (jittered)
   and continuing at a steady period for the rest of the run. A publish failure (should not happen against
   a healthy broker) logs at `ERROR`:
   ```
   [1] Error while publishing message to device: batch[...] and gateway: GW00000000 ...
   ```
   Device mode: same idea, `scheduleDeviceTelemetry()`, message text
   `Message was successfully published to device: <name>` (device mode has no `and gateway:` suffix).

5. **Ramp complete, RPC sender starts only now.** Two lines fire back-to-back from the same callback
   (`MqttGatewayAPITest.runStaggeredApiTests`'s `onComplete`), immediately preceded by the engine's own
   completion line:
   ```
   Ramp complete: 6 onboarded, 0 failed
   STAGGERED gateway ramp complete: 6 onboarded, 0 failed — starting RPC sender
   ```
   Note: the second line's "— starting RPC sender" text is unconditional — it prints even if
   `GATEWAY_RPC_SENDER_ENABLED=false` — the sender only actually starts inside the `if (rpcSenderEnabled)`
   guard right after. With the env above (`GATEWAY_RPC_SENDER_ENABLED=true`) it does start, confirmed by
   `RpcBurstSender` itself:
   ```
   RPC burst sender: 12 devices in ... chunks of 500, every 60s, first burst in ...ms (url ...)
   ```
   **This device count (12) must equal the full device range**, not just the devices belonging to
   gateways that onboarded early — confirming the sender was built from the complete post-ramp device
   list, not a partial one. Device mode has no RPC step, so step 5 for device mode is just the single
   `STAGGERED device ramp complete: 12 onboarded, 0 failed` line (no RPC sender to start).

6. **Shutdown** (after `DURATION_IN_SECONDS`): gateway/device telemetry timers cancel, the engine stops,
   the RPC burst sender stops, and (gateway+RPC only) the usual drain block runs:
   ```
   Gateway RPC drain: waiting for in-flight RPCs to settle (quietSec=5, maxSec=...)...
   Gateway RPC drain complete [drained ...s, quiesced=true]
   RPC In [total]: publish=... (new ..., redelivered ...)
   RPC Out [total]: publish=..., pubAck=..., failed=..., recovered=..., lost=...
   ```

## 3. Pass/fail checklist

- [ ] `STAGGERED model prepared: ...` appears once, with the expected entity counts.
- [ ] `Staggered onboarding starting: N entities, maxConcurrent=2, firstJitter=20000ms` — cap and jitter match config.
- [ ] Zero (or explained) `Onboard failed for entity ...` lines.
- [ ] Per-gateway/device telemetry `DEBUG` lines appear at staggered times, not bunched at one instant.
- [ ] `Ramp complete: N onboarded, 0 failed` fires only after step 2's timestamp + roughly the jitter/cap-bounded ramp time — not immediately.
- [ ] `STAGGERED gateway ramp complete: ...` fires immediately after, and `RPC burst sender: <full-device-count> devices ...` (if `GATEWAY_RPC_SENDER_ENABLED=true`) shows the **complete** device range, proving the sender started from the full post-ramp list and only after ramp-complete (there is no earlier `RPC burst sender: ...` line anywhere above it in the log).
- [ ] Drain + `RPC In [total]` / `RPC Out [total]` lines appear at shutdown (RPC runs only).

## 4. Known gaps to account for when reading the log (not smoke-test failures)

- **No periodic `Connections [window Ns]: live=.../...` line during STAGGERED.** `registerConnectionStats()`
  is only called on the PHASED connect path; STAGGERED's `connectGateways()`/`connectDevices()` return
  early before reaching it (by design — see Task 3/4 notes: the fixed-fleet connections gauge doesn't fit
  a paced ramp). Don't wait for this line; it will not appear.
- **Gateway + `GATEWAY_RPC_ENABLED=true`: the periodic `RPC Subscription` / `RPC In` / `RPC Out` /
  `Gateway device announce` interval lines do not appear during a STAGGERED run.** `runStaggeredApiTests()`
  calls `statsReporter().start()` *before* `initRpcReceiver()` registers those four blocks
  (`MqttGatewayAPITest.java`, `statsReporter().start()` a few lines above the `if (rpcEnabled)
  { initRpcReceiver(); }` that follows it). `StatsReporter.start()` checks `sources.isEmpty()` and, finding
  it empty at that point, logs `Stats logging: no active sources for this run` and returns without ever
  scheduling the periodic task — and nothing calls `start()` again afterward, so the four blocks registered
  moments later are silently never reported for the rest of the run. **This looks like a real ordering
  defect** (unlike the connections-gauge gap above, these blocks clearly are meant to report — the
  `register()` calls exist), reported separately rather than fixed here per this task's scope (verification
  only, no production-code changes). It does **not** lose data: the one-shot drain-time
  `RPC In [total]` / `RPC Out [total]` lines (step 5/6 above) are logged directly, not through
  `StatsReporter`, so end-of-run totals are still correct — only the per-`STATS_LOG_INTERVAL_SEC` snapshots
  during the run are missing. If you need those during the run, that's a sign the ordering bug needs
  fixing, not that your run is broken.
