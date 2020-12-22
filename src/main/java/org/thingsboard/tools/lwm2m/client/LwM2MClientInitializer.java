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

@Slf4j
//@Service("LwM2MClientInitializer")
//@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientInitializer {

//    @Autowired
    private LeshanClient client;

    public LwM2MClientInitializer (LeshanClient client) {
        this.client = client;
    }

//    @PostConstruct
    public void init() {
        log.info("init client");
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

                log.info("ClientObserver -> onRegistrationStarted... ServerIdentity [{}]", server);
            }

            @Override
            public void onRegistrationSuccess(ServerIdentity server, RegisterRequest request, String registrationID) {
//                log.info("ClientObserver -> onRegistrationSuccess... ServerIdentity [{}] client.coapServer [{}]", server, client.triggerRegistrationUpdate());
                log.info("ClientObserver -> onRegistrationSuccess... ServerIdentity [{}] \n request: {} \n registrationID {}", server, request, registrationID);
            }

            @Override
            public void onRegistrationFailure(ServerIdentity server, RegisterRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {
                log.info("ClientObserver -> onRegistrationFailure... ServerIdentity [{}]", server);
            }

            @Override
            public void onRegistrationTimeout(ServerIdentity server, RegisterRequest request) {
                log.info("ClientObserver -> onRegistrationTimeout... RegisterRequest [{}]", request);
            }

            @Override
            public void onUpdateStarted(ServerIdentity server, UpdateRequest request) {
                log.info("ClientObserver -> onUpdateStarted...  UpdateRequest [{}]", request);
            }

            @Override
            public void onUpdateSuccess(ServerIdentity server, UpdateRequest request) {

            }

            @Override
            public void onUpdateFailure(ServerIdentity server, UpdateRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {

            }

            @Override
            public void onUpdateTimeout(ServerIdentity server, UpdateRequest request) {

            }

            @Override
            public void onDeregistrationStarted(ServerIdentity server, DeregisterRequest request) {

            }

            @Override
            public void onDeregistrationSuccess(ServerIdentity server, DeregisterRequest request) {

            }

            @Override
            public void onDeregistrationFailure(ServerIdentity server, DeregisterRequest request, ResponseCode responseCode, String errorMessage, Exception cause) {

            }

            @Override
            public void onDeregistrationTimeout(ServerIdentity server, DeregisterRequest request) {

            }
        };
        this.client.addObserver(observer);
        /** Start the client */
        this.client.start();

        /** De-register on shutdown and stop client. */
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                client.destroy(true); // send de-registration request before destroy
            }
        });

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
