/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.tools.lwm2m.client.objects;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LwM2mTemperatureSensor extends LwM2mBaseInstanceEnabler {

    private static final String UNIT_CELSIUS = "cel";
    private double currentTemp = 20d;
    private double minMeasuredValue = currentTemp;
    private double maxMeasuredValue = currentTemp;

    public LwM2mTemperatureSensor() {

    }

    public LwM2mTemperatureSensor(ScheduledExecutorService executorService, List<Integer> unSupportedResources, Integer id) {
        try {
            if (id != null) this.setId(id);
        executorService.scheduleWithFixedDelay(this::adjustTemperature, 2000, 2000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public synchronized ReadResponse read(ServerIdentity identity, int resourceid) {
//        log.info("Read on Temperature resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
        resourceid = getSupportedResource (resourceid);
        switch (resourceid) {
            case 5601:
                return ReadResponse.success(resourceid, getTwoDigitValue(minMeasuredValue));
            case 5602:
                return ReadResponse.success(resourceid, getTwoDigitValue(maxMeasuredValue));
            case 5700:
                return ReadResponse.success(resourceid, getTwoDigitValue(currentTemp));
            case 5701:
                return ReadResponse.success(resourceid, UNIT_CELSIUS);
            default:
                return super.read(identity, resourceid);
        }
    }

    @Override
    public synchronized ExecuteResponse execute(ServerIdentity identity, int resourceId, String params) {
//        log.info("Execute on Temperature resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
        resourceId = getSupportedResource (resourceId);
        switch (resourceId) {
            case 5605:
                resetMinMaxMeasuredValues();
                return ExecuteResponse.success();
            default:
                return super.execute(identity, resourceId, params);
        }
    }

    private double getTwoDigitValue(double value) {
        BigDecimal toBeTruncated = BigDecimal.valueOf(value);
        return toBeTruncated.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private void adjustTemperature() {
        float delta = (RANDOM.nextInt(20) - 10) / 10f;
        currentTemp += delta;
        Integer changedResource = adjustMinMaxMeasuredValue(currentTemp);
        if (changedResource != null) {
            fireResourcesChange(5700, changedResource);
        } else {
            fireResourcesChange(5700);
        }
    }

    private synchronized Integer adjustMinMaxMeasuredValue(double newTemperature) {
        if (newTemperature > maxMeasuredValue) {
            maxMeasuredValue = newTemperature;
            return 5602;
        } else if (newTemperature < minMeasuredValue) {
            minMeasuredValue = newTemperature;
            return 5601;
        } else {
            return null;
        }
    }

    private void resetMinMaxMeasuredValues() {
        minMeasuredValue = currentTemp;
        maxMeasuredValue = currentTemp;
    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel model) {
        return supportedResources;
    }
}
