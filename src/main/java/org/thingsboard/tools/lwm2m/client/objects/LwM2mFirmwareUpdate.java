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
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.eclipse.leshan.core.model.ResourceModel.Type.INTEGER;


@Slf4j
@Data
public class LwM2mFirmwareUpdate extends BaseInstanceEnabler {
    private static final Random RANDOM = new Random();
    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    private byte[] packageData;
    private String packageURI = "coaps://example.org/firmware";
    Map<Integer, String> states = new ConcurrentHashMap<>();
    private int state = 0;
    Map<Integer, String> updateResults = new ConcurrentHashMap<>();
    private int updateResult = 0;
    private String pkgName;         // Name of the Firmware Package
    private String pkgVersion;      // Version of the Firmware package
    Map<Integer, String> firmwareUpdateProtocolSupports = new ConcurrentHashMap<>();
    Map<Integer, String>  firmwareUpdateProtocolSupport = new ConcurrentHashMap<>();
    Map<Integer, String>  firmwareUpdateDeliveryMethods = new ConcurrentHashMap<>();
    private int firmwareUpdateDeliveryMethod = 0;

    public LwM2mFirmwareUpdate() {

    }

    public LwM2mFirmwareUpdate(ScheduledExecutorService executorService) {
        try {
            this.init();
            executorService.scheduleWithFixedDelay(() ->
                    fireResourcesChange(3, 7), 5000, 5000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    private void init () {
        this.setStates();
        this.setUpdateResults();
        this.setFirmwareUpdateProtocolSupport(0);
        this.setFirmwareUpdateProtocolSupport(1);
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
            case 3:
                return ReadResponse.success(resourceid, getState());
            case 5:
                return ReadResponse.success(resourceid, getUpdateResult());
                case 6:
                return ReadResponse.success(resourceid, getPkgName());
                case 7:
                return ReadResponse.success(resourceid, getPkgVersion());
                case 8:
                return ReadResponse.success(resourceid, getFirmwareUpdateProtocolSupport(), INTEGER);
            case 9:
                return ReadResponse.success(resourceid, getFirmwareUpdateDeliveryMethod());
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
            default:
                return super.write(identity, resourceid, value);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceid, String params) {
        switch (resourceid) {
            // Update
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

    private void setPackageData(byte[] value) {
        this.packageData = value;
    }

//    private byte[] getPackageData() {
//        int value = RANDOM.nextInt(100000001);
//        this.packageData = new byte[]{
//                (byte) (value >>> 24),
//                (byte) (value >>> 16),
//                (byte) (value >>> 8),
//                (byte) value};
//        return this.packageData;
//    }

    private String getPackageURI() {
        return this.packageURI;
    }

    private void setPackageURI(String value ) {
        this.packageURI = value;
    }

    private void setStates () {
        states.put(0, "Idle");          // (before downloading or after successful updating)
        states.put(1, "Downloading");   // The data sequence is on the way
        states.put(2, "Downloaded");    // device has downloaded the firmware packag
        states.put(3, "Updating");       // When in Downloaded state, and the executable Resource Update is triggered, the state changes to Updating.

    }

    private void setUpdateResults () {
        updateResults.put(0, "Initial value");  // nce the updating process is initiated (Download /Update), this Resource MUST be reset to Initial value.
        updateResults.put(1, "Firmware updated successfully");
        updateResults.put(2, "Not enough flash memory for the new firmware package.");
        updateResults.put(3, "Out of RAM during downloading process");
        updateResults.put(4, "Connection lost during downloading process.");
        updateResults.put(5, "Integrity check failure for new downloaded package.");
        updateResults.put(6, "Unsupported package type.");
        updateResults.put(7, "Invalid URI.");
        updateResults.put(8, "Firmware update failed.");
        /**
         * A LwM2M client indicates the failure to retrieve the firmware image using the URI provided
         * in the Package URI resource by writing the value 9 to the /5/0/5 (Update Result resource)
         * when the URI contained a URI scheme unsupported by the client.
         * Consequently, the LwM2M Client is unable to retrieve the firmware image using
         * the URI provided by the LwM2M Server in the Package URI when it refers to an unsupported protocol.
         */
        updateResults.put(9, "Unsupported protocol.");
    }

    private void setFirmwareUpdateProtocolSupport() {
        // CoAP is the default setting.
        // (as defined in RFC 7252) with the additional support for block-wise transfer.
        this.firmwareUpdateProtocolSupports.put(0, "CoAP");
        // (as defined in RFC 7252) with the additional support for block-wise transfer
        this.firmwareUpdateProtocolSupports.put(1, "CoAPS");
        // (as defined in RFC 7230)
        this.firmwareUpdateProtocolSupports.put(2, "HTTP 1.1");
        // (as defined in RFC 7230)
        this.firmwareUpdateProtocolSupports.put(3, "HTTPS 1.1");
        // (as defined in RFC 8323)
        this.firmwareUpdateProtocolSupports.put(4, "CoAP over TCP");
        // (as defined in RFC 8323)
        this.firmwareUpdateProtocolSupports.put(5, "CoAP over TLS");
    }

    private void setFirmwareUpdateProtocolSupport(Integer protocolSupport) {
        this.firmwareUpdateProtocolSupport.put(protocolSupport, this.firmwareUpdateProtocolSupports.get(protocolSupport));
    }

    /**
     * Both. In this case the LwM2M Server MAY choose the preferred mechanism for conveying
     * the firmware image to the LwM2M Client.
     */
    private void setFirmwareUpdateDeliveryMethods () {
        states.put(0, "Pull only");
        states.put(1, "Push only");
        states.put(2, "Both");
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
