package org.thingsboard.tools.service.customer;

import lombok.Data;

@Data
public class CustomerProfile {

    private String name;
    private int count;
    private int minDevices;
    private int maxDevices;
    private SubCustomerProfile subCustomers;
}
