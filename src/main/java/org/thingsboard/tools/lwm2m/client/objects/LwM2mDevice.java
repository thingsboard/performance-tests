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
import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel.Type;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class LwM2mDevice extends BaseInstanceEnabler {
    private static final Random RANDOM = new Random();
    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 9, 10, 11, 13, 14, 15, 16, 17, 18,
            19, 20, 21);

    public LwM2mDevice() {
//                 notify new date each 5 second
        Timer timer = new Timer("Device-Current Time, Value betery, utcOffse, timeZone");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                fireResourcesChange(9, 13, 14, 15);
            }
        }, 5000, 5000);
    }

    public LwM2mDevice(ScheduledExecutorService executorService) {
        try {
            executorService.scheduleWithFixedDelay(() ->
//                    fireResourcesChange(9, 13, 14, 15), 10000, 10000, TimeUnit.MILLISECONDS);
                    fireResourcesChange(9, 13), 2000, 2000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }

    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceid) {
//        log.info("Read on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        switch (resourceid) {
            case 0:
                return ReadResponse.success(resourceid, getManufacturer());
            case 1:
                return ReadResponse.success(resourceid, getModelNumber());
            case 2:
                return ReadResponse.success(resourceid, getSerialNumber());
            case 3:
                return ReadResponse.success(resourceid, getFirmwareVersion());
            case 9:
                return ReadResponse.success(resourceid, getBatteryLevel());
            case 10:
                return ReadResponse.success(resourceid, getMemoryFree());
            case 11:
                Map<Integer, Long> errorCodes = new HashMap<Integer, Long>();
                errorCodes.put(0, getErrorCode());
                return ReadResponse.success(resourceid, errorCodes, Type.INTEGER);
            case 13:
                return ReadResponse.success(resourceid, getCurrentTime());
            case 14:
                return ReadResponse.success(resourceid, getUtcOffset());
            case 15:
                return ReadResponse.success(resourceid, getTimezone());
            case 16:
                return ReadResponse.success(resourceid, getSupportedBinding());
            case 17:
                return ReadResponse.success(resourceid, getDeviceType());
            case 18:
                return ReadResponse.success(resourceid, getHardwareVersion());
            case 19:
                return ReadResponse.success(resourceid, getSoftwareVersion());
            case 20:
                return ReadResponse.success(resourceid, getBatteryStatus());
            case 21:
                return ReadResponse.success(resourceid, getMemoryTotal());
            default:
                return super.read(identity, resourceid);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceid, String params) {
//        String withParams = null;
//        if (params != null && params.length() != 0)
//            withParams = " with params " + params;
//        log.info("Execute on Device resource /[{}]/[{}]/[{}] [{}]", getModel().id, getId(), resourceid,
//                withParams != null ? withParams : "");

        if (resourceid == 4) {
            new Timer("Reboot Lwm2mClient").schedule(new TimerTask() {
                @Override
                public void run() {
                    getLwM2mClient().stop(true);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                    }
                    getLwM2mClient().start();
                }
            }, 500);
        }
        if (resourceid == 5) {
            getLwM2mClient().triggerRegistrationUpdate();
        }
        return ExecuteResponse.success();
    }

    @Override
    public WriteResponse write(ServerIdentity identity, int resourceid, LwM2mResource value) {
//        log.info("Write on Device resource /[{}]/[{}]/[{}] value [{}]", getModel().id, getId(), resourceid, value);

        switch (resourceid) {
            case 13:
                return WriteResponse.notFound();
            case 14:
                setUtcOffset((String) value.getValue());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 15:
                setTimezone((String) value.getValue());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            default:
                return super.write(identity, resourceid, value);
        }
    }

    private String getManufacturer() {
        return "Thingsboard Test Device";
    }

    private String getModelNumber() {
        return "Model 500";
    }

    private String getSerialNumber() {
        return "TH-500-000-0001";
    }

    private String getFirmwareVersion() {
        return "1.0.0";
    }

    private long getErrorCode() {
        return 0;
    }

    private int getBatteryLevel() {
        return RANDOM.nextInt(101);
    }

    private long getMemoryFree() {
        return Runtime.getRuntime().freeMemory() / 1024;
    }

    private Date getCurrentTime() {
        return new Date();
    }

    private String utcOffset = new SimpleDateFormat("X").format(Calendar.getInstance().getTime());

    private String getUtcOffset() {
        return utcOffset;
    }

    private void setUtcOffset(String t) {
        utcOffset = t;
    }

    private String timeZone = TimeZone.getDefault().getID();

    private String getTimezone() {
        return timeZone;
    }

    private void setTimezone(String t) {
        timeZone = t;
    }

    private String getSupportedBinding() {
        return "U";
    }

    private String getDeviceType() {
        return "Test";
    }

    private String getHardwareVersion() {
        return "1.0.1";
    }

    private String getSoftwareVersion() {
        return "1.0.2";
    }

    private int getBatteryStatus() {
        return RANDOM.nextInt(7);
    }

    private long getMemoryTotal() {
        return Runtime.getRuntime().totalMemory() / 1024;
    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel model) {
        return supportedResources;
    }
}
