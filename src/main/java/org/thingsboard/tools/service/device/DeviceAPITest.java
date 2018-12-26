package org.thingsboard.tools.service.device;

public interface DeviceAPITest {

    void createDevices() throws Exception;

    void removeDevices() throws Exception;

    void warmUpDevices() throws InterruptedException;

    void runApiTests(int publishTelemetryCount, final int publishTelemetryPause) throws InterruptedException;
}
