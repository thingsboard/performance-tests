package org.thingsboard.tools.service.gateway;

import lombok.Data;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.server.common.data.id.DeviceId;

@Data
public class DeviceGatewayClient {

    private DeviceId gatewayId;
    private String gatewayName;

    private DeviceId deviceId;
    private String deviceName;

    private MqttClient mqttClient;
}
