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
