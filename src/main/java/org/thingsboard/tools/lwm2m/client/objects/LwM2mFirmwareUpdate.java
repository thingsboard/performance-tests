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
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.sql.Time;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
public class LwM2mFirmwareUpdate extends BaseInstanceEnabler {
    private static final Random RANDOM = new Random();
    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

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
//
//    private Time timestamp;
//
//    private String description;
//
//    private String dataFormat;
//
//    private Integer appID;

    /**
     * 			<Item ID="0">
     * 				<Name>Package</Name>
     *         <Operations>W</Operations>
     *         <MultipleInstances>Single</MultipleInstances>
     * 				<Mandatory>Mandatory</Mandatory>
     * 				<Type>Opaque</Type>
     */
    private byte[] packageData;

    /**
     <Item ID="1">
     <Name>Package URI</Name>
     <Operations>RW</Operations>
     <MultipleInstances>Single</MultipleInstances>
     <Mandatory>Mandatory</Mandatory>
     <Type>String</Type>
     <RangeEnumeration>0..255</RangeEnumeration>
     <Units></Units>
     <Description><![CDATA[URI from where the device can download the firmware package by an alternative mechanism. As soon the device has received the Package URI it performs the download at the next practical opportunity.
     The URI format is defined in RFC 3986. For example, coaps://example.org/firmware is a syntactically valid URI. The URI scheme determines the protocol to be used. For CoAP this endpoint MAY be a LwM2M Server but does not necessarily need to be. A CoAP server implementing block-wise transfer is sufficient as a server hosting a firmware repository and the expectation is that this server merely serves as a separate file server making firmware images available to LwM2M Clients.]]></Description>
     </Item>
     */
    private String packageURI = "coaps://example.org/firmware";

    /**
     <Item ID="2">
     <Name>Update</Name>
     <Operations>E</Operations>
     <MultipleInstances>Single</MultipleInstances>
     <Mandatory>Mandatory</Mandatory>
     <Type></Type>
     <RangeEnumeration></RangeEnumeration>
     <Units></Units>
     <Description><![CDATA[Updates firmware by using the firmware package stored in Package, or, by using the firmware downloaded from the Package URI.
     This Resource is only executable when the value of the State Resource is Downloaded.]]></Description>
     </Item>
     */
    private Time update;
    public LwM2mFirmwareUpdate() {

    }

    public LwM2mFirmwareUpdate(ScheduledExecutorService executorService) {
        try {
            executorService.scheduleWithFixedDelay(() ->
                    fireResourcesChange(0, 2), 5000, 5000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceid) {
//        log.info("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        switch (resourceid) {
            case 0:
//                log.info("Read on Location resource /[{}]/[{}]/[{}], {}", getModel().id, getId(), resourceid, Hex.encodeHexString(this.data).toLowerCase());
                return ReadResponse.notFound();
            case 1:
                return ReadResponse.success(resourceid, getPackageURI());
            case 2:
                return ReadResponse.success(resourceid, getUpdate());
//            case 3:
//                return ReadResponse.success(resourceid, getDescription());
//            case 4:
//                return ReadResponse.success(resourceid, getDataFormat());
//            case 5:
//                return ReadResponse.success(resourceid, getAppID());
            default:
                return super.read(identity, resourceid);
        }
    }

    @Override
    public WriteResponse write(ServerIdentity identity, int resourceid, LwM2mResource value) {
//        log.info("Write on Device resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);

        switch (resourceid) {
            case 0:
                setPackageData((byte[]) value.getValue());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 1:
                setPackageURI((String) value.getValue());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
            case 2:
                setUpdate(((Date) value.getValue()).getTime());
                fireResourcesChange(resourceid);
                return WriteResponse.success();
//            case 3:
//                setDescription((String) value.getValue());
//                fireResourcesChange(resourceid);
//                return WriteResponse.success();
//            case 4:
//                setDataFormat((String) value.getValue());
//                fireResourcesChange(resourceid);
//                return WriteResponse.success();
//                case 5:
//                setAppID((Integer) value.getValue());
//                fireResourcesChange(resourceid);
//                return WriteResponse.success();

            default:
                return super.write(identity, resourceid, value);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceid, String params) {
        switch (resourceid) {
            case 2:
//                if (data->state == 1)
//                {
//                    fprintf(stdout, "\n\t FIRMWARE UPDATE\r\n\n");
//                    // trigger your firmware download and update logic
//                    data->state = 2;
//                    return COAP_204_CHANGED;
//                }
//                else
//                {
//                    // firmware update already running
//                    return COAP_400_BAD_REQUEST;
//                }
                getLwM2mClient().triggerRegistrationUpdate(identity);
                return ExecuteResponse.success();
        }
        return super.execute(identity, resourceid, params);
    }

//    private Integer getAppID() {
//        return this.appID;
//    }
//
//    private void setAppID(Integer appId) {
//        this.appID = appId;
//    }
//
//    private void setDataFormat(String value) {
//        this.dataFormat = value;
//    }
//
//    private String getDataFormat() {
////        return  this.dataFormat == null ? "base64" : this.dataFormat;
//        return  this.dataFormat == null ? "OPAQUE" : this.dataFormat;
//    }
//
//    private void setDescription(String value) {
//        this.description = value;
//    }
//
//    private String getDescription() {
//        return  this.description == null ? "meter reading" : this.description;
//    }

    private void setUpdate(long time) {
        this.update = new Time(time);
    }

    private Time getUpdate() {
        return this.update != null ? this.update : new Time (new Date().getTime());
    }

    private void setPackageData(byte[] value) {
        this.packageData = value;
    }

    private byte[] getPackageData() {
        int value = RANDOM.nextInt(100000001);
        this.packageData = new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
        return this.packageData;
    }

    private String getPackageURI() {
        return this.packageURI;
    }

    private void setPackageURI(String value ) {
        this.packageURI = value;
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
