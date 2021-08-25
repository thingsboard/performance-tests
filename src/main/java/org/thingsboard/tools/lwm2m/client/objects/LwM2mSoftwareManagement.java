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
import org.eclipse.leshan.core.node.ObjectLink;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
@Data
public class LwM2mSoftwareManagement extends LwM2mBaseInstanceEnabler {

    private byte[] packageData;
    private String packageURI = "coaps://example.org/software";
    private volatile int updateState;
    private volatile int updateResult;
    private ObjectLink checkpoint = new ObjectLink();

    /**
     * If this value is true, the LwM2M Client MUST inform the registered LwM2M Servers of Objects and
     * Object Instances parameter by sending an Update or Registration message
     * after the software update operation at the next practical opportunity if supported Objects in the LwM2M Client have changed,
     * in order for the LwM2M Servers to promptly manage newly installed Objects.
     * If false, Objects and Object Instances parameter MUST be reported at the next periodic Update message.
     * The default value is false.
     */
    private volatile boolean updateSupportedObjects = false;
    /**
     * Indicates the current activation state of this software:
     * 0: DISABLED
     * Activation State is DISABLED if the Software Activation State Machine is in the INACTIVE state or not alive.
     * 1: ENABLED
     * Activation State is ENABLED only if the Software Activation State Machine is in the ACTIVE state
     */
    private volatile boolean activationState = false;
    /**
     * Link to "Package Settings" object which allows to modify at any time software configuration settings.
     * This is an application specific object.
     * Note: OMA might provide a template for a Package Settings object in a future release of this specification.
     */
    private volatile ObjectLink packageSettings = new ObjectLink();
    /**
     * User Name for access to SW Update Package in pull mode.
     * Key based mechanism can alternatively use for talking to the component server instead of user name and password combination.
     */
    private volatile String userName;
    /**
     * Password for access to SW Update Package in pull mode.
     */
    private volatile String password;
    /**
     * Contains the status of the actions done by the client on the SW Component(s) referred by the present SW Update Package.
     * The status is defined in Appendix B.
     */
    private String statusReason = "";
    /**
     * Reference to SW Components downloaded and installed in scope of the present SW Update Package Note:
     * When resource 17 objlink exist, resources 2, 3 and 12 in this table are ignored.
     */
    private ObjectLink softwareComponentLink = new ObjectLink();
    /**
     * Software Component tree length indicates the number of instances existing for this software package in the Software Component Object.
     */
    private int softwareComponentTreeLength;

    private volatile String pkgName = "";         // Name of the Software Package
    private volatile String pkgVersion = "";      // Version of the Software package
    private ServerIdentity identity;
    private Long timeDelay = 1000L;
    public ScheduledExecutorService executorService;
    // for test
    private volatile int stateAfterUpdate;
    private volatile int updateResultAfterUpdate;

    public LwM2mSoftwareManagement() {

    }

    public LwM2mSoftwareManagement(ScheduledExecutorService executorService, Integer id) {
        try {
            this.executorService = executorService;
            if (id != null) this.setId(id);
            this.init();
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    private void init() {

    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
//        log.info("Read on Location resource /[{}]/[{}]/[{}]", getModel().id, getId(), resourceid);
        identity = identity != null ? identity : this.identity;
        this.identity = identity;
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            case 0:
                return ReadResponse.success(resourceId, getPkgName());
            case 1:
                return ReadResponse.success(resourceId, getPkgVersion());

//                return ReadResponse.success(resourceId, getPackageURI());
//            case 3:
//                return ReadResponse.success(resourceId, this.getState());
            /**
             * [Link to a "Checkpoint" object which allows to specify conditions/dependencies for a software update.
             * E.g. power connected, sufficient memory, target system.
             */
            case 5:
                return ReadResponse.success(resourceId, getCheckpoint());
            case 7:
                return ReadResponse.success(resourceId, getUpdateState());
            case 8:
                return ReadResponse.success(resourceId, this.isUpdateSupportedObjects());
            case 9:
                return ReadResponse.success(resourceId, getUpdateResult());
            case 12:
                return ReadResponse.success(resourceId, this.isActivationState());
            case 13:
                return ReadResponse.success(resourceId, this.getPackageSettings());
            case 16:
                return ReadResponse.success(resourceId, this.getStatusReason());
            case 17:
                return ReadResponse.success(resourceId, this.getSoftwareComponentLink());
            case 18:
                return ReadResponse.success(resourceId, this.getSoftwareComponentTreeLength());
            default:
                return super.read(identity, resourceId);
        }
    }


    @Override
    public WriteResponse write(ServerIdentity identity, boolean replace, int resourceId, LwM2mResource value) {
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            case 2:
                this.identity = identity;
                return this.setPackageData((byte[]) value.getValue());
            case 3:
                setPackageURI((String) value.getValue(), resourceId);
                return WriteResponse.success();
            case 8:
                setUpdateSupportedObjects((boolean) value.getValue());
                return WriteResponse.success();
            case 13:
                setPackageSettings((ObjectLink) value.getValue());
                return WriteResponse.success();
            case 14:
                setUserName((String) value.getValue());
                return WriteResponse.success();
            case 15:
                setPassword((String) value.getValue());
                return WriteResponse.success();
            default:
                return super.write(identity, replace, resourceId, value);
        }
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceId, String params) {
        resourceId = getSupportedResource(resourceId);
        switch (resourceId) {
            // Install
            case 4:
                /**
                 * Installs software from the package either stored in Package resource, or, downloaded from the Package URI.
                 * This Resource is only executable when the value of the State Resource is DELIVERED.
                 */
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                this.setUpdateResult(this.updateResultAfterUpdate);        //  Success/Fail
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return ExecuteResponse.badRequest(String.format("Software write failed during updating. Error: %s.",
                            e.getMessage()));
                }
                    /**
                     * true
                     * Contains the result of downloading or installing/uninstalling the software
                     * 0: Initial value.
                     * - Prior to download any new package in the Device, Update Result MUST be reset to this initial value.
                     * - One side effect of executing the Uninstall resource is to reset Update Result to this initial value "0".
                     * 2: Software successfully installed.
                     * (( 4-49, for expansion, of other scenarios))
                     * fail
                     * 57: Device defined update error
                     * 58: Software installation failure
                     * 59: Uninstallation Failure during for Update(arg=0)
                     * 60-200 : (for expansion, selection to be in blocks depending on new introduction of features)
                     * This Resource MAY be reported by sending Observe operation.
                     */
                    if (UpdateResultSw.NOT_ENOUGH_STORAGE.code > this.getUpdateResult()) {
                        this.setUpdateState (UpdateStateSw.INSTALLED.code);
                        try {
                            Thread.sleep(timeDelay);
                        } catch (InterruptedException e) {
                            return ExecuteResponse.badRequest(String.format("Software write failed during updating. Error: %s.",
                                    e.getMessage()));
                        }
                        this.setUpdateState (UpdateStateSw.INITIAL.code);
                    } else {    // UpdateState == DELEVERED
                        return ExecuteResponse.badRequest(String.format(":Software update failed during updating. %s.",
                                UpdateResultSw.fromUpdateResultSwByCode(this.getUpdateResult()).type));
                    }
                    return ExecuteResponse.success();
                //Uninstall
                /**
                 * Uninstalls the software package.
                 * This executable resource may have one argument.
                 * If used with no argument or argument is 0,
                 * the Package is removed i from the Device.
                 * If the argument is 1 ("ForUpdate"),
                 * the Client MUST prepare itself for receiving a Package used to upgrade the Software already in place.
                 * Update State is set back to INITIAL state.
                 */
            case 6:
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                executorService.schedule(() -> {
                    // Activate
                    return ExecuteResponse.success();
                }, timeDelay, TimeUnit.MILLISECONDS);
                //
                /**
                 * This action activates the software previously successfully installed
                 * (the Package Installation State Machine is currently in the INSTALLED state)
                 */
            case 10:
                break;
            //Deactivate
            /**
             * This action deactivates software if the Package Installation State Machine is currently in the INSTALLED state.
             */
            case 11:
                break;
        }
        return super.execute(identity, resourceId, params);
    }

    private WriteResponse setPackageData(byte[] value) {
        this.setUpdateState(UpdateStateSw.DOWNLOAD_STARTED.code); // "DOWNLOAD_STARTED"
        try {
            Thread.sleep(timeDelay);
        } catch (InterruptedException e) {
            return WriteResponse.badRequest(String.format("Software write failed during downloading. Error: %s.",
                    e.getMessage()));
        }
        this.setUpdateResult(UpdateResultSw.DOWNLOADING.code); // "DOWNLOADING"
        return this.downloadedPackage(value);
    }

    private void setUpdateState(int updateState) {
//        executorService.schedule(() -> {
        this.updateState = updateState;
        fireResourcesChange(7);
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

    private int getUpdateState() {
        return this.updateState;
    }

    private void setUpdateResult(int updateResult) {
//        executorService.schedule(() -> {
        this.updateResult = updateResult;
        fireResourcesChange(9);
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

    private WriteResponse downloadedPackage(byte[] value) {
        String pkg = new String(value);
        log.warn(pkg);
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
            this.setUpdateState(UpdateStateSw.DOWNLOAD_STARTED.code); // "DOWNLOAD STARTED"
            this.setUpdateResult(UpdateResultSw.DOWNLOADING.code);
            try {
                Thread.sleep(timeDelay);
            } catch (InterruptedException e) {
                return WriteResponse.badRequest(String.format("Software write failed during downloading. Error: %s.",
                        e.getMessage()));
            }
            /**
             * false
             * 50: Not enough storage for the new software package.
             * 51: Out of memory during downloading process.
             * 52: Connection lost during downloading process.
             * 54: Unsupported package type.
             * 56: Invalid URI
             */
            if (UpdateResultSw.NOT_ENOUGH_STORAGE.code == this.updateResultAfterUpdate
                    || UpdateResultSw.OUT_OFF_MEMORY.code == this.updateResultAfterUpdate
                    || UpdateResultSw.CONNECTION_LOST.code == this.updateResultAfterUpdate
                    || UpdateResultSw.UNSUPPORTED_PACKAGE_TYPE.code == this.updateResultAfterUpdate
                    || UpdateResultSw.INVALID_URI.code == this.updateResultAfterUpdate) {
                this.setUpdateResult(this.updateResultAfterUpdate); // "Failed"
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed during downloading. Error: %s.",
                            e.getMessage()));
                }
                return WriteResponse.badRequest(String.format("Software write failed during downloading. UpdateResult: %s.",
                        UpdateResultSw.fromUpdateResultSwByCode(this.getUpdateResult()).type));
            } else {
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed during downloading. Error: %s.",
                            e.getMessage()));
                }
                this.setUpdateState(UpdateStateSw.DOWNLOADED.code);
            }

            /**
             * true
             * 3: Successfully Downloaded and package integrity verified
             * (( 4-49, for expansion, of other scenarios))
             * ** Failed
             * 53: Package integrity check failure.
             */

            if (UpdateResultSw.PACKAGE_CHECK_FAILURE.code == this.updateResultAfterUpdate) {
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed after downloaded. Error: %s.",
                            e.getMessage()));
                }
                this.setUpdateResult(this.updateResultAfterUpdate);        //  Success/Fail
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed after downloaded. Error: %s.",
                            e.getMessage()));
                }
                return WriteResponse.badRequest(String.format("Software write failed after downloaded. UpdateResult: %s.",
                        UpdateResultSw.fromUpdateResultSwByCode(this.getUpdateResult()).type));
            } else {
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed after downloaded. Error: %s.",
                            e.getMessage()));
                }
                this.setUpdateState(UpdateStateSw.DELIVERED.code);
                this.setUpdateResult(UpdateResultSw.SUCCESSFULLY_DOWNLOADED_VERIFIED.code);
                try {
                    Thread.sleep(timeDelay);
                } catch (InterruptedException e) {
                    return WriteResponse.badRequest(String.format("Software write failed after downloaded. Error: %s.",
                            e.getMessage()));
                }
                return WriteResponse.success();
            }
        }
        else {
            return WriteResponse.badRequest(String.format("Firmware write failed during downloading. UpdateResult: %s.",
                    UpdateResultSw.fromUpdateResultSwByCode(this.getUpdateResult()).type));
        }
    }

    /**
     * SW Update State R
     * 0: INITIAL Before downloading. (see 5.1.2.1)
     * 1: DOWNLOAD STARTED The downloading process has started and is on-going. (see 5.1.2.2)
     * 2: DOWNLOADED The package has been completely downloaded  (see 5.1.2.3)
     * 3: DELIVERED In that state, the package has been correctly downloaded and is ready to be installed.  (see 5.1.2.4)
     * If executing the Install Resource failed, the state remains at DELIVERED.
     * If executing the Install Resource was successful, the state changes from DELIVERED to INSTALLED.
     * After executing the UnInstall Resource, the state changes to INITIAL.
     * 4: INSTALLED
     */
    public enum UpdateStateSw {
        INITIAL(0, "Initial"),
        DOWNLOAD_STARTED(1, "DownloadStarted"),
        DOWNLOADED(2, "Downloaded"),
        DELIVERED(3, "Delivered"),
        INSTALLED(4, "Installed");

        public int code;
        public String type;

        UpdateStateSw(int code, String type) {
            this.code = code;
            this.type = type;
        }

        public static UpdateStateSw fromUpdateStateSwByType(String type) {
            for (UpdateStateSw to : UpdateStateSw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported SW State type  : %s", type));
        }

        public static UpdateStateSw fromUpdateStateSwByCode(int code) {
            for (UpdateStateSw to : UpdateStateSw.values()) {
                if (to.code == code) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported SW State type  : %s", code));
        }
    }

    /**
     * SW Update Result
     * Contains the result of downloading or installing/uninstalling the software
     * 0: Initial value.
     * - Prior to download any new package in the Device, Update Result MUST be reset to this initial value.
     * - One side effect of executing the Uninstall resource is to reset Update Result to this initial value "0".
     * 1: Downloading.
     * - The package downloading process is on-going.
     * 2: Software successfully installed.
     * 3: Successfully Downloaded and package integrity verified
     * (( 4-49, for expansion, of other scenarios))
     * ** Failed
     * 50: Not enough storage for the new software package.
     * 51: Out of memory during downloading process.
     * 52: Connection lost during downloading process.
     * 53: Package integrity check failure.
     * 54: Unsupported package type.
     * 56: Invalid URI
     * 57: Device defined update error
     * 58: Software installation failure
     * 59: Uninstallation Failure during for Update (arg=0)
     * 60-200 : (for expansion, selection to be in blocks depending on new introduction of features)
     * This Resource MAY be reported by sending Observe operation.
     */
    public enum UpdateResultSw {
        INITIAL(0, "Initial value", false),
        DOWNLOADING(1, "Downloading", false),
        SUCCESSFULLY_INSTALLED(2, "Software successfully installed", false),
        SUCCESSFULLY_DOWNLOADED_VERIFIED(3, "Successfully Downloaded and package integrity verified", false),
        NOT_ENOUGH_STORAGE(50, "Not enough storage for the new software package", true),
        OUT_OFF_MEMORY(51, "Out of memory during downloading process", true),
        CONNECTION_LOST(52, "Connection lost during downloading process", false),
        PACKAGE_CHECK_FAILURE(53, "Package integrity check failure.", false),
        UNSUPPORTED_PACKAGE_TYPE(54, "Unsupported package type", false),
        INVALID_URI(56, "Invalid URI", true),
        UPDATE_ERROR(57, "Device defined update error", true),
        INSTALL_FAILURE(58, "Software installation failure", true),
        UN_INSTALL_FAILURE(59, "Uninstallation Failure during forUpdate(arg=0)", true);

        public int code;
        public String type;
        public boolean isAgain;

        UpdateResultSw(int code, String type, boolean isAgain) {
            this.code = code;
            this.type = type;
            this.isAgain = isAgain;
        }

        public static UpdateResultSw fromUpdateResultSwByType(String type) {
            for (UpdateResultSw to : UpdateResultSw.values()) {
                if (to.type.equals(type)) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported SW Update Result type  : %s", type));
        }

        public static UpdateResultSw fromUpdateResultSwByCode(int code) {
            for (UpdateResultSw to : UpdateResultSw.values()) {
                if (to.code == code) {
                    return to;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupported SW Update Result code  : %s", code));
        }
    }

}
