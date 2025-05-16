/**
 * Copyright © 2016-2025 The Thingsboard Authors
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

import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.core.request.BindingMode;

import java.util.EnumSet;

public class Lwm2mServer extends Server {

    public Lwm2mServer() {}
    public Lwm2mServer(int shortServerId, long lifetime, EnumSet<BindingMode> binding, boolean notifyWhenDisable,
                       BindingMode preferredTransport, int id) {
        super (shortServerId, lifetime, binding, notifyWhenDisable, preferredTransport);
        this.setId(id);

    }
}
