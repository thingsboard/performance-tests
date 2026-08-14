# Known issues

Issues that show up when running this tool, with root cause and status. Each entry states whether it affects
measurement correctness, because that is the only thing that should ever block a run.

---

## 1. `IllegalReferenceCountException` in `MqttChannelHandler.handlePuback` (log noise, not data loss)

**Status:** open. Root cause identified, fix known, blocked on a `netty-mqtt` release + this tool's migration to
the 4.3.1.4 line (see "Why it is not fixed yet").

**Affects measurement:** no. Delivery accounting is unaffected — see "Why it is benign".

### Symptom
Under load, many stack traces like this, each preceded by
`WARN o.t.mqtt.MqttChannelHandler - [null] exceptionCaught`:

```
io.netty.util.IllegalReferenceCountException: refCnt: 0, decrement: 1
    at io.netty.util.internal.ReferenceCountUpdater.toLiveRealRefCnt(ReferenceCountUpdater.java:83)
    at io.netty.util.internal.ReferenceCountUpdater.release(ReferenceCountUpdater.java:148)
    at io.netty.buffer.AbstractReferenceCountedByteBuf.release(AbstractReferenceCountedByteBuf.java:101)
    at org.thingsboard.mqtt.MqttChannelHandler.handlePuback(MqttChannelHandler.java:292)
    at org.thingsboard.mqtt.MqttChannelHandler.channelRead0(MqttChannelHandler.java:76)
    ...
```

Frequency scales with connection churn and with the number of in-flight QoS-1 publishes. A large persistent-
gateway run produced ~1300 occurrences in 30 minutes.

### Root cause
A `MqttPendingPublish` payload is retained once but can be released by several paths, and one of them frees the
buffer **without removing the pending entry from the client's `pendingPublishes` map**:

`netty-mqtt/src/main/java/org/thingsboard/mqtt/MqttPendingPublish.java`
```java
void onChannelClosed() {
    publishRetransmissionHandler.stop();
    pubrelRetransmissionHandler.stop();
    if (payload != null) {
        payload.release();          // frees the payload, but the map entry survives
    }
}
```

Race:
1. A channel closes while a QoS-1 publish is still in flight → `onChannelClosed()` releases the payload and
   leaves the entry in `pendingPublishes`.
2. A late `PUBACK` for that message id arrives → `handlePuback`'s `computeIfPresent` still finds the entry and
   calls `getPayload().release()` on a buffer already at refCnt 0 → `IllegalReferenceCountException`.

Release sites for the same payload (all in `netty-mqtt`):

| site | atomic w.r.t. the map? |
|---|---|
| `MqttChannelHandler.handlePuback` (~line 291) | yes — `computeIfPresent` |
| `MqttChannelHandler.handlePubcomp` (~line 334) | no — `get()`, then `remove()`, then `release()` (QoS 2 only) |
| `MqttClientImpl.onMaxRetransmissionAttemptsReached` (~line 427) | yes — `computeIfPresent` |
| `MqttPendingPublish.onChannelClosed` (~line 91) | **no — releases without removing the entry** |

Note the retransmission path is *not* the culprit: `MqttPendingPublish.startPublishRetransmissionTimer` correctly
does `payload.retain()` before each re-send, so writes stay balanced.

### Why it is benign
The double release happens in post-ack bookkeeping on an **already-closed** channel. It does not drop, duplicate
or corrupt a published message, and it does not touch this tool's delivery counters — `publish`, `pubAck`,
`failed`, `rePublished`, `recovered`, `lost` are maintained by the tool itself and remain authoritative. Treat
the exception as noise; judge delivery from the stats blocks and the end-of-run totals.

### Why it is not fixed yet
The bug lives in the `netty-mqtt` module of the ThingsBoard server repo, which this tool consumes as a released
artifact (`thingsboard.version` in `pom.xml`). Fixing it therefore requires a `netty-mqtt` release, and this tool
is still on the 4.0.1 line — moving to 4.3.1.4 is a broader dependency migration, not a one-line version bump.

**Do not migrate solely to escape this bug — it is not fixed upstream either.** Verified against `v4.3.1.3`:
`onChannelClosed()` and `handlePuback()` are byte-identical to the 4.0.1 code, and every commit touching
`netty-mqtt/` between `v4.0.1` and `v4.3.1.3` is version-bump or maven-plugin housekeeping — no functional change:

```
beb7b109ef Version set to 4.3.1.3
83097d7370 Version set to 4.3.1.3-SNAPSHOT
...
b0efe276eb Refactor dao and netty-mqtt to inherit maven-jar-plugin version from pluginManagement
04ccf48419 Fix maven-jar-plugin version mismatch across modules
```

A further reason not to rush the migration: running the load tool on a newer line than the server under test
introduces version skew on the very protocol being measured. Migrate tool and server together, deliberately.

### Fix (for whenever netty-mqtt is next released)
Make the payload release idempotent so every site is safe, in `MqttPendingPublish`:

```java
private final AtomicBoolean payloadReleased = new AtomicBoolean();

private void releasePayloadOnce() {
    if (payload != null && payloadReleased.compareAndSet(false, true)) {
        payload.release();
    }
}
```

Then call `releasePayloadOnce()` instead of `payload.release()` / `getPayload().release()` from all four sites
above. This also fixes the same latent double-release for any other consumer of the library (gateways,
integrations), not just this tool. Optionally also make `handlePubcomp` remove-then-release atomically.

### Workaround until then
Silence the logger in the run's logback config, e.g.:

```xml
<logger name="org.thingsboard.mqtt.MqttChannelHandler" level="ERROR"/>
```

**Trade-off, decide deliberately:** `exceptionCaught` logs *all* channel exceptions at WARN, so this also hides
genuine MQTT channel errors (failed subscribes, unexpected disconnects). Acceptable when the tool's own counters
are the source of truth for delivery; not acceptable when debugging connectivity.
