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


import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.response.ReadResponse;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


@Slf4j
@Data
public class LwM2mConnectivityMonitoring extends LwM2mBaseInstanceEnabler {

    private Integer networkBearer = 0;  // 0    value 0-50
    private Integer availableNetworkBearer = 1;  // 1  value 0-50
    private Integer radioSignalStrength = 2;  // 2  value 0-50
    private Integer linkQuality = 3;  // 3
    private String iPAddresses = "";  // 4
    private String routerIPAddresses = "";  // 5
    private Integer linkUtilization = 6;  // 6
    private String aPN = "";  // 7
    private Integer cellID = 20470117;  // 8
    private Integer sMNC = 9;  // 9
    private Integer sMCC = 10;  // 10

    public LwM2mConnectivityMonitoring () {

    }

    public LwM2mConnectivityMonitoring (ScheduledExecutorService executorService, List<Integer> unSupportedResources, Integer id) {
        try {
            if (id != null) this.setId(id);
            this.unSupportedResourcesInit = unSupportedResources;
        executorService.scheduleWithFixedDelay(() ->
                fireResourcesChange(8), 5000, 5000, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            log.error("[{}]Throwable", e.toString());
            e.printStackTrace();
        }
    }

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
//        log.info("Read on Device resource /{}/{}/{}", getModel().id, getId(), resourceid);
        resourceId = getSupportedResource (resourceId);
        switch (resourceId) {
            case 0:
                return ReadResponse.success(resourceId, getNetworkBearer());
            case 1:
                return ReadResponse.success(resourceId, getAvailableNetworkBearer());
            case 2:
                return ReadResponse.success(resourceId, getRadioSignalStrength());
            case 3:
                return ReadResponse.success(resourceId, getLinkQuality());
            case 4:
                return ReadResponse.success(resourceId, getIPAddresses());
            case 5:
                return ReadResponse.success(resourceId, getRouterIPAddresses());
            case 6:
                return ReadResponse.success(resourceId, getLinkUtilization());
            case 7:
                return ReadResponse.success(resourceId, getAPN());
            case 8:
                return ReadResponse.success(resourceId, getCellID());
            case 9:
                return ReadResponse.success(resourceId, getSMNC());
            case 10:
                return ReadResponse.success(resourceId, getSMCC());
            default:
                return super.read(identity, resourceId);
        }
    }


    private int getCellID() {
        return RANDOM.nextInt(268435455);
    }


}
