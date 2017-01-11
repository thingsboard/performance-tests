/**
 * Copyright © ${project.inceptionYear}-2017 The Thingsboard Authors
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
package org.thingsboard.client.tools;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class MqttStressTestClient {

    @Getter
    private final String deviceToken;
    @Getter
    private final String clientId;
    private final MqttClientPersistence persistence;
    private final MqttAsyncClient client;
    private final ResultAccumulator results;

    public MqttStressTestClient(ResultAccumulator results, String brokerUri, String deviceToken) throws MqttException {
        this.results = results;
        this.clientId = MqttAsyncClient.generateClientId();
        this.deviceToken = deviceToken;
        this.persistence = new MemoryPersistence();
        this.client = new MqttAsyncClient(brokerUri, clientId, persistence);
    }

    public IMqttToken connect() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(deviceToken);
        return client.connect(options, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken iMqttToken) {
                log.info("OnSuccess");
            }

            @Override
            public void onFailure(IMqttToken iMqttToken, Throwable e) {
                log.info("OnFailure", e);
            }
        });
    }

    public void disconnect() throws MqttException {
        client.disconnect();
    }



    public void warmUp(byte[] data) throws MqttException {
        MqttMessage msg = new MqttMessage(data);
        client.publish("v1/devices/me/telemetry", msg, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
            }
        }).waitForCompletion();
    }


    public void publishTelemetry(byte[] data) throws MqttException {
        long sendTime = System.currentTimeMillis();
        MqttMessage msg = new MqttMessage(data);
        client.publish("v1/devices/me/telemetry", msg, null, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                long ackTime = System.currentTimeMillis();
                results.onResult(true, ackTime - sendTime);
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                long failTime = System.currentTimeMillis();
                results.onResult(false, failTime - sendTime);
            }
        });
    }
}
