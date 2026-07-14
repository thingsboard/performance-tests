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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.thingsboard.mqtt.MqttClient;

import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reliably (re)announces a downstream device to its gateway ({@code v1/gateway/connect}) so the server
 * re-establishes that sub-device's RPC routing. Publishes at QoS 1 and drives {@link AckedRetry} on the
 * client's netty event loop, so success is a PUBACK (not a QoS-0 flush) and a lost/timed-out announce is
 * retried until confirmed or the cap is hit ({@code unconfirmed}). A global in-flight {@link Semaphore}
 * bounds concurrent announces so a reconnect storm cannot self-amplify; the permit is acquired
 * non-blockingly (re-queued on the event loop when saturated — never a blocking acquire on an event
 * loop) and released on the terminal outcome.
 */
public class GatewayDeviceAnnouncer {

    private static final String CONNECT_TOPIC = "v1/gateway/connect";
    private static final MqttQoS QOS = MqttQoS.AT_LEAST_ONCE;

    private final AnnounceStats stats;
    private final AckedRetryConfig cfg;
    private final Random rng;
    private final Semaphore inFlight;
    private final long permitWaitMs;

    public GatewayDeviceAnnouncer(AnnounceStats stats, AckedRetryConfig cfg, Random rng,
                                  int maxConcurrent, long permitWaitMs) {
        this.stats = stats;
        this.cfg = cfg;
        this.rng = rng;
        this.inFlight = new Semaphore(Math.max(1, maxConcurrent));
        this.permitWaitMs = permitWaitMs;
    }

    /** Announce one device on its gateway client. The returned future completes success on PUBACK-confirm
     *  and failure if the device could not be confirmed within the retry cap (so warm-up can await it). */
    public Future<Void> announce(MqttClient client, byte[] connectPayload) {
        Promise<Void> done = ImmediateEventExecutor.INSTANCE.newPromise();
        acquireThenRun(client, connectPayload, done);
        return done;
    }

    private void acquireThenRun(MqttClient client, byte[] payload, Promise<Void> done) {
        if (!inFlight.tryAcquire()) {
            // Saturated: never block the event loop — re-queue this announce after a short jittered wait.
            client.getEventLoop().schedule(() -> acquireThenRun(client, payload, done),
                    1L + rng.nextInt((int) Math.max(1, permitWaitMs)), TimeUnit.MILLISECONDS);
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                inFlight.release();
            }
        };
        AckedRetry.run(client.getEventLoop(),
                () -> client.publish(CONNECT_TOPIC, Unpooled.wrappedBuffer(payload), QOS),
                cfg, rng, new AckedRetry.Callbacks() {
                    @Override
                    public void onAcked() {
                        stats.onAcked();
                        release.run();
                        done.trySuccess(null);
                    }

                    @Override
                    public void onAttemptFailed() {
                        stats.onAttemptFailed();
                    }

                    @Override
                    public void onRetry() {
                        stats.onRetry();
                    }

                    @Override
                    public void onUnconfirmed() {
                        stats.onUnconfirmed();
                        release.run();
                        done.tryFailure(new RuntimeException("device announce unconfirmed after retries"));
                    }
                });
    }
}
