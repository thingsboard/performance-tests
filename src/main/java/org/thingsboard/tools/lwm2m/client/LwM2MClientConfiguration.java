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
package org.thingsboard.tools.lwm2m.client;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.*;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.engine.DefaultRegistrationEngineFactory;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.californium.DefaultEndpointFactory;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.tools.lwm2m.client.objects.LwM2MLocationParams;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mBinaryAppDataContainer;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mConnectivityMonitoring;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mDevice;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mLocation;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mTemperatureSensor;
import org.thingsboard.tools.lwm2m.secure.LwM2MSecurityStore;


import javax.annotation.PostConstruct;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.eclipse.leshan.core.LwM2mId.CONNECTIVITY_MONITORING;
import static org.eclipse.leshan.core.LwM2mId.DEVICE;
import static org.eclipse.leshan.core.LwM2mId.LOCATION;


@Slf4j
//@Configuration("LwM2MClientConfiguration")
//@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientConfiguration {

    private static final int TEMPERATURE_SENSOR = 3303;
    private static final int BINARY_APP_DATA_CONTAINER = 19;
    private String endPoint;
    private int clientPort;
    private LwM2MSecurityMode mode;
    private final ScheduledExecutorService executorService;


    private LwM2MClientContext context;
    private LwM2MLocationParams locationParams;

    public LwM2MClientConfiguration (LwM2MClientContext context, LwM2MLocationParams locationParams, String endPoint,
                                     int portNumber, LwM2MSecurityMode mode, ScheduledExecutorService executorService) {
        this.mode = mode;
        this.context = context;
        this.locationParams = locationParams;
        this.endPoint = endPoint;
        this.clientPort = context.getClientStartPort() + portNumber;
        this.executorService = executorService;
    }

//    @Bean
    public LeshanClient getLeshanClient() {
        /** Create client */
//        log.info("Starting LwM2M client... PostConstruct. BootstrapEnable: ???");
        /** Initialize model */
//        List<ObjectModel> models = ObjectLoader.loadDefault();
        List<ObjectModel> models = context.getModelsValue();

        /** Initialize object list */
        final LwM2mModel model = new StaticModel(models);
        final ObjectsInitializer initializer = new ObjectsInitializer(model);

        /** Endpoint */
//        String subEndpoint = !context.getSubEndpoint().isEmpty() ? context.getSubEndpoint() : LwM2MSecurityMode.fromSecurityMode(context.getDtlsMode()).subEndpoint;
//        String endpoint = !context.getEndpoint().isEmpty() ? context.getEndpoint() + "_" + subEndpoint : "client_default";
//        log.info("Start LwM2M client... PostConstruct [{}]", endpoint);

        /** Initialize security object */
        new LwM2MSecurityStore(context, initializer, this.endPoint, this.mode);

        /** Initialize other objects */
        initializer.setInstancesForObject(DEVICE, new LwM2mDevice(executorService));
//        initializer.setInstancesForObject(DEVICE, new LwM2mDevice());
//        initializer.setInstancesForObject(CONNECTIVITY_MONITORING, new LwM2mConnectivityMonitoring(executorService));
//        initializer.setInstancesForObject(BINARY_APP_DATA_CONTAINER, new LwM2mBinaryAppDataContainer(executorService));
////        initializer.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));
//        initializer.setInstancesForObject(LOCATION, new LwM2mLocation(locationParams.getLatitude(), locationParams.getLongitude(), locationParams.getScaleFactor()));
//
        LwM2mInstanceEnabler [] instances = {new LwM2mTemperatureSensor(executorService), new LwM2mTemperatureSensor(executorService)};
        initializer.setInstancesForObject(TEMPERATURE_SENSOR, instances);


        List<LwM2mObjectEnabler> enablers = initializer.createAll();

        /** Create CoAP Config */
        NetworkConfig coapConfig;
        File configFile = new File(NetworkConfig.DEFAULT_FILE_NAME);
        if (configFile.isFile()) {
            coapConfig = new NetworkConfig();
            coapConfig.load(configFile);
        } else {
            coapConfig = LeshanClientBuilder.createDefaultNetworkConfig();
            coapConfig.store(configFile);
        }

        /** Create DTLS Config */
        DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();
        dtlsConfig.setRecommendedCipherSuitesOnly(!context.getOldCiphers());

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
        builder.setAdditionalAttributes(context.getAddAttributes().isEmpty() ? null : getAddAttrs(context.getAddAttributes()));
        return builder.build();
    }


//    @PostConstruct
    public void init(Map<String, String> clientAccessConnect){
        LwM2MClientInitializer clientInitializer= new LwM2MClientInitializer(getLeshanClient(), clientAccessConnect);
        LeshanClient client = clientInitializer.init();
        client.start();
    }

   private Map<String, String> getAddAttrs(String addAttrs) {
        Map<String, String> additionalAttributes = new HashMap<>();
        Pattern p1 = Pattern.compile("(.*):\"(.*)\"");
        Pattern p2 = Pattern.compile("(.*):(.*)");
        String[] values = addAttrs.split(";");
        for (String v : values) {
            Matcher m = p1.matcher(v);
            if (m.matches()) {
                String attrName = m.group(1);
                String attrValue = m.group(2);
                additionalAttributes.put(attrName, attrValue);
            } else {
                m = p2.matcher(v);
                if (m.matches()) {
                    String attrName = m.group(1);
                    String attrValue = m.group(2);
                    additionalAttributes.put(attrName, attrValue);
                } else {
                    log.error("Invalid syntax for additional attributes : [{}]", v);
                    return null;
                }
            }
        }
        return additionalAttributes;    }
}
