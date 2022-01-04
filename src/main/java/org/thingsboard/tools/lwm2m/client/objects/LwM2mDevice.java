/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ResourceModel.Type;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Data
public class LwM2mDevice extends LwM2mBaseInstanceEnabler {
    private String Manufacturer = "Thingsboard Test Device";
    private String modelNumber = "Model 500";
    private String serialNumber = "TH-500-000-0001";
    private String firmwareVersion = "TestThingsboard@TestMore1024_2.04";
    private Integer availablePowerSources = 1;
    private Integer powerSourceVoltage = 5000;  // mV
    private Integer powerSourceCurrent = 3;  // mA
    private Integer batteryLevel;  // mV
    private Integer batteryLevelCritical = 10;  // mV
    private Integer memoryFree = 256;  // in kilobytes
    private Integer memoryFreeCritical = 128;  // in kilobytes
    /**
     * 0=No error
     * 1=Low battery power
     * 2=External power supply off
     * 3=GPS module failure
     * 4=Low received signal strength
     * 5=Out of memory
     * 6=SMS failure
     * 7=IP connectivity failure
     * 8=Peripheral malfunction
     * 9..15=Reserved for future use
     * 16..32=Device specific error codes
     */
    private Map<Integer, Long> errorCode = new HashMap<>();  // 0=No error... 32=Device specific error codes
    private Date currentTime;
    private String utcOffset = new SimpleDateFormat("X").format(Calendar.getInstance().getTime());

    private String timeZone = TimeZone.getDefault().getID();

//    private String supportedBinding = "UQ";
    private String supportedBinding = "U";
    private String deviceType = "smart meters";
    private String hardwareVersion = "1.01";
    private String softwareVersion = "1.02";
    /**
     * 0	Normal	The battery is operating normally and not on power.
     * 1	Charging	The battery is currently charging.
     * 2	Charge Complete	The battery is fully charged and still on power.
     * 3	Damaged	The battery has some problem.
     * 4	Low Battery	The battery is low on charge.
     * 5	Not Installed	The battery is not installed.
     * 6	Unknown	The battery information is not available.
     */
    private Integer batteryStatus = 0;
    private Integer memoryTotal = 512;

    public LwM2mDevice() {
//                 notify new date each 5 second
        Timer timer = new Timer("Device-Current Time, Value bettery, utcOffse, timeZone");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                fireResourcesChange(9);
            }
        }, 5000, 5000);
    }

    public LwM2mDevice(ScheduledExecutorService executorService, Integer id) {
        try {
            if (id != null) this.setId(id);
            setErrorCode(1L);
            // 15 - not present
            this.supportedResources =  Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21);
            executorService.scheduleWithFixedDelay(() ->
//                    setBatteryStatus(), 10000, 10000, TimeUnit.MILLISECONDS);
                    fireResourcesChange(9), 10000, 10000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }

    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
        log.info("Read on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
        resourceId = getSupportedResource (resourceId);
        switch (resourceId) {
            case 0:
                return ReadResponse.success(resourceId, getManufacturer());
            case 1:
                return ReadResponse.success(resourceId, getModelNumber());
            case 2:
                return ReadResponse.success(resourceId, getSerialNumber());
            case 3:
                return ReadResponse.success(resourceId, getFirmwareVersion());
            case 6:
                return ReadResponse.success(resourceId, getAvailablePowerSources());
            case 7:
                return ReadResponse.success(resourceId, getPowerSourceVoltage());
            case 8:
                return ReadResponse.success(resourceId, getPowerSourceCurrent());
            case 9:
                return ReadResponse.success(resourceId, getBatteryLevel());
            case 10:
                return ReadResponse.success(resourceId, getMemoryFree());
            case 11:
                return ReadResponse.success(resourceId, getErrorCode(), Type.INTEGER);
            case 13:
                return ReadResponse.success(resourceId, getCurrentTime());
            case 14:
                return ReadResponse.success(resourceId, getUtcOffset());
            case 15:
                return ReadResponse.success(resourceId, getTimezone());
            case 16:
                return ReadResponse.success(resourceId, getSupportedBinding());
            case 17:
                return ReadResponse.success(resourceId, getDeviceType());
            case 18:
                return ReadResponse.success(resourceId, getHardwareVersion());
            case 19:
                return ReadResponse.success(resourceId, getSoftwareVersion());
            case 20:
                return ReadResponse.success(resourceId, getBatteryStatus());
            case 21:
                return ReadResponse.success(resourceId, getMemoryTotal());
            default:
                return super.read(identity, resourceId);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceId, String params) {
        log.info("Execute on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
//        String withParams = null;
//        if (params != null && params.length() != 0)
//            withParams = " with params " + params;
//        log.info("Execute on Device resource /[{}]/[{}]/[{}] [{}]", getModel().id, getId(), resourceid,
//                withParams != null ? withParams : "");
        resourceId = getSupportedResource (resourceId);
        switch (resourceId) {
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
                errorCode = new HashMap<>();
                break;
            default:
                break;
        }
        return ExecuteResponse.success();
    }

    @Override
    public WriteResponse write(ServerIdentity identity,  boolean replace, int resourceId, LwM2mResource value) {
        log.info("Write on Device resource /[{}]/[{}]/[{}] value [{}]", getModel().id, getId(), resourceId, value);
        resourceId = getSupportedResource (resourceId);
        switch (resourceId) {
            case 13:
                setCurrentTime((Date) value.getValue());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 14:
                setUtcOffset((String) value.getValue());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 15:
                setTimezone((String) value.getValue());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            default:
                return super.write(identity, replace, resourceId, value);
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
        this.batteryLevel = RANDOM.nextInt(101);
        if (this.batteryLevel < this.batteryLevelCritical) {
            setErrorCode(1L);
        }
        return batteryLevel;
    }

    private int getPowerSourceVoltage() {
        return powerSourceVoltage = RANDOM.nextInt(101);
    }
    private int getPowerSourceCurrent() {
        return powerSourceCurrent = RANDOM.nextInt(101);
    }

    private long getMemoryFree() {
        this.memoryFree = Math.toIntExact(Runtime.getRuntime().freeMemory() / 1024);
        if (this.memoryFree < this.memoryFreeCritical) {
            setErrorCode(5L);
        }
        return this.memoryFree;
    }

    private Map<Integer, Long> getErrorCode() {
        return this.errorCode;
    }

    private void setErrorCode(Long errorCode) {
         this.errorCode.put( this.errorCode.size(), errorCode);
    }

    private Date getCurrentTime() {
        return currentTime == null ? currentTime = new Date() : currentTime;
    }

    private Date setCurrentTime(Date date) {
        return currentTime = date;
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
        return this.batteryStatus;
    }

    private void setBatteryStatus() {
        int batteryStatus = RANDOM.nextInt(7);
        if (batteryStatus == 4) {
            setErrorCode(1L);
        }
        this.batteryStatus = batteryStatus;
        fireResourcesChange(9);
    }

    private long getMemoryTotal() {
        return memoryTotal = Math.toIntExact(Runtime.getRuntime().totalMemory() / 1024);
    }
}
