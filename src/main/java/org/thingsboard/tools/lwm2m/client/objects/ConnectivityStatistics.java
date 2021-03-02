package org.thingsboard.tools.lwm2m.client.objects;

import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

public class ConnectivityStatistics extends BaseInstanceEnabler {

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceid) {
        switch (resourceid) {
            case 0:
                return ReadResponse.notFound();
//                return ReadResponse.success(resourceid, getSmsTxCounter());
        }
        return ReadResponse.notFound();
    }

    @Override
    public WriteResponse write(ServerIdentity identity, int resourceid, LwM2mResource value) {
        switch (resourceid) {
            case 15:
//                setCollectionPeriod((Long) value.getValue());
                return WriteResponse.success();
        }
        return WriteResponse.notFound();
    }

    @Override
    public ExecuteResponse execute(ServerIdentity identity, int resourceid, String params) {
        switch (resourceid) {
            case 12:
//                start();
                return ExecuteResponse.success();
        }
        return ExecuteResponse.notFound();
    }
}