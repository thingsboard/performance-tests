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
package org.thingsboard.tools.lwm2m.secure;

import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.EndpointContextMatcher;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.UDPConnector;

import java.io.IOException;
import java.net.InetSocketAddress;

public class UDPConnectorCustom implements Connector {
    @Override
    public void start() throws IOException {

    }

    @Override
    public void stop() {

    }

    @Override
    public void destroy() {

    }

    @Override
    public void send(RawData msg) {

    }

    @Override
    public void setRawDataReceiver(RawDataChannel messageHandler) {

    }

    @Override
    public void setEndpointContextMatcher(EndpointContextMatcher matcher) {

    }

    @Override
    public InetSocketAddress getAddress() {
        return null;
    }

    @Override
    public String getProtocol() {
        return null;
    }
}
