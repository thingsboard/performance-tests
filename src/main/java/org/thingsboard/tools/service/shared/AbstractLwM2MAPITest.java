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
package org.thingsboard.tools.service.shared;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.observe.ObservationStore;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.engine.DefaultRegistrationEngineFactory;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.DummyInstanceEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.californium.EndpointFactory;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.tools.service.lwm2m.LwM2MClient;
import org.thingsboard.tools.service.lwm2m.LwM2MDeviceClient;
import org.thingsboard.tools.service.msg.Msg;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.eclipse.leshan.client.object.Security.noSec;
import static org.eclipse.leshan.core.LwM2mId.ACCESS_CONTROL;
import static org.eclipse.leshan.core.LwM2mId.DEVICE;
import static org.eclipse.leshan.core.LwM2mId.SERVER;

@Slf4j
public abstract class AbstractLwM2MAPITest extends AbstractAPITest {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(500, ThingsBoardThreadFactory.forName("lwm2m-scheduled"));

    protected final List<LwM2MClient> lwM2MClients = Collections.synchronizedList(new ArrayList<>());

    @Value("${lwm2m.host}")
    private String lwm2mHost;
    @Value("${lwm2m.port}")
    protected int port;
    @Value("${lwm2m.lifetime}")
    protected long lifetime;

    private List<ObjectModel> models;
    private Security security;
    private NetworkConfig coapConfig;

    @SneakyThrows
    @PostConstruct
    protected void init() {
        super.init();
        String[] resources = new String[]{"0.xml", "1.xml", "2.xml", "3.xml", "19.xml"};
        models = new ArrayList<>();
        for (String resourceName : resources) {
            models.addAll(ObjectLoader.loadDdfFile(AbstractLwM2MAPITest.class.getClassLoader().getResourceAsStream("lwm2m/" + resourceName), resourceName));
        }

        security = noSec("coap://" + lwm2mHost + ":" + port, 123);
        coapConfig = new NetworkConfig().setString("COAP_PORT", Integer.toString(port));
    }

    @Override
    protected void send(int iteration,
                        AtomicInteger totalSuccessPublishedCount,
                        AtomicInteger totalFailedPublishedCount,
                        AtomicInteger successPublishedCount,
                        AtomicInteger failedPublishedCount,
                        CountDownLatch testDurationLatch,
                        CountDownLatch iterationLatch,
                        DeviceClient client,
                        Msg message) {
        try {
            ((LwM2MDeviceClient) client).getClient().send(message);
            totalSuccessPublishedCount.incrementAndGet();
            successPublishedCount.incrementAndGet();
            logSuccessTestMessage(iteration, client);
        } catch (Exception e) {
            totalFailedPublishedCount.incrementAndGet();
            failedPublishedCount.incrementAndGet();
            logFailureTestMessage(iteration, client, e);
        }
        iterationLatch.countDown();
    }

    @Override
    protected ObjectNode createRpc(DeviceClient client) {
        ObjectNode rpcRequest = mapper.createObjectNode();
        rpcRequest.put("method", "WriteReplace");
        ObjectNode rpcParams = mapper.createObjectNode();
        rpcParams.put("id", "/19/0/1");
        rpcParams.put("value", true);
        rpcRequest.set("params", rpcParams);
        rpcRequest.put("persistent", ((LwM2MDeviceClient)client).getClient().getNextRpcValue());
        rpcRequest.put("timeout", 10000);

        return rpcRequest;
    }

    private LwM2MClient initClient(String endpoint, int i) throws InterruptedException {
            LwM2MClient client = new LwM2MClient();
            client.setName(endpoint);
            LeshanClient leshanClient;

            LwM2mModel model = new StaticModel(models);
            ObjectsInitializer initializer = new ObjectsInitializer(model);
            initializer.setInstancesForObject(0, security);
            initializer.setInstancesForObject(SERVER, new Server(123, lifetime));
            initializer.setInstancesForObject(19, client);
            initializer.setClassForObject(DEVICE, DummyInstanceEnabler.class);
            initializer.setClassForObject(ACCESS_CONTROL, DummyInstanceEnabler.class);
            DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();
            dtlsConfig.setRecommendedCipherSuitesOnly(true);
            dtlsConfig.setClientOnly();

            DefaultRegistrationEngineFactory engineFactory = new DefaultRegistrationEngineFactory();
            engineFactory.setReconnectOnUpdate(false);
            engineFactory.setResumeOnConnect(true);

            EndpointFactory endpointFactory = new EndpointFactory() {

                @Override
                public CoapEndpoint createUnsecuredEndpoint(InetSocketAddress address, NetworkConfig coapConfig,
                                                            ObservationStore store) {
                    CoapEndpoint.Builder builder = new CoapEndpoint.Builder();
                    builder.setInetSocketAddress(address);
                    builder.setNetworkConfig(coapConfig);
                    return builder.build();
                }

                @Override
                public CoapEndpoint createSecuredEndpoint(DtlsConnectorConfig dtlsConfig, NetworkConfig coapConfig,
                                                          ObservationStore store) {
                    CoapEndpoint.Builder builder = new CoapEndpoint.Builder();
                    DtlsConnectorConfig.Builder dtlsConfigBuilder = new DtlsConnectorConfig.Builder(dtlsConfig);
                    builder.setConnector(new DTLSConnector(dtlsConfigBuilder.build()));
                    builder.setNetworkConfig(coapConfig);
                    return builder.build();
                }
            };

            LeshanClientBuilder builder = new LeshanClientBuilder(endpoint);
            builder.setLocalAddress("0.0.0.0", 11000 + i);
            builder.setObjects(initializer.createAll());
            builder.setCoapConfig(coapConfig);
            builder.setDtlsConfig(dtlsConfig);
            builder.setRegistrationEngineFactory(engineFactory);
            builder.setEndpointFactory(endpointFactory);
            builder.setSharedExecutor(executor);
            builder.setDecoder(new

                    DefaultLwM2mNodeDecoder(true));
            builder.setEncoder(new

                    DefaultLwM2mNodeEncoder(true));
            leshanClient = builder.build();

            client.setLeshanClient(leshanClient);

            Thread.sleep(1000);
            leshanClient.start();

            return client;
    }

    protected void connectDevices(List<String> pack, AtomicInteger totalConnectedCount, boolean isGateway) throws InterruptedException {
        log.info("Connecting {} devices...", pack.size());
        CountDownLatch connectLatch = new CountDownLatch(pack.size());
        for (String deviceName : pack) {
            restClientService.getWorkers().submit(() -> {
                try {
                    lwM2MClients.add(initClient(deviceName, totalConnectedCount.incrementAndGet()));
                    totalConnectedCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error while connect {}", "device", e);
                } finally {
                    connectLatch.countDown();
                }
            });
        }
        connectLatch.await();
        log.info("{} devices have been connected successfully!", totalConnectedCount.get());
    }


    @Override
    @PreDestroy
    public void destroy() {
        for (LwM2MClient mqttClient : lwM2MClients) {
            mqttClient.destroy();
        }

        executor.shutdownNow();
        super.destroy();
    }
}
