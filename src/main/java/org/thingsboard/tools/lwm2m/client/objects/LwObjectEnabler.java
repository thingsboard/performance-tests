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
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.ObjectEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.client.servers.ServersInfoExtractor;
import org.eclipse.leshan.client.util.LinkFormatHelper;
import org.eclipse.leshan.core.Destroyable;
import org.eclipse.leshan.core.Link;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.Startable;
import org.eclipse.leshan.core.Stoppable;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mResource;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class LwObjectEnabler extends ObjectEnabler {
    public LwObjectEnabler(int id, ObjectModel objectModel, Map<Integer, LwM2mInstanceEnabler> instances, LwM2mInstanceEnablerFactory instanceFactory, ContentFormat defaultContentFormat) {
        super(id, objectModel, instances, instanceFactory, defaultContentFormat);
        this.instances = new HashMap<>(instances);
        this.instanceFactory = instanceFactory;
        this.defaultContentFormat = defaultContentFormat;
        for (Map.Entry<Integer, LwM2mInstanceEnabler> entry : this.instances.entrySet()) {
            instances.put(entry.getKey(), entry.getValue());
            listenInstance(entry.getValue(), entry.getKey());
        }
    }

    @Override
    public synchronized List<Integer> getAvailableInstanceIds() {
        List<Integer> ids = new ArrayList<>(instances.keySet());
        Collections.sort(ids);
        return ids;
    }

    @Override
    public synchronized List<Integer> getAvailableResourceIds(int instanceId) {
        LwM2mInstanceEnabler instanceEnabler = instances.get(instanceId);
        if (instanceEnabler != null) {
            return instanceEnabler.getAvailableResourceIds(getObjectModel());
        } else {
            return Collections.emptyList();
        }
    }

    public synchronized void addInstance(int instanceId, LwM2mInstanceEnabler newInstance) {
        instances.put(instanceId, newInstance);
        listenInstance(newInstance, instanceId);
        fireInstancesAdded(instanceId);
    }

    public synchronized LwM2mInstanceEnabler getInstance(int instanceId) {
        return instances.get(instanceId);
    }

    public synchronized LwM2mInstanceEnabler removeInstance(int instanceId) {
        LwM2mInstanceEnabler removedInstance = instances.remove(instanceId);
        if (removedInstance != null) {
            fireInstancesRemoved(removedInstance.getId());
        }
        return removedInstance;
    }

    @Override
    protected CreateResponse doCreate(ServerIdentity identity, CreateRequest request) {
        if (!getObjectModel().multiple && instances.size() > 0) {
            return CreateResponse.badRequest("an instance already exist for this single instance object");
        }

        if (request.unknownObjectInstanceId()) {
            // create instance
            LwM2mInstanceEnabler newInstance = createInstance(identity, getObjectModel().multiple ? null : 0,
                    request.getResources());

            // add new instance to this object
            instances.put(newInstance.getId(), newInstance);
            listenInstance(newInstance, newInstance.getId());
            fireInstancesAdded(newInstance.getId());

            return CreateResponse
                    .success(new LwM2mPath(request.getPath().getObjectId(), newInstance.getId()).toString());
        } else {
            List<LwM2mObjectInstance> instanceNodes = request.getObjectInstances();

            // checks single object instances
            if (!getObjectModel().multiple) {
                if (request.getObjectInstances().size() > 1) {
                    return CreateResponse.badRequest("can not create several instances on this single instance object");
                }
                if (request.getObjectInstances().get(0).getId() != 0) {
                    return CreateResponse.badRequest("single instance object must use 0 as ID");
                }
            }
            // ensure instance does not already exists
            for (LwM2mObjectInstance instance : instanceNodes) {
                if (instances.containsKey(instance.getId())) {
                    return CreateResponse.badRequest(String.format("instance %d already exists", instance.getId()));
                }
            }

            // create the new instances
            int[] instanceIds = new int[request.getObjectInstances().size()];
            int i = 0;
            for (LwM2mObjectInstance instance : request.getObjectInstances()) {
                // create instance
                LwM2mInstanceEnabler newInstance = createInstance(identity, instance.getId(),
                        instance.getResources().values());

                // add new instance to this object
                instances.put(newInstance.getId(), newInstance);
                listenInstance(newInstance, newInstance.getId());

                // store instance ids
                instanceIds[i] = newInstance.getId();
                i++;
            }
            fireInstancesAdded(instanceIds);
            return CreateResponse.success();
        }
    }

    protected LwM2mInstanceEnabler createInstance(ServerIdentity identity, Integer instanceId,
                                                  Collection<LwM2mResource> resources) {
        // create the new instance
        LwM2mInstanceEnabler newInstance = instanceFactory.create(getObjectModel(), instanceId, instances.keySet());
        newInstance.setLwM2mClient(getLwm2mClient());

        // add/write resource
        for (LwM2mResource resource : resources) {
            newInstance.write(identity, resource.getId(), resource);
        }

        return newInstance;
    }

    @Override
    protected ReadResponse doRead(ServerIdentity identity, ReadRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            List<LwM2mObjectInstance> lwM2mObjectInstances = new ArrayList<>();
            for (LwM2mInstanceEnabler instance : instances.values()) {
                ReadResponse response = instance.read(identity);
                if (response.isSuccess()) {
                    lwM2mObjectInstances.add((LwM2mObjectInstance) response.getContent());
                }
            }
            return ReadResponse.success(new LwM2mObject(getId(), lwM2mObjectInstances));
        }

        // Manage Instance case
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return ReadResponse.notFound();

        if (path.getResourceId() == null) {
            return instance.read(identity);
        }

        // Manage Resource case
        return instance.read(identity, path.getResourceId());
    }

    @Override
    protected ObserveResponse doObserve(final ServerIdentity identity, final ObserveRequest request) {
        final LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            List<LwM2mObjectInstance> lwM2mObjectInstances = new ArrayList<>();
            for (LwM2mInstanceEnabler instance : instances.values()) {
                ReadResponse response = instance.observe(identity);
                if (response.isSuccess()) {
                    lwM2mObjectInstances.add((LwM2mObjectInstance) response.getContent());
                }
            }
            return ObserveResponse.success(new LwM2mObject(getId(), lwM2mObjectInstances));
        }

        // Manage Instance case
        final LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return ObserveResponse.notFound();

        if (path.getResourceId() == null) {
            return instance.observe(identity);
        }

        // Manage Resource case
        return instance.observe(identity, path.getResourceId());
    }

    @Override
    protected WriteResponse doWrite(ServerIdentity identity, WriteRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Instance case
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null)
            return WriteResponse.notFound();

        if (path.isObjectInstance()) {
            return instance.write(identity, request.isReplaceRequest(), (LwM2mObjectInstance) request.getNode());
        }

        // Manage Resource case
        return instance.write(identity, path.getResourceId(), (LwM2mResource) request.getNode());
    }

    @Override
    protected BootstrapWriteResponse doWrite(ServerIdentity identity, BootstrapWriteRequest request) {
        LwM2mPath path = request.getPath();

        // Manage Object case
        if (path.isObject()) {
            for (LwM2mObjectInstance instanceNode : ((LwM2mObject) request.getNode()).getInstances().values()) {
                LwM2mInstanceEnabler instanceEnabler = instances.get(instanceNode.getId());
                if (instanceEnabler == null) {
                    doCreate(identity, new CreateRequest(path.getObjectId(), instanceNode));
                } else {
                    doWrite(identity, new WriteRequest(WriteRequest.Mode.REPLACE, path.getObjectId(), instanceEnabler.getId(),
                            instanceNode.getResources().values()));
                }
            }
            return BootstrapWriteResponse.success();
        }

        // Manage Instance case
        if (path.isObjectInstance()) {
            LwM2mObjectInstance instanceNode = (LwM2mObjectInstance) request.getNode();
            LwM2mInstanceEnabler instanceEnabler = instances.get(path.getObjectInstanceId());
            if (instanceEnabler == null) {
                doCreate(identity, new CreateRequest(path.getObjectId(), instanceNode));
            } else {
                doWrite(identity, new WriteRequest(WriteRequest.Mode.REPLACE, request.getContentFormat(), path.getObjectId(),
                        path.getObjectInstanceId(), instanceNode.getResources().values()));
            }
            return BootstrapWriteResponse.success();
        }

        // Manage resource case
        LwM2mResource resource = (LwM2mResource) request.getNode();
        LwM2mInstanceEnabler instanceEnabler = instances.get(path.getObjectInstanceId());
        if (instanceEnabler == null) {
            doCreate(identity, new CreateRequest(path.getObjectId(),
                    new LwM2mObjectInstance(path.getObjectInstanceId(), resource)));
        } else {
            instanceEnabler.write(identity, path.getResourceId(), resource);
        }
        return BootstrapWriteResponse.success();
    }

    @Override
    protected ExecuteResponse doExecute(ServerIdentity identity, ExecuteRequest request) {
        LwM2mPath path = request.getPath();
        LwM2mInstanceEnabler instance = instances.get(path.getObjectInstanceId());
        if (instance == null) {
            return ExecuteResponse.notFound();
        }
        return instance.execute(identity, path.getResourceId(), request.getParameters());
    }

    @Override
    protected DeleteResponse doDelete(ServerIdentity identity, DeleteRequest request) {
        LwM2mInstanceEnabler deletedInstance = instances.remove(request.getPath().getObjectInstanceId());
        if (deletedInstance != null) {
            deletedInstance.onDelete(identity);
            fireInstancesRemoved(deletedInstance.getId());
            return DeleteResponse.success();
        }
        return DeleteResponse.notFound();
    }

    @Override
    public BootstrapDeleteResponse doDelete(ServerIdentity identity, BootstrapDeleteRequest request) {
        if (request.getPath().isRoot() || request.getPath().isObject()) {
            if (id == LwM2mId.SECURITY) {
                // For security object, we clean everything except bootstrap Server account.
                Map.Entry<Integer, LwM2mInstanceEnabler> bootstrapServerAccount = null;
                int[] instanceIds = new int[instances.size()];
                int i = 0;
                for (Map.Entry<Integer, LwM2mInstanceEnabler> instance : instances.entrySet()) {
                    if (ServersInfoExtractor.isBootstrapServer(instance.getValue())) {
                        bootstrapServerAccount = instance;
                    } else {
                        // store instance ids
                        instanceIds[i] = instance.getKey();
                        i++;
                    }
                }
                instances.clear();
                if (bootstrapServerAccount != null) {
                    instances.put(bootstrapServerAccount.getKey(), bootstrapServerAccount.getValue());
                }
                fireInstancesRemoved(instanceIds);
                return BootstrapDeleteResponse.success();
            } else {
                instances.clear();
                // fired instances removed
                int[] instanceIds = new int[instances.size()];
                int i = 0;
                for (Map.Entry<Integer, LwM2mInstanceEnabler> instance : instances.entrySet()) {
                    instanceIds[i] = instance.getKey();
                    i++;
                }
                fireInstancesRemoved(instanceIds);

                return BootstrapDeleteResponse.success();
            }
        } else if (request.getPath().isObjectInstance()) {
            if (id == LwM2mId.SECURITY) {
                // For security object, deleting bootstrap Server account is not allowed
                LwM2mInstanceEnabler instance = instances.get(request.getPath().getObjectInstanceId());
                if (ServersInfoExtractor.isBootstrapServer(instance)) {
                    return BootstrapDeleteResponse.badRequest("bootstrap server can not be deleted");
                }
            }
            if (null != instances.remove(request.getPath().getObjectInstanceId())) {
                fireInstancesRemoved(request.getPath().getObjectInstanceId());
                return BootstrapDeleteResponse.success();
            } else {
                return BootstrapDeleteResponse.badRequest(String.format("Instance %s not found", request.getPath()));
            }
        }
        return BootstrapDeleteResponse.badRequest(String.format("unexcepted path %s", request.getPath()));
    }

    @Override
    public synchronized WriteAttributesResponse writeAttributes(ServerIdentity identity,
                                                                WriteAttributesRequest request) {
        // execute is not supported for bootstrap
        if (identity.isLwm2mBootstrapServer()) {
            return WriteAttributesResponse.methodNotAllowed();
        }


        if (id == LwM2mId.SECURITY) {
            return WriteAttributesResponse.notFound();
        }
        return doWriteAttributes(identity, request);
//        discover(identity, new DiscoverRequest (request.getPath().toString()));
//        return WriteAttributesResponse.internalServerError(discover(identity, new DiscoverRequest (request.getPath().toString())).getObjectLinks().toString());
//        return WriteAttributesResponse.success();
    }

    protected WriteAttributesResponse doWriteAttributes (ServerIdentity identity, WriteAttributesRequest request) {

        LwM2mPath path = request.getPath();
        if (path.isObject()) {
            // Manage discover on object
            Link[] ObjectLinks = LinkFormatHelper.getObjectDescription(this, null);
            log.warn("WriteAttributes: [{}] [{}]", request.getPath(), request.getAttributes());
            log.warn("WriteAttributesResponse: [{}]", ObjectLinks);
            return WriteAttributesResponse.success();

        } else if (path.isObjectInstance()) {
            // Manage WriteAttribute on instance
            if (!getAvailableInstanceIds().contains(path.getObjectInstanceId()))
                return WriteAttributesResponse.notFound();

            Link[] instanceLink = LinkFormatHelper.getInstanceDescription(this, path.getObjectInstanceId(), null);
            log.warn("WriteAttributes: [{}] [{}]", request.getPath(), request.getAttributes());
            log.warn("WriteAttributesResponse: [{}]", instanceLink);
            return WriteAttributesResponse.success();

        } else if (path.isResource()) {
            // Manage writeAttribute on resource
            if (!getAvailableInstanceIds().contains(path.getObjectInstanceId()))
                return WriteAttributesResponse.notFound();

            ResourceModel resourceModel = getObjectModel().resources.get(path.getResourceId());
            if (resourceModel == null)
                return WriteAttributesResponse.notFound();

            if (!getAvailableResourceIds(path.getObjectInstanceId()).contains(path.getResourceId()))
                return WriteAttributesResponse.notFound();

            Link resourceLink = LinkFormatHelper.getResourceDescription(this, path.getObjectInstanceId(),
                    path.getResourceId(), null);
            log.warn("WriteAttributes: [{}] [{}]", request.getPath(), request.getAttributes());
            log.warn("WriteAttributesResponse: [{}]", new Link[] { resourceLink });
            return WriteAttributesResponse.success();
        }
        return WriteAttributesResponse.badRequest(null);
    }

    @Override
    public synchronized DiscoverResponse discover(ServerIdentity identity, DiscoverRequest request) {

        if (identity.isLwm2mBootstrapServer()) {
            // discover is not supported for bootstrap
            return DiscoverResponse.methodNotAllowed();
        }

        if (id == LwM2mId.SECURITY) {
            return DiscoverResponse.notFound();
        }
        return doDiscover(identity, request);

    }

    protected DiscoverResponse doDiscover(ServerIdentity identity, DiscoverRequest request) {

        LwM2mPath path = request.getPath();
        if (path.isObject()) {
            // Manage discover on object
            Link[] ObjectLinks = LinkFormatHelper.getObjectDescription(this, null);
            return DiscoverResponse.success(ObjectLinks);

        } else if (path.isObjectInstance()) {
            // Manage discover on instance
            if (!getAvailableInstanceIds().contains(path.getObjectInstanceId()))
                return DiscoverResponse.notFound();

            Link[] instanceLink = LinkFormatHelper.getInstanceDescription(this, path.getObjectInstanceId(), null);
            return DiscoverResponse.success(instanceLink);

        } else if (path.isResource()) {
            // Manage discover on resource
            if (!getAvailableInstanceIds().contains(path.getObjectInstanceId()))
                return DiscoverResponse.notFound();

            ResourceModel resourceModel = getObjectModel().resources.get(path.getResourceId());
            if (resourceModel == null)
                return DiscoverResponse.notFound();

            if (!getAvailableResourceIds(path.getObjectInstanceId()).contains(path.getResourceId()))
                return DiscoverResponse.notFound();

            Link resourceLink = LinkFormatHelper.getResourceDescription(this, path.getObjectInstanceId(),
                    path.getResourceId(), null);
            return DiscoverResponse.success(new Link[] { resourceLink });
        }
        return DiscoverResponse.badRequest(null);
    }

    @Override
    public synchronized BootstrapDiscoverResponse discover(ServerIdentity identity, BootstrapDiscoverRequest request) {

        if (!identity.isLwm2mBootstrapServer()) {
            return BootstrapDiscoverResponse.badRequest("not a bootstrap server");
        }

        return doDiscover(identity, request);
    }

    protected BootstrapDiscoverResponse doDiscover(ServerIdentity identity, BootstrapDiscoverRequest request) {

        LwM2mPath path = request.getPath();
        if (path.isObject()) {
            // Manage discover on object
            Link[] ObjectLinks = LinkFormatHelper.getBootstrapObjectDescription(this);
            return BootstrapDiscoverResponse.success(ObjectLinks);
        }
        return BootstrapDiscoverResponse.badRequest("invalid path");
    }


//    protected void listenInstance(LwM2mInstanceEnabler instance, final int instanceId) {
//        instance.addResourceChangedListener(new ResourceChangedListener() {
//            @Override
//            public void resourcesChanged(int... resourceIds) {
//                fireResourcesChanged(instanceId, resourceIds);
//            }
//        });
//    }


    @Override
    public ContentFormat getDefaultEncodingFormat(DownlinkRequest<?> request) {
        return defaultContentFormat;
    }

    @Override
    public void setLwM2mClient(LwM2mClient client) {
        for (LwM2mInstanceEnabler instanceEnabler : instances.values()) {
            instanceEnabler.setLwM2mClient(client);
        }
    }

    @Override
    public void destroy() {
        for (LwM2mInstanceEnabler instanceEnabler : instances.values()) {
            if (instanceEnabler instanceof Destroyable) {
                ((Destroyable) instanceEnabler).destroy();
            } else if (instanceEnabler instanceof Stoppable) {
                ((Stoppable) instanceEnabler).stop();
            }
        }
    }

    @Override
    public void start() {
        for (LwM2mInstanceEnabler instanceEnabler : instances.values()) {
            if (instanceEnabler instanceof Startable) {
                ((Startable) instanceEnabler).start();
            }
        }
    }

    @Override
    public void stop() {
        for (LwM2mInstanceEnabler instanceEnabler : instances.values()) {
            if (instanceEnabler instanceof Stoppable) {
                ((Stoppable) instanceEnabler).stop();
            }
        }
    }
}
