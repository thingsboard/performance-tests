/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.lwm2m.client;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.ClientHandshaker;
import org.eclipse.californium.scandium.dtls.DTLSSession;
import org.eclipse.californium.scandium.dtls.HandshakeException;
import org.eclipse.californium.scandium.dtls.Handshaker;
import org.eclipse.californium.scandium.dtls.ResumingClientHandshaker;
import org.eclipse.californium.scandium.dtls.ResumingServerHandshaker;
import org.eclipse.californium.scandium.dtls.ServerHandshaker;
import org.eclipse.californium.scandium.dtls.SessionAdapter;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.engine.DefaultRegistrationEngineFactory;
import org.eclipse.leshan.client.resource.BaseInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.californium.DefaultEndpointFactory;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.thingsboard.tools.lwm2m.client.objects.LwM2MLocationParams;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mBinaryAppDataContainer;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mDevice;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mFirmwareUpdate;
import org.thingsboard.tools.lwm2m.client.objects.LwObjectEnabler;
import org.thingsboard.tools.lwm2m.secure.LwM2MSecurityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static org.eclipse.leshan.core.LwM2mId.DEVICE;
import static org.eclipse.leshan.core.LwM2mId.FIRMWARE;
import static org.eclipse.leshan.core.LwM2mId.SECURITY;
import static org.eclipse.leshan.core.LwM2mId.SERVER;
import static org.eclipse.leshan.core.LwM2mId.SOFTWARE_MANAGEMENT;
import static org.eclipse.leshan.core.request.ContentFormat.TLV;


@Slf4j
//@Configuration("LwM2MClientConfiguration")
//@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientConfiguration {

    private static final int TEMPERATURE_SENSOR = 3303;
    private static final int BINARY_APP_DATA_CONTAINER = 19;
    private String endPoint;
    private int clientPort;
    private int numberClient;
    private LwM2MSecurityMode mode;
    private ScheduledExecutorService executorService;


    private LwM2MClientContext context;
    private LwM2MLocationParams locationParams;

    public void init(LwM2MClientContext context, LwM2MLocationParams locationParams, String endPoint,
                     int portNumber, LwM2MSecurityMode mode, ScheduledExecutorService executorService, int numberClient) {
        this.mode = mode;
        this.context = context;
        this.locationParams = locationParams;
        this.endPoint = endPoint;
        this.clientPort = context.getClientStartPort() + portNumber;
        this.executorService = executorService;
        this.numberClient = numberClient;
    }

    public LeshanClient getLeshanClient() {
        /** Create client */
//        log.info("Starting LwM2M client... PostConstruct. BootstrapEnable: ???");
        /** Initialize model */
//        List<ObjectModel> models = ObjectLoader.loadDefault();
        List<ObjectModel> models = context.getModelsValue();

        /** Initialize object list */
        final LwM2mModel model = new StaticModel(models);
        final ObjectsInitializer initializerModel = new ObjectsInitializer(model);

        /** Endpoint */
//        String subEndpoint = !context.getSubEndpoint().isEmpty() ? context.getSubEndpoint() : LwM2MSecurityMode.fromSecurityMode(context.getDtlsMode()).subEndpoint;
//        String endpoint = !context.getEndpoint().isEmpty() ? context.getEndpoint() + "_" + subEndpoint : "client_default";
        log.info("Start LwM2M client... PostConstruct [{}] [{}] [{}]", this.endPoint, this.mode, this.numberClient);

        /** Initialize security object */
        new LwM2MSecurityStore(context, initializerModel, this.endPoint, this.mode, this.numberClient);

        /** Initialize SingleOne objects */
        // Device (0)
        List<LwM2mObjectEnabler> enablers = new ArrayList<>();
        Map<Integer, LwM2mInstanceEnabler> deviceMapInstances = new HashMap<>();
        LwM2mDevice lwM2mDevice0 = new LwM2mDevice(executorService, 0);
        deviceMapInstances.put(0, lwM2mDevice0);
        LwM2mInstanceEnabler[] deviceInstances = {lwM2mDevice0};
        initializerModel.setInstancesForObject(DEVICE, deviceInstances);
        initializerModel.setClassForObject(DEVICE, LwM2mDevice.class);
        LwM2mInstanceEnablerFactory factoryDevice = new BaseInstanceEnablerFactory() {
            @Override
            public LwM2mInstanceEnabler create() {
                return new LwM2mDevice();
            }
        };
        LwM2mObjectEnabler device = new LwObjectEnabler(DEVICE, models.stream().filter(mod -> mod.id == DEVICE)
                .collect(Collectors.toUnmodifiableList()).get(0), deviceMapInstances, factoryDevice, TLV);

        // FirmwareUpdate (0)
        Map<Integer, LwM2mInstanceEnabler> firmwareUpdateMapInstances = new HashMap<>();
        LwM2mFirmwareUpdate firmwareUpdate0 = new LwM2mFirmwareUpdate(executorService, 0);
        firmwareUpdateMapInstances.put(0, firmwareUpdate0);
        LwM2mInstanceEnabler[] firmwareUpdateInstances = {firmwareUpdate0};
        initializerModel.setInstancesForObject(FIRMWARE, firmwareUpdateInstances);
        initializerModel.setClassForObject(FIRMWARE, LwM2mFirmwareUpdate.class);
        LwM2mInstanceEnablerFactory factoryFirmware = new BaseInstanceEnablerFactory() {
            @Override
            public LwM2mInstanceEnabler create() {
                return new LwM2mFirmwareUpdate();
            }
        };
        LwM2mObjectEnabler firmwareUpdate = new LwObjectEnabler(FIRMWARE, models.stream().filter(mod -> mod.id == FIRMWARE)
                .collect(Collectors.toUnmodifiableList()).get(0), firmwareUpdateMapInstances, factoryFirmware, TLV);

        //  LwM2mSoftwareManagement (0)
        Map<Integer, LwM2mInstanceEnabler>  softwareManagementMapInstances = new HashMap<>();
        LwM2mFirmwareUpdate softwareUpdate0 = new LwM2mFirmwareUpdate(executorService, 0);
        firmwareUpdateMapInstances.put(0, softwareUpdate0);
        LwM2mInstanceEnabler[] softwareUpdateInstances = {firmwareUpdate0};
        initializerModel.setInstancesForObject(SOFTWARE_MANAGEMENT , softwareUpdateInstances);
        initializerModel.setClassForObject(SOFTWARE_MANAGEMENT , LwM2mFirmwareUpdate.class);
        LwM2mInstanceEnablerFactory factorySoftwareManagement = new BaseInstanceEnablerFactory() {
            @Override
            public LwM2mInstanceEnabler create() {
                return new LwM2mFirmwareUpdate();
            }
        };
        LwM2mObjectEnabler softwareUpdate = new LwObjectEnabler(SOFTWARE_MANAGEMENT , models.stream().filter(mod -> mod.id == SOFTWARE_MANAGEMENT )
                .collect(Collectors.toUnmodifiableList()).get(0), softwareManagementMapInstances, factorySoftwareManagement, TLV);

        /** initializeMultiInstanceObjects */
        // BinaryAppDataContainer (0, 1)
        Map<Integer, LwM2mInstanceEnabler> lwM2mBinaryAppDataContainerMapInstances = new HashMap<>();
        LwM2mBinaryAppDataContainer lwM2mBinaryAppDataContainer0 = new LwM2mBinaryAppDataContainer(executorService, 0);
        LwM2mBinaryAppDataContainer lwM2mBinaryAppDataContainer1 = new LwM2mBinaryAppDataContainer(executorService, 1);
        lwM2mBinaryAppDataContainerMapInstances.put(0, lwM2mBinaryAppDataContainer0);
        lwM2mBinaryAppDataContainerMapInstances.put(1, lwM2mBinaryAppDataContainer1);
        LwM2mInstanceEnabler[] lwM2mBinaryAppDataContainerInstances = {lwM2mBinaryAppDataContainer0, lwM2mBinaryAppDataContainer1};
        initializerModel.setInstancesForObject(BINARY_APP_DATA_CONTAINER, lwM2mBinaryAppDataContainerInstances);
        initializerModel.setClassForObject(BINARY_APP_DATA_CONTAINER, LwM2mBinaryAppDataContainer.class);
        LwM2mInstanceEnablerFactory factoryLwM2mBinaryAppDataContainer = new BaseInstanceEnablerFactory() {
            @Override
            public LwM2mInstanceEnabler create() {
                return new LwM2mBinaryAppDataContainer();
            }
        };
        LwM2mObjectEnabler LwM2mBinaryAppDataContainer = new LwObjectEnabler(BINARY_APP_DATA_CONTAINER,
                models.stream().filter(mod -> mod.id == BINARY_APP_DATA_CONTAINER).collect(Collectors.toUnmodifiableList()).get(0),
                lwM2mBinaryAppDataContainerMapInstances, factoryLwM2mBinaryAppDataContainer, TLV);

        LwM2mObjectEnabler security = initializerModel.create(SECURITY);
        LwM2mObjectEnabler server = initializerModel.create(SERVER);
        enablers.add(security);
        enablers.add(server);
        enablers.add(device);
        enablers.add(firmwareUpdate);
        enablers.add(softwareUpdate);
        enablers.add(LwM2mBinaryAppDataContainer);

//        initializer.setInstancesForObject(BINARY_APP_DATA_CONTAINER, new LwM2mBinaryAppDataContainer(executorService));
//        initializer.setInstancesForObject(CONNECTIVITY_MONITORING, new LwM2mConnectivityMonitoring(executorService));
//        initializer.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));
//        initializer.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));
//
//        LwM2mInstanceEnabler[] instances = {new LwM2mTemperatureSensor(executorService), new LwM2mTemperatureSensor(executorService)};
//        initializer.setInstancesForObject(TEMPERATURE_SENSOR, instances);
//        initializer.setInstancesForObject(CONNECTIVITY_STATISTICS, new ConnectivityStatistics());

//        List<LwM2mObjectEnabler> enablers = initializer.createAll();


        /** Create CoAP Config */
        NetworkConfig coapConfig = LwM2mNetworkConfig.getCoapConfig();
        switch (this.mode) {
            case PSK:
            case NO_SEC:
                coapConfig.setString("COAP_PORT", Integer.toString(context.getLwm2mPortNoSec()));
                coapConfig.setString("COAP_SECURE_PORT", Integer.toString(context.getLwm2mPortPSK()));
                break;
            case X509:
                coapConfig.setString("COAP_SECURE_PORT", Integer.toString(context.getLwm2mPortX509()));
                break;
            case RPK:
            default:
                coapConfig.setString("COAP_SECURE_PORT", Integer.toString(context.getLwm2mPortRPK()));
        }


        /** Create DTLS Config */
        DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();

        /** Configure Registration Engine */
        DefaultRegistrationEngineFactory engineFactory = new DefaultRegistrationEngineFactory();
        if (context.getRequestTimeoutInMs() != null) {
            engineFactory.setRequestTimeoutInMs(context.getRequestTimeoutInMs());
        }
        engineFactory.setReconnectOnUpdate(context.getReconnectOnUpdate());
        engineFactory.setResumeOnConnect(!context.getForceFullHandshake());

        /** Configure EndpointFactory */
        DefaultEndpointFactory endpointFactory = new DefaultEndpointFactory(this.endPoint) {
            @Override
            protected Connector createSecuredConnector(DtlsConnectorConfig dtlsConfig) {

                return new DTLSConnector(dtlsConfig) {
                    @Override
                    protected void onInitializeHandshaker(Handshaker handshaker) {
                        handshaker.addSessionListener(new SessionAdapter() {

                            @Override
                            public void handshakeStarted(Handshaker handshaker) throws HandshakeException {
                                if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : STARTED ...");
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by client : STARTED ...");
                                }
                            }

                            @Override
                            public void sessionEstablished(Handshaker handshaker, DTLSSession establishedSession)
                                    throws HandshakeException {
                                if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : SUCCEED, handshaker {}", handshaker);
                                } else if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : SUCCEED, handshaker {}", handshaker);
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : SUCCEED, handshaker {}", handshaker);
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by client : SUCCEED, handshaker {}", handshaker);
                                }
                            }

                            @Override
                            public void handshakeFailed(Handshaker handshaker, Throwable error) {
                                /** get cause */
                                String cause;
                                if (error != null) {
                                    if (error.getMessage() != null) {
                                        cause = error.getMessage();
                                    } else {
                                        cause = error.getClass().getName();
                                    }
                                } else {
                                    cause = "unknown cause";
                                }

                                if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : FAILED [{}]", cause);
                                } else if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : FAILED [{}]", cause);
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : FAILED [{}]", cause);
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by client : FAILED [{}]", cause);
                                }
                            }
                        });
                    }
                };
            }
        };

        /** Create client */
        LeshanClientBuilder builder = new LeshanClientBuilder(this.endPoint);
        builder.setLocalAddress((context.getClientHost().isEmpty()) ? null : context.getClientHost(), this.clientPort);
        builder.setObjects(enablers);
        builder.setCoapConfig(coapConfig);
        builder.setDtlsConfig(dtlsConfig);
        builder.setRegistrationEngineFactory(engineFactory);
        builder.setEndpointFactory(endpointFactory);
        builder.setSharedExecutor(executorService);
        if (context.getSupportOldFormat()) {
            builder.setDecoder(new DefaultLwM2mNodeDecoder(true));
            builder.setEncoder(new DefaultLwM2mNodeEncoder(true));
        }
        builder.setAdditionalAttributes(context.getAddAttributes().isEmpty() ? null : context.getAddAttrs(context.getAddAttributes()));
        return builder.build();
    }

    public void start(Map<String, String> clientAccessConnect) {
        LwM2MClientInitializer clientInitializer = new LwM2MClientInitializer(this.getLeshanClient(), clientAccessConnect);
        LeshanClient client = clientInitializer.init();
        client.start();
    }
//
//    protected LwM2mMultipleResource initializeMultipleResource(int ObjId, int resId) {
//        ObjectModel objectModel = null;
//        ResourceModel resourceModel = null;
//        DummyInstanceEnabler dummyInstanceEnabler = new DummyInstanceEnabler(resId);
//        return initializeMultipleResource(objectModel, resourceModel);
//    }
//
//    protected LwM2mMultipleResource initializeMultipleResource(ObjectModel objectModel, ResourceModel resourceModel) {
//        Map<Integer, Object> values = new HashMap<>();
//        switch (resourceModel.type) {
//            case STRING:
//                values.put(0, createDefaultStringValueFor(objectModel, resourceModel));
//                break;
//            case BOOLEAN:
//                values.put(0, createDefaultBooleanValueFor(objectModel, resourceModel));
//                values.put(1, createDefaultBooleanValueFor(objectModel, resourceModel));
//                break;
//            case INTEGER:
//                values.put(0, createDefaultIntegerValueFor(objectModel, resourceModel));
//                values.put(1, createDefaultIntegerValueFor(objectModel, resourceModel));
//                break;
//            case FLOAT:
//                values.put(0, createDefaultFloatValueFor(objectModel, resourceModel));
//                values.put(1, createDefaultFloatValueFor(objectModel, resourceModel));
//                break;
//            case TIME:
//                values.put(0, createDefaultDateValueFor(objectModel, resourceModel));
//                break;
//            case OPAQUE:
//                values.put(0, createDefaultOpaqueValueFor(objectModel, resourceModel));
//                break;
//            default:
//                // this should not happened
//                values = null;
//                break;
//        }
//        if (values != null)
//            return LwM2mMultipleResource.newResource(resourceModel.id, values, resourceModel.type);
//        else
//            return null;
//    }


}
