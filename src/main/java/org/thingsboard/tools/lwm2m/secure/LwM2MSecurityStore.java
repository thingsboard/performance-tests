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

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.elements.util.StandardCharsets;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.util.Hex;
import org.thingsboard.tools.lwm2m.client.LwM2MClientContext;
import org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode;
import sun.security.x509.X509CertImpl;

import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import static org.eclipse.leshan.client.object.Security.*;
import static org.eclipse.leshan.client.object.Security.noSec;
import static org.eclipse.leshan.core.LwM2mId.SECURITY;
import static org.eclipse.leshan.core.LwM2mId.SERVER;

@Slf4j
@Data
public class LwM2MSecurityStore {

    String clientPublicKey;
    String clientPrivateKey;
    String bootstrapPublicKey;
    String serverPublicKey;
    private LwM2MClientContext context;
    private String endPoint;
    private ObjectsInitializer initializer;
    private LwM2MSecurityMode mode;

    //    public LwM2MSecurityStore(LwM2MClientContext context, ObjectsInitializer initializer, String endPoint, LwM2MSecurityMode mode, DtlsConnectorConfig.Builder dtlsConfig) {
    public LwM2MSecurityStore(LwM2MClientContext context, ObjectsInitializer initializer, String endPoint, LwM2MSecurityMode mode, int numberClient) {
        this.context = context;
        this.endPoint = endPoint;
        this.initializer = initializer;
        this.mode = mode;
        switch (mode) {
            case PSK:
                setInstancesPSK();
                break;
            case RPK:
                setInstancesRPK();
                break;
            case X509:
                setInstancesX509(numberClient);
                break;
            case NO_SEC:
                setInstancesNoSec();
                break;
            default:
        }
    }

    private void setInstancesNoSec() {
        String serverURI = null;
        if (context.isLwm2mNoSecBootStrapEnabled()) {
            serverURI = context.coapLink + context.getLwm2mHostNoSecBootStrap() + ":" + context.getLwm2mPortNoSecBootStrap();
            initializer.setInstancesForObject(SECURITY, noSecBootstap(serverURI));
            initializer.setClassForObject(SERVER, Server.class);
        } else {
            serverURI = context.coapLink + context.getLwm2mHostNoSec() + ":" + context.getLwm2mPortNoSec();
            initializer.setInstancesForObject(SECURITY, noSec(serverURI, context.getServerShortId()));
            initializer.setInstancesForObject(SERVER, new Server(context.getServerShortId(), context.getLifetime(), BindingMode.U, false));
        }
    }

    private void setInstancesPSK() {
        this.getParamsKeys();
        String clientPrivateKey = context.getNodeConfigKeys().get(mode.name()).get("clientSecretKey").asText();
        byte[] pskIdentity = (endPoint + context.getLwm2mPSKIdentitySub()).getBytes();
        byte[] pskKey = Hex.decodeHex(clientPrivateKey.toCharArray());
        String serverSecureURI = null;
        if (context.isLwm2mPSKBootStrapEnabled()) {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostPSKBootStrap() + ":" + context.getLwm2mPortPSKBootStrap();
            initializer.setInstancesForObject(SECURITY, pskBootstrap(serverSecureURI, pskIdentity, pskKey));
            initializer.setClassForObject(SERVER, Server.class);
        } else {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostPSK() + ":" + context.getLwm2mPortPSK();
            initializer.setInstancesForObject(SECURITY, psk(serverSecureURI, context.getServerShortId(), pskIdentity, pskKey));
            initializer.setInstancesForObject(SERVER, new Server(context.getServerShortId(), context.getLifetime(), BindingMode.U, false));
        }
    }

    private void setInstancesRPK() {
        String serverSecureURI = null;
        this.getParamsKeys();
        if (context.isLwm2mRPKBootStrapEnabled()) {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostPSKBootStrap() + ":" + context.getLwm2mPortRPKBootStrap();
            initializer.setInstancesForObject(SECURITY, rpkBootstrap(serverSecureURI,
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.bootstrapPublicKey.toCharArray())));
            initializer.setClassForObject(SERVER, Server.class);
        } else {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostRPK() + ":" + context.getLwm2mPortRPK();
            initializer.setInstancesForObject(SECURITY, rpk(serverSecureURI, context.getServerShortId(),
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.serverPublicKey.toCharArray())));
            initializer.setInstancesForObject(SERVER, new Server(context.getServerShortId(), context.getLifetime(), BindingMode.U, false));
        }
    }

    private void setInstancesX509(int numberClient) {
        this.getKeyCertForX509(numberClient);
        String serverSecureURI = null;
        if (context.isLwm2mX509BootStrapEnabled()) {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostX509BootStrap() + ":" + context.getLwm2mPortX509BootStrap();
            initializer.setInstancesForObject(SECURITY, x509Bootstrap(serverSecureURI,
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.bootstrapPublicKey.toCharArray())));
            initializer.setClassForObject(SERVER, Server.class);
        } else {
            serverSecureURI = context.coapLinkSec + context.getLwm2mHostX509() + ":" + context.getLwm2mPortX509();
            initializer.setInstancesForObject(SECURITY, x509(serverSecureURI, context.getServerShortId(),
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.serverPublicKey.toCharArray())));
            initializer.setInstancesForObject(SERVER, new Server(context.getServerShortId(), context.getLifetime(), BindingMode.U, true));
        }
    }


    private void getParamsKeys() {
        this.clientPublicKey = context.getNodeConfigKeys().get(mode.name()).get("clientPublicKeyOrId").asText();
        this.clientPrivateKey = context.getNodeConfigKeys().get(mode.name()).get("clientSecretKey").asText();
        this.bootstrapPublicKey = context.getNodeConfigKeys().get(mode.name()).get("bootstrapPublicKey").asText();
        this.serverPublicKey = context.getNodeConfigKeys().get(mode.name()).get("serverPublicKey").asText();
    }

    private void getKeyCertForX509(int numberClient) {
        try {
            Certificate clientCertificate = (X509Certificate) context.getClientKeyStoreValue().getCertificate(context.getClientAlias(numberClient));
            PrivateKey clientPrivKey = (PrivateKey) context.getClientKeyStoreValue().getKey(context.getClientAlias(numberClient), context.getClientKeyStorePwd().toCharArray());
            Certificate serverCertificate = (X509Certificate) context.getServerKeyStoreValue().getCertificate(context.getServerAlias());
            Certificate bootStrapCertificate = (X509Certificate) context.getServerKeyStoreValue().getCertificate(context.getBootstrapAlias());
            this.clientPublicKey = Hex.encodeHexString(clientCertificate.getEncoded());
            this.clientPrivateKey = Hex.encodeHexString(clientPrivKey.getEncoded());
            getParamsX509((X509Certificate)clientCertificate, "Client");
            this.serverPublicKey = Hex.encodeHexString(serverCertificate.getEncoded());
            getParamsX509((X509Certificate)serverCertificate, "Server");
            this.bootstrapPublicKey = Hex.encodeHexString(bootStrapCertificate.getEncoded());
            getParamsX509((X509Certificate)bootStrapCertificate, "Bootstrap");
        } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException | CertificateEncodingException e) {
            log.error("Unable to load key and certificates for X509: [{}]", e.getMessage());
        }
    }

    private static void getParamsX509(X509Certificate certificate, String whose) {
        try {
            log.info("{} uses X509 : " +
                            "\n X509 Certificate (Hex): [{}] " +
                            "\n getSigAlgName: [{}] " +
                            "\n getSigAlgOID: [{}] " +
                            "\n type: [{}] " +
                            "\n IssuerDN().getName: [{}] " +
                            "\n SubjectDN().getName: [{}]",
                    whose,
                    Hex.encodeHexString(certificate.getEncoded()),
                    certificate.getSigAlgName(),
                    certificate.getSigAlgOID(),
                    certificate.getType(),
                    certificate.getIssuerDN().getName(),
                    certificate.getSubjectDN().getName()
//                    new String(((X509CertImpl) certificate).signedCert, StandardCharsets.UTF_8)
            );

        } catch (CertificateEncodingException e) {
            log.error(" [{}]", e.getMessage());
        }
    }

}
