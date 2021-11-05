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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.rest.client.RestClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DefaultRestClientService implements RestClientService {

    @Value("${test.http_pool_size}")
    private int httpPoolSize;

    public static final int LOG_PAUSE = 1;

    private ExecutorService httpExecutor;
    private final ScheduledExecutorService logScheduler = Executors.newScheduledThreadPool(10);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final ExecutorService workers = Executors.newFixedThreadPool(10, ThingsBoardThreadFactory.forName("workers"));

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
        httpExecutor = Executors.newFixedThreadPool(httpPoolSize);
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
