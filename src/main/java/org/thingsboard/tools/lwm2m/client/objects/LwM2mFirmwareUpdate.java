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
import org.apache.commons.lang3.StringUtils;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import static org.eclipse.leshan.core.model.ResourceModel.Type.INTEGER;


@Slf4j
@Data
public class LwM2mFirmwareUpdate extends LwM2mBaseInstanceEnabler {

    private byte[] packageData;
    private String packageURI = "coaps://example.org/firmware";
    private volatile int state;
    private volatile int updateResult;

    private String pkgName = "";         // Name of the Firmware Package
    private volatile String pkgVersion = "";      // Version of the Firmware package
    Map<Integer, Long> firmwareUpdateProtocolSupport = new ConcurrentHashMap<>();
    private int firmwareUpdateDeliveryMethod;
    private ServerIdentity identity;
    private Long timeDelay = 1000L;
    public ScheduledExecutorService executorService;
    // for test
    private volatile int stateAfterUpdate;
    private volatile int updateResultAfterUpdate;

    public LwM2mFirmwareUpdate() {

    }

    public LwM2mFirmwareUpdate(ScheduledExecutorService executorService, Integer id) {
        try {
            this.executorService = executorService;
            if (id != null) this.setId(id);
            this.init();
//            ResourceChangedListener resourceChangedListener = new ResourceChangedListener() {
//                @Override
//                public void resourcesChanged(int... resourceIds) {
//                    log.warn("Listener resourceIds: {}", resourceIds);
//                    Arrays.stream(resourceIds).forEach(i -> {
//                        read(identity, i);
//                    });
//                    if (getState() == 1) {
//                        execute(identity, 2, null);
////                        executorService.schedule(() -> {
////                            setState(2);
////                            setUpdateResult(1);
////                        },timeDelay,  TimeUnit.MILLISECONDS);
//                    }
//                    else if (getState() == 2) {
//                        executorService.schedule(() -> {
//                            setPkgVersion("2.04");
//                            setState(3);
//                        },timeDelay,  TimeUnit.MILLISECONDS);
//                    }
//                    else if (getState() == 3) {
//                        executorService.schedule(() -> {
//                            setUpdateResult(1); // "Firmware updated successfully"
//                            setState(0);        // "Idle
//                        },timeDelay,  TimeUnit.MILLISECONDS);
//                    }
//                }
//            };
//            this.addResourceChangedListener(resourceChangedListener);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    private void init() {
        this.setFirmwareUpdateProtocolSupport(ProtocolSupportFW.COAP.code);
        this.setFirmwareUpdateProtocolSupport(ProtocolSupportFW.COAPS.code);
    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
//        log.info("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        identity = identity != null ? identity : this.identity;
        this.identity = identity;

        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            case 0:
//                log.info("Read on Location resource /[{}]/[{}]/[{}], {}", getModel().id, getId(), resourceid, Hex.encodeHexString(this.data).toLowerCase());
                return ReadResponse.notFound();
            case 1:
                return ReadResponse.success(resourceId, getPackageURI());
            case 3:
                return ReadResponse.success(resourceId, this.getState());
            case 5:
                return ReadResponse.success(resourceId, getUpdateResult());
            case 6:
                return ReadResponse.success(resourceId, getPkgName());
            case 7:
                return ReadResponse.success(resourceId, getPkgVersion());
            case 8:
                return ReadResponse.success(resourceId, getFirmwareUpdateProtocolSupport(), INTEGER);
            case 9:
                return ReadResponse.success(resourceId, getFirmwareUpdateDeliveryMethod());
            default:
                return super.read(identity, resourceId);
        }
    }

    @Override
    public WriteResponse write(ServerIdentity identity, int resourceId, LwM2mResource value) {
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            case 0:
                this.identity = identity;
                return this.setPackageData((byte[]) value.getValue());
            case 1:
                setPackageURI((String) value.getValue(), resourceId);
                return WriteResponse.success();
            default:
                return super.write(identity, resourceId, value);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceId, String params) {
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            // Update
            case 2:
                /**
                 * When in Downloaded state, and the executable Resource Update is triggered,
                 * -the state changes to Updating.
                 * If the Update Resource failed,
                 * -the state returns at Downloaded.
                 * If performing the Update Resource was successful,
                 * -the state changes from Updating to 0 "Idle".
                 */
                if (UpdateResultFw.UPDATE_SUCCESSFULLY.code == this.updateResultAfterUpdate) {
                    this.setState(StateFw.UPDATING.code);                       //  Success
                    this.setUpdateResult(this.updateResultAfterUpdate);         //  Success
                    try {
                        Thread.sleep(timeDelay);
                    } catch (InterruptedException e) {
                        return ExecuteResponse.badRequest(String.format("Firmware update failed during updating. Error: %s.",
                                e.getMessage()));
                    }
                    this.setState(StateFw.IDLE.code);                           //  Success
                    return ExecuteResponse.success();
                } else if (UpdateResultFw.INITIAL.code == this.updateResultAfterUpdate) {
                    this.setState(StateFw.IDLE.code); // resets the Firmware Update State Machine
                    return ExecuteResponse.success();
                } else if (UpdateResultFw.UPDATE_FAILED.code == this.updateResultAfterUpdate) {
                    this.setState(StateFw.DOWNLOADED.code);                     // Fail
                    this.setUpdateResult(this.updateResultAfterUpdate);         //Fail
                    return ExecuteResponse.badRequest(String.format(":Firmware update failed during updating. UpdateResult^ %s.",
                            UpdateResultFw.fromUpdateResultFwByCode(this.getUpdateResult()).type));
                }
        }
        return super.execute(identity, resourceId, params);
    }

    private WriteResponse setPackageData(byte[] value) {
        try {
            this.setState(StateFw.DOWNLOADING.code); // "Downloading"
            try {
                Thread.sleep(timeDelay);
            } catch (InterruptedException e) {
                return WriteResponse.badRequest(String.format("Firmware write failed during downloading. Error: %s.",
                        e.getMessage()));
            }
            return this.downloadedPackage(value);
        } catch (Exception e) {
            return WriteResponse.badRequest(String.format(":Firmware write failed during downloading. Error: %s.",
                    e.getMessage()));
        }
    }

    private void setState(int state) {
//        executorService.schedule(() -> {
        this.state = state;
        fireResourcesChange(3);
//        }, timeDelay, TimeUnit.MILLISECONDS);

    }

    private void setPkgName(String pkgName) {
        this.pkgName = pkgName;
        fireResourcesChange(6);
    }

    private void setPkgVersion(String pkgVersion) {
        this.pkgVersion = pkgVersion;
        fireResourcesChange(7);
    }

    private int getState() {
        return this.state;
    }

    private void setUpdateResult(int updateResult) {
//        executorService.schedule(() -> {
        this.updateResult = updateResult;
        fireResourcesChange(5);
//        }, timeDelay, TimeUnit.MILLISECONDS);
    }

    private int getUpdateResult() {
        return this.updateResult;
    }

    private String getPackageURI() {
        return this.packageURI;
    }

    private void setPackageURI(String value, int resourceId) {
        this.packageURI = value;
        fireResourcesChange(resourceId);
    }

    private void setFirmwareUpdateProtocolSupport(Long protocolSupport) {
        this.firmwareUpdateProtocolSupport.put(this.firmwareUpdateProtocolSupport.size(), protocolSupport);
    }

    private WriteResponse downloadedPackage(byte[] value) {
        String pkg = new String(value);
        log.warn(pkg);
        /**
         * Writing an empty string to Package URI Resource or setting the Package Resource to NULL (‘\0’),
         * - resets the Firmware Update State Machine:
         */
        if (StringUtils.trimToNull(pkg) != null) {
            this.packageData = value;
            int start = pkg.indexOf("pkgVer:") + ("pkgVer:").length();
            int finish = pkg.indexOf("updateResult");
            this.setPkgVersion(pkg.substring(start, finish).trim());
            start = pkg.indexOf("pkgName:") + ("pkgName:").length();
            finish = pkg.indexOf("pkgVer");
            this.setPkgName(pkg.substring(start, finish).trim());
            start = pkg.indexOf("updateResultAfterUpdate:") + ("updateResultAfterUpdate:").length();
            finish = pkg.indexOf("stateAfterUpdate");
            this.updateResultAfterUpdate = (Integer.parseInt(pkg.substring(start, finish).trim()));
            start = pkg.indexOf("stateAfterUpdate:") + ("stateAfterUpdate:").length();
            finish = pkg.length() - 1;
            this.stateAfterUpdate = (Integer.parseInt(pkg.substring(start, finish).trim()));
            /**
             * true:
             *  DOWNLOADING -> DOWNLOADED
             *         INITIAL(0)
             *         UPDATE_SUCCESSFULLY(1),
             *         UPDATE_FAILED(8),
             * false: DOWNLOADING && brake
             *         NOT_ENOUGH(2),
             *         OUT_OFF_RAM(3),
             *         CONNECTION_LOST(4),
             *         INTEGRITY_CHECK_FAILURE(5),
             *         UNSUPPORTED_TYPE(6),
             *         INVALID_URI(7),
             *         UNSUPPORTED_PROTOCOL(9);
             */
            if (UpdateResultFw.UPDATE_SUCCESSFULLY.code >= this.stateAfterUpdate || UpdateResultFw.UPDATE_FAILED.code ==this.stateAfterUpdate) {
                this.setState(StateFw.DOWNLOADED.code); // "Downloaded"
                this.setUpdateResult(UpdateResultFw.INITIAL.code); // "Initial value"
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Firmware write failed during downloaded. Error: %s.",
                            e.getMessage()));
                }
                return WriteResponse.success();
            } else {
                this.setUpdateResult(this.updateResultAfterUpdate);        //  Fail
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Firmware write failed during downloading. Error: %s.",
                            e.getMessage()));
                }
                return WriteResponse.badRequest(String.format("Firmware write failed during downloading. UpdateResult: %s.",
                        UpdateResultFw.fromUpdateResultFwByCode(this.getUpdateResult()).type));
            }
        }
        /**
         * Writing an empty string to Package URI Resource or setting the Package Resource to NULL (‘\0’),
         * - resets the Firmware Update State Machine:
         * -- state = 0 IDLE Idle
         * -- updateResult = 5 integrity check failure for new downloaded package
         */
        else  {
            this.setState(StateFw.IDLE.code);
            this.setUpdateResult(UpdateResultFw.INTEGRITY_CHECK_FAILURE.code);
            try {
                Thread.sleep(timeDelay);
            } catch (InterruptedException e) {
                return WriteResponse.badRequest(String.format("Firmware write failed during downloaded. Error: %s.",
                        e.getMessage()));
            }
            return WriteResponse.badRequest(String.format("Firmware write failed during downloading. UpdateResult: %s.",
                    UpdateResultFw.fromUpdateResultFwByCode(this.getUpdateResult()).type));
        }
    }

    /**
     * /** State R
     * Indicates current state with respect to this firmware update. This value is set by the LwM2M Client.
     * 0: Idle (before downloading or after successful updating)
     * 1: Downloading (The data sequence is on the way)
     * 2: Downloaded
     * 3: Updating
     * If writing the firmware package to Package Resource has completed, or,
     * if the device has downloaded the firmware package from the Package URI
     * - the state changes to Downloaded.
     * Writing an empty string to Package URI Resource or setting the Package Resource to NULL (‘\0’),
     * - resets the Firmware Update State Machine:
     * -- the State Resource value is set to 0 "Idle" and
     * -- the Update Result Resource value is set to 0 "Initial value".
     * When in Downloaded state, and the executable Resource Update is triggered,
     * -the state changes to Updating.
     * If the Update Resource failed,
     * -the state returns at Downloaded.
     * If performing the Update Resource was successful,
     * -the state changes from Updating to 0 "Idle".
     * The firmware update state machine is illustrated in Figure 29 of the LwM2M version 1.0 specification
     * (and also in Figure E.6.1-1 of this specification).
     */
    private enum StateFw {
        IDLE(0, "Idle"),
        DOWNLOADING(1, "Downloading"),
        DOWNLOADED(2, "Downloaded"),
        UPDATING(3, "Updating");

        public int code;
        public String type;

        StateFw(int code, String type) {
            this.code = code;
            this.type = type;
        }

        public static StateFw fromStateFw(String type) {
            for (StateFw to : StateFw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported FW State type  : %s", type));
        }
    }

    /**
     * 0, "Initial value");  // once the updating process is initiated (Download /Update), this Resource MUST be reset to Initial value.
     * 1, "Firmware updated successfully");
     * 2, "Not enough flash memory for the new firmware package.");
     * 3, "Out of RAM during downloading process");
     * 4, "Connection lost during downloading process.");
     * 5, "Integrity check failure for new downloaded package.");
     * 6, "Unsupported package type.");
     * 7, "Invalid URI.");
     * 8, "Firmware update failed.");
     * * A LwM2M client indicates the failure to retrieve the firmware image using the URI provided
     * * in the Package URI resource by writing the value 9 to the /5/0/5 (Update Result resource)
     * * when the URI contained a URI scheme unsupported by the client.
     * * Consequently, the LwM2M Client is unable to retrieve the firmware image using
     * * the URI provided by the LwM2M Server in the Package URI when it refers to an unsupported protocol.
     * 9, "Unsupported protocol.");
     */
    private enum UpdateResultFw {
        INITIAL(0, "Initial value", false),
        UPDATE_SUCCESSFULLY(1, "Firmware updated successfully", false),
        NOT_ENOUGH(2, "Not enough flash memory for the new firmware package", false),
        OUT_OFF_RAM(3, "Out of RAM during downloading process", false),
        CONNECTION_LOST(4, "Connection lost during downloading process", true),
        INTEGRITY_CHECK_FAILURE(5, "Integrity check failure for new downloaded package", true),
        UNSUPPORTED_TYPE(6, "Unsupported package type", false),
        INVALID_URI(7, "Invalid URI", false),
        UPDATE_FAILED(8, "Firmware update failed", false),
        UNSUPPORTED_PROTOCOL(9, "Unsupported protocol", false);

        public int code;
        public String type;
        public boolean isAgain;

        UpdateResultFw(int code, String type, boolean isAgain) {
            this.code = code;
            this.type = type;
            this.isAgain = isAgain;
        }

        public static UpdateResultFw fromUpdateResultFwByType(String type) {
            for (UpdateResultFw to : UpdateResultFw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported FW Update Result type  : %s", type));
        }

        public static UpdateResultFw fromUpdateResultFwByCode(int code) {
            for (UpdateResultFw to : UpdateResultFw.values()) {
                if (to.code == code) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported FW Update Result code  : %s", code));
        }
    }

    /**
     * Both. In this case the LwM2M Server MAY choose the preferred mechanism for conveying
     * the firmware image to the LwM2M Client.
     * 0, "Pull only");
     * 1, "Push only");
     * 2, "Both");
     */
    private enum DeliveryMethodFW {
        PULL_ONLY(0, "Pull only"),
        PUSH_ONLY(1, "Push only"),
        BOTH(2, "Both");

        public int code;
        public String type;

        DeliveryMethodFW(int code, String type) {
            this.code = code;
            this.type = type;
        }

        public static StateFw fromDeliveryMethodFW(String type) {
            for (StateFw to : StateFw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported FW DeliveryMethod type  : %s", type));
        }
    }

    /**
     * This resource indicates what protocols the LwM2M Client implements to retrieve firmware images. The LwM2M server uses this information to decide what URI to include in the Package URI. A LwM2M Server MUST NOT include a URI in the Package URI object that uses a protocol that is unsupported by the LwM2M client.
     * For example, if a LwM2M client indicates that it supports CoAP and CoAPS then a LwM2M Server must not provide an HTTP URI in the Packet URI.
     * The following values are defined by this version of the specification:
     * 0: CoAP (as defined in RFC 7252) with the additional support for block-wise transfer. CoAP is the default setting.
     * 1: CoAPS (as defined in RFC 7252) with the additional support for block-wise transfer
     * 2: HTTP 1.1 (as defined in RFC 7230)
     * 3: HTTPS 1.1 (as defined in RFC 7230)
     * 4: CoAP over TCP (as defined in RFC 8323)
     * 5: CoAP over TLS (as defined in RFC 8323)
     * Additional values MAY be defined in the future. Any value not understood by the LwM2M Server MUST be ignored.
     */
    private enum ProtocolSupportFW {
        COAP(0L, "CoAP"),
        COAPS(1L, "CoAPS"),
        HTTP_1_1(2L, "HTTP 1.1"),
        HTTPS_1_1(3L, "HTTPS 1.1"),
        COAP_OVER_TCP(4L, "CoAP over TCP"),
        COAP_OVER_TLS(5L, "CoAP over TLS");
        public Long code;
        public String type;

        ProtocolSupportFW(Long code, String type) {
            this.code = code;
            this.type = type;
        }

        public static StateFw fromProtocolSupportFW(String type) {
            for (StateFw to : StateFw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported FW ProtocolSupport type  : %s", type));
        }
    }
}
