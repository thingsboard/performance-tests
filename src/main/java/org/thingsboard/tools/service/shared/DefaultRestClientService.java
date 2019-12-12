package org.thingsboard.tools.service.shared;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DefaultRestClientService implements RestClientService {

    public static final int LOG_PAUSE = 1;

    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(100);
    private final ScheduledExecutorService logScheduler = Executors.newScheduledThreadPool(10);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final ExecutorService workers = Executors.newFixedThreadPool(10);

    @Value("${rest.url}")
    private String restUrl;
    @Value("${rest.username}")
    private String username;
    @Value("${rest.password}")
    private String password;

    @Getter
    private RestClient restClient;
    @Getter
    private EventLoopGroup eventLoopGroup;

    @Override
    public ExecutorService getWorkers() {
        return workers;
    }

    @Override
    public ExecutorService getHttpExecutor() {
        return httpExecutor;
    }

    @Override
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    @Override
    public ScheduledExecutorService getLogScheduler() {
        return logScheduler;
    }

    @PostConstruct
    public void init() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
        eventLoopGroup = new NioEventLoopGroup();
    }


    @PreDestroy
    public void destroy() {
        if (!this.httpExecutor.isShutdown()) {
            this.httpExecutor.shutdownNow();
        }
        if (!this.scheduler.isShutdown()) {
            this.scheduler.shutdownNow();
        }
        if (!this.workers.isShutdown()) {
            this.workers.shutdownNow();
        }
        if (!eventLoopGroup.isShutdown()) {
            eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }

    }

}
