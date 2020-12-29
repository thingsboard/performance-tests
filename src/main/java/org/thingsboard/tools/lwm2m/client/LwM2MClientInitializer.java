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
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.observer.LwM2mClientObserver;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.listener.ObjectsListenerAdapter;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.ResponseCode;
import org.eclipse.leshan.core.request.BootstrapRequest;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;

@Slf4j
//@Service("LwM2MClientInitializer")
//@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientInitializer {

//    @Autowired
    private LeshanClient client;
    protected Map<String, String> clientAccessConnect;

    public LwM2MClientInitializer (LeshanClient client, Map<String, String> clientAccessConnect) {
        this.client = client;
        this.clientAccessConnect = clientAccessConnect;
    }

//    @PostConstruct
    public LeshanClient init() {

//        log.info("init client");
        this.client.getObjectTree().addListener(new ObjectsListenerAdapter() {
            @Override
            public void objectRemoved(LwM2mObjectEnabler object) {
                log.info("Object [{}] disabled.", object.getId());
            }

            @Override
            public void objectAdded(LwM2mObjectEnabler object) {
                log.info("Object [{}] enabled.", object.getId());
            }
        });
        LwM2mClientObserver observer = new LwM2mClientObserver() {
            @Override
            public void onBootstrapStarted(ServerIdentity bsserver, BootstrapRequest request) {
                log.info("ClientObserver -> onBootstrapStarted...");
            }

            @Override
            public void onBootstrapSuccess(ServerIdentity bsserver, BootstrapRequest request) {
                log.info("ClientObserver -> onBootstrapSuccess...");
            }

            @Override
            public void onBootstrapFailure(ServerIdentity bsserver, BootstrapRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {
                log.info("ClientObserver -> onBootstrapFailure...");
            }

            @Override
            public void onBootstrapTimeout(ServerIdentity bsserver, BootstrapRequest request) {
                log.info("ClientObserver -> onBootstrapTimeout...");
            }

            @Override
            public void onRegistrationStarted(ServerIdentity server, RegisterRequest request) {
//                log.info("ClientObserver -> onRegistrationStarted...  EndpointName [{}]", request.getEndpointName());
            }

            @Override
            public void onRegistrationSuccess(ServerIdentity server, RegisterRequest request, String registrationID) {
                clientAccessConnect.put(registrationID, request.getEndpointName());
                log.info("ClientObserver -> onRegistrationSuccess...  EndpointName [{}] [{}]", request.getEndpointName(), registrationID);
            }

            @Override
            public void onRegistrationFailure(ServerIdentity server, RegisterRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {
//                log.info("ClientObserver -> onRegistrationFailure... ServerIdentity [{}]", server);
            }

            @Override
            public void onRegistrationTimeout(ServerIdentity server, RegisterRequest request) {
//                log.info("ClientObserver -> onRegistrationTimeout... RegisterRequest [{}]", request);
            }

            @Override
            public void onUpdateStarted(ServerIdentity server, UpdateRequest request) {
//                log.info("ClientObserver -> onUpdateStarted...  UpdateRequest [{}]", request);
            }

            @Override
            public void onUpdateSuccess(ServerIdentity server, UpdateRequest request) {
//                log.info("ClientObserver -> onUpdateSuccess...  UpdateRequest [{}]", request);
            }

            @Override
            public void onUpdateFailure(ServerIdentity server, UpdateRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {

            }

            @Override
            public void onUpdateTimeout(ServerIdentity server, UpdateRequest request) {

            }

            @Override
            public void onDeregistrationStarted(ServerIdentity server, DeregisterRequest request) {
                log.info("ClientObserver ->onDeregistrationStarted...  DeregisterRequest [{}] [{}]", request.getRegistrationId(), clientAccessConnect.get(request.getRegistrationId()));

            }

            @Override
            public void onDeregistrationSuccess(ServerIdentity server, DeregisterRequest request) {
                log.info("ClientObserver ->onDeregistrationSuccess...  DeregisterRequest [{}] [{}]", request.getRegistrationId(), clientAccessConnect.get(request.getRegistrationId()));
                clientAccessConnect.remove(request.getRegistrationId());
                log.info("ClientObserver ->onDeregistrationSuccess...  clientAccessConnect.size [{}]", clientAccessConnect.size());

            }

            @Override
            public void onDeregistrationFailure(ServerIdentity server, DeregisterRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {
                log.info("ClientObserver ->onDeregistrationFailure...  DeregisterRequest [{}] [{}]", request.getRegistrationId(), request.getRegistrationId());
            }

            @Override
            public void onDeregistrationTimeout(ServerIdentity server, DeregisterRequest request) {
                log.info("ClientObserver ->onDeregistrationTimeout...  DeregisterRequest [{}] [{}]", request.getRegistrationId(), request.getRegistrationId());
            }
        };
        this.client.addObserver(observer);
        /** Start the client */
//        this.client.start();

        /** De-register on shutdown and stop client. */
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                client.destroy(true); // send de-registration request before destroy
            }
        });
        return this.client;
    }

    @PreDestroy
    public void shutdown()  {
        log.info("Stopping LwM2M thingsboard client!");
        try {
            client.destroy(true);
        } finally {
        }
        log.info("LwM2M thingsboard client stopped!");
    }

}
