package org.thingsboard.tools.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.device.DeviceAPITest;
import org.thingsboard.tools.service.shared.BaseTestExecutor;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "test", value = "api", havingValue = "lwm2m")
public class LwM2MClientBaseTestExecutor extends BaseTestExecutor {


    @Autowired
    private DeviceAPITest deviceAPITest;


    @Override
    protected void initEntities() throws Exception {
        if (deviceCreateOnStart) {
            deviceAPITest.createDevices();
        }

        if (testEnabled) {
            deviceAPITest.connectDevices();
        }
    }

    @Override
    protected void runApiTests() throws InterruptedException {

    }

    @Override
    protected void cleanUpEntities() throws Exception {

    }

    @Override
    protected void waitOtherClients() throws Exception {
        if (testEnabled) {
            while (true) {
                try {
                    log.info("Test completed. Waiting for other clients to complete!");
                    log.info("If all clients done, please execute next command: 'kubectl delete statefulset tb-performance-run'");
                    TimeUnit.SECONDS.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
        }
    }
}
