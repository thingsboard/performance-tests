package org.thingsboard.tools.service.customer;

import lombok.Data;

@Data
public class SubCustomerProfile {

    private int count;
    private int minDevices;
    private int maxDevices;

}
