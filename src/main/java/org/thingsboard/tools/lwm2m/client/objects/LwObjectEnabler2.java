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
package org.thingsboard.tools.lwm2m.client.objects;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.LwM2mClient;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler2;
import org.eclipse.leshan.client.resource.listener.ObjectListener;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.request.BootstrapDeleteRequest;
import org.eclipse.leshan.core.request.BootstrapDiscoverRequest;
import org.eclipse.leshan.core.request.BootstrapWriteRequest;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.CreateRequest;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.request.DownlinkRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.response.BootstrapDeleteResponse;
import org.eclipse.leshan.core.response.BootstrapDiscoverResponse;
import org.eclipse.leshan.core.response.BootstrapWriteResponse;
import org.eclipse.leshan.core.response.CreateResponse;
import org.eclipse.leshan.core.response.DeleteResponse;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import java.util.List;

@Slf4j
public class LwObjectEnabler2 implements LwM2mObjectEnabler2 {

    @Override
    public BootstrapDiscoverResponse discover(ServerIdentity identity, BootstrapDiscoverRequest request) {
        return null;
    }

    @Override
    public int getId() {
        return 0;
    }

    @Override
    public ObjectModel getObjectModel() {
        return null;
    }

    @Override
    public List<Integer> getAvailableInstanceIds() {
        return null;
    }

    @Override
    public List<Integer> getAvailableResourceIds(int instanceId) {
        return null;
    }

    @Override
    public CreateResponse create(ServerIdentity identity, CreateRequest request) {
        return null;
    }

    @Override
    public ReadResponse read(ServerIdentity identity, ReadRequest request) {
        return null;
    }

    @Override
    public WriteResponse write(ServerIdentity identity, WriteRequest request) {
        return null;
    }

    @Override
    public BootstrapWriteResponse write(ServerIdentity identity, BootstrapWriteRequest request) {
        return null;
    }

    @Override
    public DeleteResponse delete(ServerIdentity identity, DeleteRequest request) {
        return null;
    }

    @Override
    public BootstrapDeleteResponse delete(ServerIdentity identity, BootstrapDeleteRequest request) {
        return null;
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, ExecuteRequest request) {
        return null;
    }

    @Override
    public WriteAttributesResponse writeAttributes(ServerIdentity identity, WriteAttributesRequest request) {
        return null;
    }

    @Override
    public DiscoverResponse discover(ServerIdentity identity, DiscoverRequest request) {
        return null;
    }

    @Override
    public ObserveResponse observe(ServerIdentity identity, ObserveRequest request) {
        return null;
    }

    @Override
    public void addListener(ObjectListener listener) {

    }

    @Override
    public void removeListener(ObjectListener listener) {

    }

    @Override
    public void setLwM2mClient(LwM2mClient client) {

    }

    @Override
    public ContentFormat getDefaultEncodingFormat(DownlinkRequest<?> request) {
        return null;
    }
}
