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
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mMultipleResource;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.sql.Time;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
public class LwM2mBinaryAppDataContainer extends LwM2mBaseInstanceEnabler {

    /**
     * id = 0
     * Multiple
     * base64
     */

    /**
     * Example1:
     * InNlcnZpY2VJZCI6Ik1ldGVyIiwNCiJzZXJ2aWNlRGF0YSI6ew0KImN1cnJlbnRSZWFka
     * W5nIjoiNDYuMyIsDQoic2lnbmFsU3RyZW5ndGgiOjE2LA0KImRhaWx5QWN0aXZpdHlUaW1lIjo1NzA2DQo=
     * "serviceId":"Meter",
     * "serviceData":{
     * "currentReading":"46.3",
     * "signalStrength":16,
     * "dailyActivityTime":5706
     */

    /**
     * Example2:
     * InNlcnZpY2VJZCI6IldhdGVyTWV0ZXIiLA0KImNtZCI6IlNFVF9URU1QRVJBVFVSRV9SRUFEX
     * 1BFUklPRCIsDQoicGFyYXMiOnsNCiJ2YWx1ZSI6NA0KICAgIH0sDQoNCg0K
     * "serviceId":"WaterMeter",
     * "cmd":"SET_TEMPERATURE_READ_PERIOD",
     * "paras":{
     * "value":4
     * },
     */
//    private String data = "InNlcnZpY2VJZCI6Ik1ldGVyIiwNCiJzZXJ2aWNlRGF0YSI6ew0KImN1cnJlbnRSZWFkaW5nIjoiNDYuMyIsDQoic2lnbmFsU3RyZW5ndGgiOjE2LA0KImRhaWx5QWN0aXZpdHlUaW1lIjo1NzA2DQo=";
//    private byte[] data;
    boolean dataSingle;
    //    Map<Integer, byte[]> data = new HashMap<>();
    private Object data;
    private Integer priority = 0;
    private Time timestamp;
    private String description;
    private String dataFormat;
    private Integer appID = -1;

    public LwM2mBinaryAppDataContainer() {
    }

    public LwM2mBinaryAppDataContainer(ScheduledExecutorService executorService, Integer id, boolean dataSingle) {
        try {
            if (id != null) this.setId(id);
            this.dataSingle = dataSingle;
//            executorService.scheduleWithFixedDelay(() ->
//                    fireResourcesChange(0, 2), 5000, 5000, TimeUnit.MILLISECONDS);
//                    fireResourcesChange(0, 2), 1800000, 1800000, TimeUnit.MILLISECONDS); // 30 MIN
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
        log.warn("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
        try {
            resourceId = getSupportedResource(resourceId);
            switch (resourceId) {
                case 0:
                    log.warn("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceId);
                    ReadResponse response = null;
                    if (this.dataSingle) {
                        if (getData() == null) {
                            if (this.id==1) {
                                return ReadResponse.internalServerError("error");
                            }
                            else {
                                setDataSingle(new byte[0]);
                                return ReadResponse.success(resourceId, getDataSingle());
                            }
                        }
                    } else {
                        response = ReadResponse.success(resourceId, (Map<Integer, ?>) getData(), ResourceModel.Type.OPAQUE);
                    }
                    log.warn("Response [{}]", response);
                    return response;

                case 1:
                    return ReadResponse.success(resourceId, getPriority());
                case 2:
                    return ReadResponse.success(resourceId, getTimestamp());
                case 3:
                    return ReadResponse.success(resourceId, getDescription());
                case 4:
                    return ReadResponse.success(resourceId, getDataFormat());
                case 5:
                    return ReadResponse.success(resourceId, getAppID());
                default:
                    return super.read(identity, resourceId);
            }
        } catch (Exception e) {
            return ReadResponse.badRequest(e.getMessage());
        }
    }

    @Override
    public WriteResponse write(ServerIdentity identity, boolean replace, int resourceId, LwM2mResource value) {
//        log.info("Write on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            case 0:
                if (setDataMulti(value, replace)) {
                    fireResourcesChange(resourceId);
                    return WriteResponse.success();
                } else {
                    return WriteResponse.badRequest("Invalidate value ...");
                }
            case 1:
                setPriority((Integer) (value.getValue() instanceof Long ? ((Long) value.getValue()).intValue() : value.getValue()));
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 2:
                setTimestamp(((Date) value.getValue()).getTime());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 3:
                setDescription((String) value.getValue());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 4:
                setDataFormat((String) value.getValue());
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            case 5:
                setAppID((value.getValue() instanceof Long ? ((Long) value.getValue()).intValue() : (Integer) value.getValue()));
                fireResourcesChange(resourceId);
                return WriteResponse.success();
            default:
                return super.write(identity, replace, resourceId, value);
        }
    }

    private Integer getAppID() {
        return this.appID;
    }

    private void setAppID(Integer appId) {
        this.appID = appId;
    }

    private void setDataFormat(String value) {
        this.dataFormat = value;
    }

    private String getDataFormat() {
//        return  this.dataFormat == null ? "base64" : this.dataFormat;
        return this.dataFormat == null ? "OPAQUE" : this.dataFormat;
    }

    private void setDescription(String value) {
        this.description = value;
    }

    private String getDescription() {
        return this.description == null ? "meter reading" : this.description;
    }

    private void setTimestamp(long time) {
        this.timestamp = new Time(time);
    }

    private Time getTimestamp() {
        return this.timestamp != null ? this.timestamp : new Time(new Date().getTime());
    }

    //    fireResourcesChange(resourceId);
    private boolean setDataMulti(LwM2mResource value, boolean replace) {
        try {
            if (value instanceof LwM2mMultipleResource) {
                if (replace || this.data == null) {
                    this.data = new HashMap<Integer, byte[]>();
                }
                value.getInstances().values().forEach(v -> {
                    ((Map) this.data).put(v.getId(), v.getValue());
                });
                return true;
            } else {
                this.data = value.getValue();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }


    private void setDataSingle(byte[] value) {
        this.data = value;
    }

    private Object getData() {
        return data;
//        this.data.put(23, new byte[]{0, 0, 2, 3});

    }
    
    private byte[] getDataSingle() {
        return (byte[]) data;
//        this.data.put(23, new byte[]{0, 0, 2, 3});

    }

    private int getPriority() {
        return this.priority;
    }

    private void setPriority(int value) {
        this.priority = value;
    }
}
