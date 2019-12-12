package org.thingsboard.tools.service.shared;

import io.netty.channel.EventLoopGroup;
import org.thingsboard.client.tools.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public interface RestClientService {

    RestClient getRestClient();

    EventLoopGroup getEventLoopGroup();

    ExecutorService getWorkers();

    ExecutorService getHttpExecutor();

    ScheduledExecutorService getScheduler();

    ScheduledExecutorService getLogScheduler();

}
