/**
 * Copyright © 2016-2018 The Thingsboard Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
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
import org.eclipse.leshan.client.resource.ResourceChangedListener;
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
    private static final List<Integer> supportedResources = Arrays.asList(0, 1, 2, 3, 5, 6, 7, 8, 9);

    private byte[] packageData;
    private String packageURI = "coaps://example.org/firmware";
    Map<Integer, String> states = new ConcurrentHashMap<>();
    private volatile int state;
    Map<Integer, String> updateResults = new ConcurrentHashMap<>();
    private volatile int updateResult;
    private String pkgName = "";         // Name of the Firmware Package
    private volatile String pkgVersion = "";      // Version of the Firmware package
    Map<Integer, String> firmwareUpdateProtocolSupports = new ConcurrentHashMap<>();
    Map<Integer, Long> firmwareUpdateProtocolSupport = new ConcurrentHashMap<>();
    Map<Integer, String> firmwareUpdateDeliveryMethods = new ConcurrentHashMap<>();
    private int firmwareUpdateDeliveryMethod;
    private ServerIdentity identity;
    private Long timeDelay = 10000L;
    public ScheduledExecutorService executorService;


    public LwM2mFirmwareUpdate() {

    }

    public LwM2mFirmwareUpdate(ScheduledExecutorService executorService) {
        try {
            this.executorService = executorService;
            this.init();
            ResourceChangedListener resourceChangedListener = new ResourceChangedListener() {
                @Override
                public void resourcesChanged(int... resourceIds) {
                    log.warn("Listener resourceIds: {}", resourceIds);
                    Arrays.stream(resourceIds).forEach(i -> {
                        read(identity, i);
                    });
                    if (getState() == 1) {
                        execute(identity, 2, null);
//                        executorService.schedule(() -> {
//                            setState(2);
//                            setUpdateResult(1);
//                        },timeDelay,  TimeUnit.MILLISECONDS);
                    }
                    else if (getState() == 2) {
                        executorService.schedule(() -> {
                            setPkgVersion("2.07");
                            setState(3);
                        },timeDelay,  TimeUnit.MILLISECONDS);
                    }
                    else if (getState() == 3) {
                        executorService.schedule(() -> {
                            setUpdateResult(1); // "Firmware updated successfully"
                            setState(0);        // "Idle
                        },timeDelay,  TimeUnit.MILLISECONDS);
                    }
                }
            };
            this.addResourceChangedListener(resourceChangedListener);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    private void init() {
        this.setStates();
        this.setUpdateResults();
        this.setFirmwareUpdateProtocolSupports();
        this.setFirmwareUpdateProtocolSupport(0L);
        this.setFirmwareUpdateProtocolSupport(1L);
        this.setFirmwareUpdateDeliveryMethods();

    }

//    @Override
//    public ReadResponse read(ServerIdentity identity) {
//        List<LwM2mResource> resources = new ArrayList<>();
//        for (ResourceModel resourceModel : model.resources.values()) {
//            // check, if internal request (SYSTEM) or readable
//            if (identity.isSystem() || resourceModel.operations.isReadable()) {
//                ReadResponse response = read(identity, resourceModel.id);
//                if (response.isSuccess() && response.getContent() instanceof LwM2mResource)
//                    resources.add((LwM2mResource) response.getContent());
//            }
//        }
//        return ReadResponse.success(new LwM2mObjectInstance(id, resources));
//    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceid) {
//        log.info("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        identity = identity != null ? identity : this.identity;
        this.identity = identity;
        switch (resourceid) {
            case 0:
//                log.info("Read on Location resource /[{}]/[{}]/[{}], {}", getModel().id, getId(), resourceid, Hex.encodeHexString(this.data).toLowerCase());
                return ReadResponse.notFound();
            case 1:
                return ReadResponse.success(resourceid, getPackageURI());
            case 3:
                return ReadResponse.success(resourceid, this.getState());
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
                this.identity = identity;
                setPackageData((byte[]) value.getValue());
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
                if (state == 1) {
                    this.executorService.schedule(() -> {
                        setState(2);        // "Downloaded"
                    },timeDelay,  TimeUnit.MILLISECONDS);
//                    getLwM2mClient().triggerRegistrationUpdate(identity);
                    return ExecuteResponse.success();
                } else {
                    return ExecuteResponse.badRequest("firmware update already running");
                }
        }
        return super.execute(identity, resourceid, params);
    }

    private boolean setPackageData(byte[] value) {
        this.packageData = value;
        fireResourcesChange(0);
        this.setState(1); // "Downloading"
        this.setUpdateResult(0); // "Initial value"
        return true;
    }

    private void setState (int state) {
        this.state = state;
        fireResourcesChange(3);
    }

    private void setPkgVersion (String pkgVersion) {
        this.pkgVersion = pkgVersion;
        fireResourcesChange(7);
    }

    private int getState() {
        return this.state;
    }

    private void setUpdateResult(int updateResult) {
        this.updateResult = updateResult;
        fireResourcesChange(5);
    }

    private int getUpdateResult() {
        return this.updateResult;
    }

    private String getPackageURI() {
        return this.packageURI;
    }

    private void setPackageURI(String value) {
        this.packageURI = value;
    }

    private void setStates() {
        states.put(0, "Idle");          // (before downloading or after successful updating)
        states.put(1, "Downloading");   // The data sequence is on the way
        states.put(2, "Downloaded");    // device has downloaded the firmware packag
        states.put(3, "Updating");       // When in Downloaded state, and the executable Resource Update is triggered, the state changes to Updating.

    }

    private void setUpdateResults() {
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

    private void setFirmwareUpdateProtocolSupports() {
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

    private void setFirmwareUpdateProtocolSupport(Long protocolSupport) {
        this.firmwareUpdateProtocolSupport.put(this.firmwareUpdateProtocolSupport.size(), protocolSupport);
    }

    /**
     * Both. In this case the LwM2M Server MAY choose the preferred mechanism for conveying
     * the firmware image to the LwM2M Client.
     */
    private void setFirmwareUpdateDeliveryMethods() {
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
