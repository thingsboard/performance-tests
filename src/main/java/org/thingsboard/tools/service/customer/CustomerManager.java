package org.thingsboard.tools.service.customer;

import org.thingsboard.server.common.data.id.CustomerId;

import java.util.List;

public interface CustomerManager {

    void createCustomers() throws Exception;

    List<CustomerId> getCustomerIds();

    void removeCustomers() throws Exception;
}
