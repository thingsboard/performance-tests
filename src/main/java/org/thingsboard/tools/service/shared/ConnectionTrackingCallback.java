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
package org.thingsboard.tools.service.shared;

import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import org.thingsboard.mqtt.MqttClientCallback;

/**
 * One instance per MQTT client. Classifies lifecycle events into {@link ConnectionStats}:
 * the first CONNACK is a connect; any CONNACK after a drop is a reconnect; a channel close while
 * up is a disconnect. Two flags keep this correct without shared per-client state:
 * {@code everConnected} distinguishes the first connect from later reconnects, {@code up} guards
 * against duplicate CONNACK/close events. Reconnect is classified via {@code onConnAck} (which the
 * client fires on every CONNACK, initial and reconnect); {@code onSuccessfulReconnect} is left as a
 * no-op to avoid double counting.
 */
public class ConnectionTrackingCallback implements MqttClientCallback {

    private final ConnectionStats stats;
    private boolean everConnected;
    private boolean up;

    public ConnectionTrackingCallback(ConnectionStats stats) {
        this.stats = stats;
    }

    @Override
    public synchronized void onConnAck(MqttConnAckMessage message) {
        if (!everConnected) {
            everConnected = true;
            up = true;
            stats.onConnect();
        } else if (!up) {
            up = true;
            stats.onReconnect();
        }
    }

    @Override
    public synchronized void connectionLost(Throwable cause) {
        if (up) {
            up = false;
            stats.onDisconnect();
        }
    }

    @Override
    public void onSuccessfulReconnect() {
        // Reconnects are classified in onConnAck; overriding here would double count.
    }
}
