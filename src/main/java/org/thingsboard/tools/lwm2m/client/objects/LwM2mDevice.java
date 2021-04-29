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

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel.Type;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Data
public class LwM2mDevice extends BaseInstanceEnabler {
    private static final Random RANDOM = new Random();
    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21);
    private static List<Integer> readableResourceIds = new ArrayList<>();
    private int id;
    private ObjectModel model;
    private String Manufacturer = "Thingsboard Test Device";
    private String modelNumber = "Model 500";
    private String serialNumber = "TH-500-000-0001";
    private String firmwareVersion = "1.2";
    private Integer availablePowerSources = 2;
    private Integer powerSourceVoltage = 5000;  // mV
    private Integer powerSourceCurrent = 3;  // mA
    private Integer batteryLevel;  // mV
    private Integer memoryFree = 256;  // in kilobytes
    private Integer errorCode = 0;  // 0=No error... 32=Device specific error codes
    private int keyCode = 0;
    private  Map<Integer, Long> errorCodes = new HashMap<Integer, Long>();  // 0=No error... 32=Device specific error codes
    private Date currentTime;
    private String utcOffset = new SimpleDateFormat("X").format(Calendar.getInstance().getTime());
    private String timeZone = TimeZone.getDefault().getID();
    private String supportedBinding = "UQ";
    private String deviceType = "mart meters";
    private String hardwareVersion = "1.01";
    private String softwareVersion = "1.02";
    private Integer batteryStatus = 0;
    private Integer memoryTotal = 512;

    public LwM2mDevice() {
//                 notify new date each 5 second
        Timer timer = new Timer("Device-Current Time, Value bet[/1/0/3]ery, utcOffse, timeZone");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                fireResourcesChange(9, 13, 14, 15);
            }
        }, 5000, 5000);
    }

    public LwM2mDevice(ScheduledExecutorService executorService) {
        try {
//            ResourceChangedListener resourceChangedListener = new ResourceChangedListener() {
//                @Override
//                public void resourcesChanged(int... resourceIds) {
//                    log.warn("Listener resourceIds: {}", resourceIds);
//                }
//            };
//            this.addResourceChangedListener(resourceChangedListener);
            executorService.scheduleWithFixedDelay(() ->
                    fireResourcesChange(9, 13, 14, 15), 10000, 10000, TimeUnit.MILLISECONDS);
//                    fireResourcesChange(9, 13), 5000, 5000, TimeUnit.MILLISECONDS);
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
            case 6:
                return ReadResponse.success(resourceid, getAvailablePowerSources());
            case 7:
                return ReadResponse.success(resourceid, getPowerSourceVoltage());
            case 8:
                return ReadResponse.success(resourceid, getPowerSourceCurrent());
            case 9:
                return ReadResponse.success(resourceid, getBatteryLevel());
            case 10:
                return ReadResponse.success(resourceid, getMemoryFree());
            case 11:
                return ReadResponse.success(resourceid, getErrorCodes(), Type.INTEGER);
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
        switch (resourceid) {
            case 4:
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
                break;
            case 5:
                getLwM2mClient().triggerRegistrationUpdate();
                break;
            case 12:
                errorCodes = new HashMap<Integer, Long>();
                errorCode = 0;
                keyCode = 0;
                break;
            default:
                break;
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

    public synchronized WriteAttributesResponse writeAttributes(ServerIdentity identity,
                                                                WriteAttributesRequest request) {
        // execute is not supported for bootstrap
        if (identity.isLwm2mBootstrapServer()) {
            return WriteAttributesResponse.methodNotAllowed();
        }
        // TODO should be implemented here to be available for all object enabler
        // This should be a not implemented error, but this is not defined in the spec.
        return WriteAttributesResponse.internalServerError("not implemented");
    }

    private int getBatteryLevel() {
        return batteryLevel = RANDOM.nextInt(101);
    }

    private int getPowerSourceVoltage() {
        return powerSourceVoltage = RANDOM.nextInt(101);
    }
    private int getPowerSourceCurrent() {
        return powerSourceCurrent = RANDOM.nextInt(101);
    }

    private long getMemoryFree() {
        return memoryFree = Math.toIntExact(Runtime.getRuntime().freeMemory() / 1024);
    }

    private long getErrorCode() {
        return errorCode = RANDOM.nextInt(33);
    }

    private Map<Integer, Long> getErrorCodes () {
         errorCodes.put(keyCode++, getErrorCode());
        return errorCodes;
    }

    private Date getCurrentTime() {
        return currentTime = new Date();
    }

    private String getUtcOffset() {
        return utcOffset;
    }

    private void setUtcOffset(String t) {
        utcOffset = t;
    }

    private String getTimezone() {
        return timeZone;
    }

    private void setTimezone(String t) {
        timeZone = t;
    }

    private int getBatteryStatus() {
        return batteryStatus = RANDOM.nextInt(7);
    }

    private long getMemoryTotal() {
        return memoryTotal = Math.toIntExact(Runtime.getRuntime().totalMemory() / 1024);
    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel model) {
        this.model = this.model == null ? model : this.model;
        this.id = this.model != null ? this.model.id : this.id;
        return supportedResources;
    }

    public List<Integer> getReadableResourceIds() {
        return model != null ? readableResourceIds = model.resources.entrySet().stream()
                .filter(rez -> rez.getValue().operations.isReadable()).map(s -> s.getValue().id)
                .collect(Collectors.toList()) : new ArrayList<>();
    }

    public Integer getId () {
        return super.getId();
    }

    @Override
    public ObjectModel getModel() {
        return super.getModel();
    }
}
