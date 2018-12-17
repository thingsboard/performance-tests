package org.thingsboard.tools.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.tools.service.device.DeviceManager;
import org.thingsboard.tools.service.device.RuleChainManager;
import org.thingsboard.tools.service.device.StatisticsCollector;

import javax.annotation.PostConstruct;

@Slf4j
@Service
public class TestExecutor {

    @Value("${tests.device.create}")
    private boolean deviceCreate;

    @Value("${tests.device.deleteOnComplete}")
    private boolean deviceDeleteOnComplete;

    @Value("${tests.publish.count}")
    private int publishTelemetryCount;

    @Value("${tests.publish.pause}")
    private int publishTelemetryPause;

    @Autowired
    private StatisticsCollector statisticsCollector;

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private RuleChainManager ruleChainManager;

    @PostConstruct
    public void init() throws Exception {
        ruleChainManager.init();
        statisticsCollector.init();
        if (deviceCreate) {
            deviceManager.createDevices();
        }
        deviceManager.warmUpDevices();
        deviceManager.runTests(publishTelemetryCount, publishTelemetryPause);
        if (deviceDeleteOnComplete) {
            deviceManager.removeDevices();
        }
        statisticsCollector.printResults();
    }

}
