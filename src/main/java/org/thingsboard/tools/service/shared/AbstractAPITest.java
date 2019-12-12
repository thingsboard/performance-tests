package org.thingsboard.tools.service.shared;

import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.tools.service.customer.CustomerManager;

public abstract class AbstractAPITest {

    @Autowired
    protected RestClientService restClientService;
    @Autowired
    protected CustomerManager customerManager;

}
