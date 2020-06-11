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
package org.thingsboard.tools.service.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.permission.GroupPermission;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.tools.service.dashboard.DashboardManager;
import org.thingsboard.tools.service.shared.DefaultRestClientService;
import org.thingsboard.tools.service.shared.RestClientService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DefaultCustomerManager implements CustomerManager {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final List<CustomerId> customerIds = Collections.synchronizedList(new ArrayList<>(1024));

    @Value("${rest.url}")
    private String restUrl;

    private final CustomerProfilesConfiguration profiles;
    private final DashboardManager dashboardManager;
    private final RestClientService restClientService;
    private final AtomicInteger tokenSeq = new AtomicInteger();
    private final AtomicInteger deviceSeq = new AtomicInteger();
    private final Set<Device> customerDevices = ConcurrentHashMap.newKeySet();

    public DefaultCustomerManager(CustomerProfilesConfiguration profiles, DashboardManager dashboardManager, RestClientService restClientService) {
        this.profiles = profiles;
        this.dashboardManager = dashboardManager;
        this.restClientService = restClientService;
    }

    @Override
    public List<CustomerId> getCustomerIds() {
        return customerIds;
    }

    @Override
    public void createCustomers() throws Exception {
        profiles.getProfiles().forEach(this::createCustomers);
    }

    private void createCustomers(CustomerProfile profile) {
        log.info("[{}] Creating {} customers with {} sub-customers for each customer.", profile.getName(), profile.getCount(), profile.getSubCustomers().getCount());
        CountDownLatch latch = new CountDownLatch(profile.getCount());
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < profile.getCount(); i++) {
            final int tokenNumber = tokenSeq.incrementAndGet();
            restClientService.getHttpExecutor().submit(() -> {
                Customer customer = null;
                try {
                    String title = profile.getName() + " C" + String.format("%8d", tokenNumber).replace(" ", "0");
                    customer = saveCustomer(null, title);

                    String email = "user@customer" + tokenNumber + "." + profile.getName().toLowerCase() + ".com";
                    saveCustomerAdmin(customer, email);

                    int deviceCount = random(profile.getMinDevices(), profile.getMaxDevices());
                    createCustomerDevice(customer.getId(), deviceCount);

                    for (int subCustomerIdx = 0; subCustomerIdx < profile.getSubCustomers().getCount(); subCustomerIdx++) {
                            createSubCustomer(profile, customer.getId(), tokenNumber, subCustomerIdx);
                    }

                    customerIds.add(customer.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("[{}][{}] Error while creating entity", profile.getName(), tokenNumber, e);
                    if (customer != null && customer.getId() != null) {
                        getRestClient().deleteCustomer(customer.getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} customers have been created so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        logScheduleFuture.cancel(true);
        log.info("{} customers have been created successfully!", customerIds.size());
    }

    private void createCustomerDevice(CustomerId customerId, int deviceCount) {
        for (int i = 0; i < deviceCount; i++) {
            Device device = new Device();
            device.setOwnerId(customerId);
            device.setName("CD" + deviceSeq.incrementAndGet());
            device.setType("default");
            device = getRestClient().saveDevice(device);
            DeviceCredentials deviceCredentials = new DeviceCredentials();
            deviceCredentials.setDeviceId(device.getId());
            deviceCredentials.setCredentialsId(device.getName());
            deviceCredentials.setCredentialsValue(device.getName());
            deviceCredentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
            getRestClient().saveDeviceCredentials(deviceCredentials);
            customerDevices.add(device);
        }
    }

    private void createSubCustomer(CustomerProfile profile, CustomerId parentId, int tokenNumber, int subCustomerIdx) {
        Customer customer = null;
        try {
            String title = profile.getName() + " C" + String.format("%8d", tokenNumber).replace(" ", "0") + "-" + String.format("%4d", subCustomerIdx).replace(" ", "0");
            customer = saveCustomer(parentId, title);
            String email = "user@" + subCustomerIdx + ".customer" + tokenNumber + "." + profile.getName().toLowerCase() + ".com";
            saveCustomerAdmin(customer, email);
            int deviceCount = random(profile.getSubCustomers().getMinDevices(), profile.getSubCustomers().getMaxDevices());
            createCustomerDevice(customer.getId(), deviceCount);
        } catch (Exception e) {
            log.error("[{}][{}][{}] Error while creating entity", profile.getName(), tokenNumber, subCustomerIdx, e);
            if (customer != null && customer.getId() != null) {
                getRestClient().deleteCustomer(customer.getId());
            }
            throw new RuntimeException("Failed to create sub-customer");
        }
    }

    private void saveCustomerAdmin(Customer customer, String email) {
        List<EntityGroupInfo> groups = getRestClient().getEntityGroupsByOwnerAndType(customer.getId(), EntityType.USER);
        EntityGroupInfo customerAdmins = groups.stream().filter(g -> g.getName()
                .equalsIgnoreCase("Customer Administrators")).findFirst()
                .orElseThrow(() -> new RuntimeException("Customer Administrator Group is not present!"));

        User customerAdmin = new User();
        customerAdmin.setFirstName(email);
        customerAdmin.setEmail(email);
        customerAdmin.setAuthority(Authority.CUSTOMER_USER);
        customerAdmin.setTenantId(customer.getTenantId());
        customerAdmin.setCustomerId(customer.getId());
        customerAdmin = getRestClient().saveUser(customerAdmin, false);

        getRestClient().addEntitiesToEntityGroup(customerAdmins.getId(), Collections.singletonList(customerAdmin.getId()));

        ObjectNode activateRequest = mapper.createObjectNode();
        activateRequest.put("activateToken", getRestClient().getActivateToken(customerAdmin.getId()));
        activateRequest.put("password", "customer");

        try {
            getRestClient().getRestTemplate().postForEntity(restUrl + "/api/noauth/activate?sendActivationMail=false", activateRequest, JsonNode.class);
        } catch (HttpClientErrorException var5) {
            throw new RuntimeException("Failed to activate user " + email);
        }
    }

    private Customer saveCustomer(EntityId parentId, String title) {
        Customer customer;
        customer = new Customer();
        customer.setTitle(title);
        if (parentId != null) {
            customer.setOwnerId(parentId);
        }
        customer = getRestClient().saveCustomer(customer);
        return customer;
    }

    private int random(int minDevices, int maxDevices) {
        return minDevices + (int) (Math.random() * (maxDevices - minDevices));
    }

    @Override
    public void removeCustomers() throws Exception {
        if (customerIds.size() == 0) {
            return;
        }
        log.info("Removing {} customers", customerIds.size());
        CountDownLatch latch = new CountDownLatch(customerIds.size());
        AtomicInteger count = new AtomicInteger();
        for (CustomerId entityId : customerIds) {
            restClientService.getHttpExecutor().submit(() -> {
                try {
                    restClientService.getRestClient().deleteCustomer(entityId);
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting customer", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} customers have been removed so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("{} customers have been removed successfully! {} were failed for removal!", count.get(), customerIds.size() - count.get());
    }

    private RestClient getRestClient() {
        return restClientService.getRestClient();
    }
}
