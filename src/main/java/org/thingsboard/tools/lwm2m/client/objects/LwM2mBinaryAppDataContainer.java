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
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;
import org.eclipse.leshan.core.util.Hex;

import java.util.*;


@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
public class LwM2mBinaryAppDataContainer extends BaseInstanceEnabler {

    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 4, 5);

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
    private byte[] data = {(byte) 0x0A, (byte) 0x17, (byte) 0x0A, (byte) 0xBC};

    private int priority = 0;

    private Date timestamp = new Date();

    private String description = "meter reading";

    private String dataFormat = "base64";

    private int appID;
    private static final Random RANDOM = new Random();

    public LwM2mBinaryAppDataContainer() {
        Timer timer = new Timer("Data byte");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                fireResourcesChange(0);
            }
//        }, 5000, 5000);
        }, 5000, 5000);
    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceid) {
//        log.info("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        switch (resourceid) {
            case 0:
                setData();
//                log.info("Read on Location resource /[{}]/[{}]/[{}], {}", getModel().id, getId(), resourceid, Hex.encodeHexString(this.data).toLowerCase());
                return ReadResponse.success(resourceid, getData());
            case 1:
                return ReadResponse.success(resourceid, getPriority());
            case 2:
                return ReadResponse.success(resourceid, getTimestamp());
            case 3:
                return ReadResponse.success(resourceid, getDescription());
            case 4:
                return ReadResponse.success(resourceid, getDataFormat());
            case 5:
                return ReadResponse.success(resourceid, getAppID());
            default:
                return super.read(identity, resourceid);
        }
    }


    @Override
    public WriteResponse write(ServerIdentity identity, int resourceid, LwM2mResource value) {
//        log.info("Write on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);

        switch (resourceid) {
            case 0:
                setData((byte[]) value.getValue());
                setTimestamp(new Date());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 1:
                setPriority((Integer) value.getValue());
                setTimestamp(new Date());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 2:
                setTimestamp(new Date());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 3:
                setDescription((String) value.getValue());
                setTimestamp(new Date());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 4:
                setDataFormat((String) value.getValue());
                setTimestamp(new Date());
                fireResourcesChange(resourceid);
                return WriteResponse.success();

            default:
                return super.write(identity, resourceid, value);
        }
    }
//
//    private String getData() {
//        setData();
//        return
//    }

    private void setData() {
        int value = RANDOM.nextInt(100000001);
        this.data = new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }


//    @Override
//    public ExecuteResponse execute(ServerIdentity identity, int resourceid, String params) {
//        return super.execute(identity, resourceid, params);
//    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel model) {
        return supportedResources;
    }
}
