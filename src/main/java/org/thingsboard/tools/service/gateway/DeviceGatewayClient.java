package org.thingsboard.tools.service.gateway;

import lombok.Data;
import org.thingsboard.mqtt.MqttClient;

@Data
public class DeviceGatewayClient {

    private String gatewayName;

    private String deviceName;

    private MqttClient mqttClient;
}
