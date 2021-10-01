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
package org.thingsboard.tools.service.lwm2m;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.response.ReadResponse;
import org.thingsboard.tools.service.msg.Msg;

import javax.security.auth.Destroyable;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class LwM2MClient extends BaseInstanceEnabler implements Destroyable {

    @Getter
    @Setter
    private String name;

    @Getter
    @Setter
    private LeshanClient leshanClient;

    private static final List<Integer> supportedResources = Arrays.asList(0);

    private volatile byte[] data;

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
        if (data != null && resourceId == 0) {
            return ReadResponse.success(resourceId, data);
        }
        return ReadResponse.notFound();
    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel model) {
        return supportedResources;
    }

    @SneakyThrows
    public void send(Msg msg) {
        data = msg.getData();
        fireResourcesChange(0);
    }

    @Override
    public void destroy() {
        if (leshanClient != null) {
            leshanClient.destroy(true);
        }
    }
}
