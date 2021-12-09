/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
import org.eclipse.californium.scandium.dtls.SessionId;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.engine.DefaultRegistrationEngineFactory;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.californium.DefaultEndpointFactory;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mEncoder;
import org.thingsboard.tools.lwm2m.client.objects.ConnectivityStatistics;
import org.thingsboard.tools.lwm2m.client.objects.LwM2MLocationParams;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mBinaryAppDataContainer;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mConnectivityMonitoring;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mDevice;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mFirmwareUpdate;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mLocation;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mSoftwareManagement;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mTemperatureSensor;
import org.thingsboard.tools.lwm2m.secure.LwM2MSecurityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static org.eclipse.leshan.core.LwM2mId.ACCESS_CONTROL;
import static org.eclipse.leshan.core.LwM2mId.CONNECTIVITY_MONITORING;
import static org.eclipse.leshan.core.LwM2mId.CONNECTIVITY_STATISTICS;
import static org.eclipse.leshan.core.LwM2mId.DEVICE;
import static org.eclipse.leshan.core.LwM2mId.FIRMWARE;
import static org.eclipse.leshan.core.LwM2mId.LOCATION;
import static org.eclipse.leshan.core.LwM2mId.SERVER;
import static org.eclipse.leshan.core.LwM2mId.SOFTWARE_MANAGEMENT;


@Slf4j
//@Configuration("LwM2MClientConfiguration")
//@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientConfiguration {

    private static final int TEMPERATURE_SENSOR = 3303;
    private static final int BINARY_APP_DATA_CONTAINER = 19;
    Map<Integer, String> versions = new HashMap();

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
        versions.put(SERVER, "1.1");
        versions.put(ACCESS_CONTROL, "1.0");
        versions.put(DEVICE, "1.0");
        versions.put(FIRMWARE, "1.0");
        versions.put(SOFTWARE_MANAGEMENT, "1.0");
        versions.put(BINARY_APP_DATA_CONTAINER, "1.0");
        versions.put(TEMPERATURE_SENSOR, "1.2");



//        List<ObjectModel> models = ObjectLoader.loadDefault();
        List<ObjectModel> modelsAll = context.getModelsValue();
        Map<Integer, ObjectModel> objects = new HashMap();
        for (ObjectModel model : modelsAll) {
            ObjectModel old = objects.put(model.id, model);
            if (old != null && !old.equals(model) && versions.containsKey(old.id)) {
                String versionId = versions.get(old.id);
                if (versionId != null && !versionId.isEmpty()) {
                    List<ObjectModel> objectModel = modelsAll.stream().filter(mod -> mod.id == old.id && mod.version.equals(versionId))
                            .collect(Collectors.toUnmodifiableList());

                    if (objectModel.size()>0) {
                        objects.put(old.id, objectModel.get(0));
                    }
                }
            }
        }
        List<ObjectModel> models = new ArrayList<ObjectModel>(objects.values());

        /** Initialize object list */
        final LwM2mModel model = new StaticModel(models);
        final ObjectsInitializer initializerModel = new ObjectsInitializer(model);

        /** Endpoint */
//        String subEndpoint = !context.getSubEndpoint().isEmpty() ? context.getSubEndpoint() : LwM2MSecurityMode.fromSecurityMode(context.getDtlsMode()).subEndpoint;
//        String endpoint = !context.getEndpoint().isEmpty() ? context.getEndpoint() + "_" + subEndpoint : "client_default";
        log.info("Start LwM2M client... PostConstruct [{}] [{}] [{}]", this.endPoint, this.mode, this.numberClient);

        /** Initialize security object */
        //
        List<ObjectModel> objectModels  = models.stream().filter(mod -> mod.id == SERVER)
                .collect(Collectors.toUnmodifiableList());
        new LwM2MSecurityStore(context, initializerModel, this.endPoint, this.mode, this.numberClient, objectModels);

        /** Initialize SingleOne objects */
//        List<LwM2mObjectEnabler> enablers = new ArrayList<>();
        // Device (0)
        objectModels = models.stream().filter(mod -> mod.id == DEVICE)
                .collect(Collectors.toUnmodifiableList());
        if (objectModels.size() > 0) {
            initializerModel.setInstancesForObject(DEVICE, new LwM2mDevice(executorService, 0));
        }
        // FirmwareUpdate (0)
        objectModels = models.stream().filter(mod -> mod.id == FIRMWARE )
                .collect(Collectors.toUnmodifiableList());
        if (objectModels.size() > 0) {
            LwM2mFirmwareUpdate firmwareUpdate0 = new LwM2mFirmwareUpdate(executorService, 0);
            initializerModel.setInstancesForObject(FIRMWARE, firmwareUpdate0);
        }
        //  LwM2mSoftwareManagement (0)
        objectModels = models.stream().filter(mod -> mod.id == SOFTWARE_MANAGEMENT)
                .collect(Collectors.toUnmodifiableList());
        if (objectModels.size() > 0) {
            LwM2mSoftwareManagement softwareUpdate0 = new LwM2mSoftwareManagement(executorService, 0);
            initializerModel.setInstancesForObject(SOFTWARE_MANAGEMENT, softwareUpdate0);
        }
        /** initializeMultiInstanceObjects */
        // BinaryAppDataContainer (0, 1)
        objectModels = models.stream().filter(mod -> mod.id == BINARY_APP_DATA_CONTAINER)
                .collect(Collectors.toUnmodifiableList());
        if (objectModels.size() > 0) {
            initializerModel.setClassForObject(BINARY_APP_DATA_CONTAINER, LwM2mBinaryAppDataContainer.class);
            boolean dataSingle = !objectModels.get(0).resources.get(0).multiple;
            LwM2mBinaryAppDataContainer lwM2mBinaryAppDataContainer0 = new LwM2mBinaryAppDataContainer(executorService, 0, dataSingle);
            LwM2mBinaryAppDataContainer lwM2mBinaryAppDataContainer1 = new LwM2mBinaryAppDataContainer(executorService, 1, dataSingle);
            LwM2mInstanceEnabler[] instances = new LwM2mInstanceEnabler[]{lwM2mBinaryAppDataContainer0, lwM2mBinaryAppDataContainer1};
            initializerModel.setInstancesForObject(BINARY_APP_DATA_CONTAINER, instances);
        }

        initializerModel.setClassForObject(CONNECTIVITY_MONITORING, LwM2mConnectivityMonitoring.class);
        initializerModel.setInstancesForObject(CONNECTIVITY_MONITORING, new LwM2mConnectivityMonitoring());
        initializerModel.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));
        initializerModel.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));

        LwM2mInstanceEnabler[] instances = {new LwM2mTemperatureSensor(executorService, 0), new LwM2mTemperatureSensor(executorService, 1)};
        initializerModel.setInstancesForObject(TEMPERATURE_SENSOR, instances);
        initializerModel.setInstancesForObject(CONNECTIVITY_STATISTICS, new ConnectivityStatistics());
//
        List<LwM2mObjectEnabler> enablers = initializerModel.createAll();


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
        dtlsConfig.setRecommendedCipherSuitesOnly(true);
        dtlsConfig.setClientOnly();

        /** Configure Registration Engine */
        DefaultRegistrationEngineFactory engineFactory = new DefaultRegistrationEngineFactory();
        if (context.getRequestTimeoutInMs() != null) {
            engineFactory.setRequestTimeoutInMs(context.getRequestTimeoutInMs());
        }
        engineFactory.setReconnectOnUpdate(context.getReconnectOnUpdate());
        engineFactory.setResumeOnConnect(!context.getForceFullHandshake());

        /** Configure EndpointFactory */
//        DefaultEndpointFactory endpointFactory = new DefaultEndpointFactory(this.endPoint) {
        DefaultEndpointFactory endpointFactory = new DefaultEndpointFactory(this.endPoint, true) {
            @Override
            protected Connector createSecuredConnector(DtlsConnectorConfig dtlsConfig) {

                return new DTLSConnector(dtlsConfig) {
                    @Override
                    protected void onInitializeHandshaker(Handshaker handshaker) {
                        handshaker.addSessionListener(new SessionAdapter() {

                            private SessionId sessionIdentifier = null;

                            @Override
                            public void handshakeStarted(Handshaker handshaker) throws HandshakeException {
                                if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    sessionIdentifier = handshaker.getSession().getSessionIdentifier();
                                    log.info("DTLS abbreviated Handshake initiated by client : STARTED ...");
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : STARTED ...");
                                }
                            }

                            @Override
                            public void sessionEstablished(Handshaker handshaker, DTLSSession establishedSession)
                                    throws HandshakeException {
                                if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : SUCCEED");
                                } else if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : SUCCEED");
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    if (sessionIdentifier != null && sessionIdentifier
                                            .equals(handshaker.getSession().getSessionIdentifier())) {
                                        log.info("DTLS abbreviated Handshake initiated by client : SUCCEED");
                                    } else {
                                        log.info(
                                                "DTLS abbreviated turns into Full Handshake initiated by client : SUCCEED");
                                    }
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : SUCCEED");
                                }
                            }

                            @Override
                            public void handshakeFailed(Handshaker handshaker, Throwable error) {
                                // get cause
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

                                if (handshaker instanceof ResumingServerHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by server : FAILED ({})", cause);
                                } else if (handshaker instanceof ServerHandshaker) {
                                    log.info("DTLS Full Handshake initiated by server : FAILED ({})", cause);
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    log.info("DTLS abbreviated Handshake initiated by client : FAILED ({})", cause);
                                } else if (handshaker instanceof ClientHandshaker) {
                                    log.info("DTLS Full Handshake initiated by client : FAILED ({})", cause);
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
            builder.setDecoder(new DefaultLwM2mDecoder(true));
            builder.setEncoder(new DefaultLwM2mEncoder(true));
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
