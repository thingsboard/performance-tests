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
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.DummyInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.util.Hex;
import org.springframework.util.Base64Utils;
import org.thingsboard.tools.lwm2m.client.LwM2MClientContext;
import org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode;
import org.thingsboard.tools.lwm2m.client.objects.LwM2mBinaryAppDataContainer;
import org.thingsboard.tools.lwm2m.client.objects.Lwm2mServer;

import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.List;

import static org.eclipse.leshan.client.object.Security.noSec;
import static org.eclipse.leshan.client.object.Security.noSecBootstap;
import static org.eclipse.leshan.client.object.Security.psk;
import static org.eclipse.leshan.client.object.Security.pskBootstrap;
import static org.eclipse.leshan.client.object.Security.rpk;
import static org.eclipse.leshan.client.object.Security.rpkBootstrap;
import static org.eclipse.leshan.client.object.Security.x509;
import static org.eclipse.leshan.client.object.Security.x509Bootstrap;
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

    public LwM2MSecurityStore(LwM2MClientContext context, ObjectsInitializer initializer, String endPoint, LwM2MSecurityMode mode, int numberClient, List<ObjectModel> objectModels) {
        this.context = context;
        this.endPoint = endPoint;
        this.initializer = initializer;
        this.mode = mode;
        switch (mode) {
            case PSK:
                this.setInstancesPSK();
                break;
            case RPK:
                this.setInstancesRPK();
                break;
            case X509:
                this.setInstancesX509(numberClient);
                break;
            case NO_SEC:
                this.setInstancesNoSec();
                break;
            default:
        }
    }

    private void setInstancesNoSec() {
        String serverURI;
        if (this.context.isLwm2mNoSecBootStrapEnabled()) {
            serverURI = LwM2MClientContext.coapLink + this.context.getLwm2mHostNoSecBootStrap() + ":" + this.context.getLwm2mPortNoSecBootStrap();
            this.initializer.setInstancesForObject(SECURITY, noSecBootstap(serverURI));
            this.initializer.setInstancesForObject(SERVER, new Server(this.context.getBootstrapShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U));
        }
        else {
            Server serverBootstrap0 = new Lwm2mServer(this.context.getBootstrapShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U, 0);
            Server server1 = new Lwm2mServer(this.context.getServerShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U, 1);
            LwM2mInstanceEnabler[] instances = new LwM2mInstanceEnabler[]{serverBootstrap0, server1};
            initializer.setClassForObject(SERVER, Server.class);
            this.initializer.setInstancesForObject(SERVER, instances);
            String serverURIServer = this.context.coapLink + context.getLwm2mHostNoSec() + ":" + this.context.getLwm2mPortNoSec();
            String serverURIBootstrap = LwM2MClientContext.coapLink + this.context.getLwm2mHostNoSecBootStrap() + ":" + this.context.getLwm2mPortNoSecBootStrap();
            Security securityBootstrap = noSecBootstap(serverURIBootstrap);
            Security securityServer = noSec(serverURIServer, this.context.getServerShortId());
            instances = new LwM2mInstanceEnabler[]{securityBootstrap, securityServer};
            this.initializer.setInstancesForObject(SECURITY, instances);
//            this.initializer.setInstancesForObject(SERVER, new Server(this.context.getServerShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U));
        }
    }

    private void setInstancesPSK() {
        this.getParamsKeys();
        String clientPrivateKey = this.context.getNodeConfigKeys().get(mode.name()).get("clientSecretKey").asText();
        this.clientPublicKey = this.endPoint + this.context.getLwm2mPSKIdentitySub();
        byte[] pskIdentity = this.clientPublicKey.getBytes();
        byte[] pskKey = Hex.decodeHex(clientPrivateKey.toCharArray());
        String serverSecureURI = null;
        if (context.isLwm2mPSKBootStrapEnabled()) {
            serverSecureURI = context.coapLinkSec + this.context.getLwm2mHostPSKBootStrap() + ":" + this.context.getLwm2mPortPSKBootStrap();
            this.initializer.setInstancesForObject(SECURITY, pskBootstrap(serverSecureURI, pskIdentity, pskKey));
            this.initializer.setClassForObject(SERVER, Server.class);
            this.getParamsInfoPSK("bootStrap");
        } else {
            serverSecureURI = context.coapLinkSec + this.context.getLwm2mHostPSK() + ":" + this.context.getLwm2mPortPSK();
            this.initializer.setInstancesForObject(SECURITY, psk(serverSecureURI, this.context.getServerShortId(), pskIdentity, pskKey));
            this.initializer.setInstancesForObject(SERVER, new Server(context.getServerShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U));
            this.getParamsInfoPSK("server");
        }
    }

    private void setInstancesRPK() {
        String serverSecureURI = null;
        this.getParamsKeys();
        if (this.context.isLwm2mRPKBootStrapEnabled()) {
            serverSecureURI = this.context.coapLinkSec + this.context.getLwm2mHostPSKBootStrap() + ":" + this.context.getLwm2mPortRPKBootStrap();
            initializer.setInstancesForObject(SECURITY, rpkBootstrap(serverSecureURI,
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.bootstrapPublicKey.toCharArray())));
            initializer.setClassForObject(SERVER, Server.class);
            this.getParamsInfoRPK(this.bootstrapPublicKey);
        } else {
            serverSecureURI = this.context.coapLinkSec + this.context.getLwm2mHostRPK() + ":" + this.context.getLwm2mPortRPK();
            initializer.setInstancesForObject(SECURITY, rpk(serverSecureURI, this.context.getServerShortId(),
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.serverPublicKey.toCharArray())));
            initializer.setInstancesForObject(SERVER, new Server(this.context.getServerShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), false, BindingMode.U));
            this.getParamsInfoRPK(this.serverPublicKey);
        }
    }

    private void setInstancesX509(int numberClient) {
        this.getKeyCertForX509(numberClient);
        String serverSecureURI = null;
        if (this.context.isLwm2mX509BootstrapEnabled()) {
            serverSecureURI = this.context.coapLinkSec + this.context.getLwm2mHostX509BootStrap() + ":" + this.context.getLwm2mPortX509BootStrap();
            initializer.setInstancesForObject(SECURITY, x509Bootstrap(serverSecureURI,
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.bootstrapPublicKey.toCharArray())));
            initializer.setClassForObject(SERVER, Server.class);
        } else {
            serverSecureURI = this.context.coapLinkSec + this.context.getLwm2mHostX509() + ":" + this.context.getLwm2mPortX509();
            Security security = x509(serverSecureURI,
                    this.context.getServerShortId(),
                    Hex.decodeHex(this.clientPublicKey.toCharArray()),
                    Hex.decodeHex(this.clientPrivateKey.toCharArray()),
                    Hex.decodeHex(this.serverPublicKey.toCharArray()));
            initializer.setInstancesForObject(SECURITY, security);
            initializer.setInstancesForObject(SERVER, new Server(this.context.getServerShortId(), this.context.getLifetime(), EnumSet.of(BindingMode.U), true, BindingMode.U));
        }
    }


    private void getParamsKeys() {
        this.clientPublicKey = this.context.getNodeConfigKeys().get(mode.name()).get("clientPublicKeyOrId").asText();
        this.clientPrivateKey = this.context.getNodeConfigKeys().get(mode.name()).get("clientSecretKey").asText();
        this.bootstrapPublicKey = this.context.getNodeConfigKeys().get(mode.name()).get("bootstrapPublicKey").asText();
        this.serverPublicKey = this.context.getNodeConfigKeys().get(mode.name()).get("serverPublicKey").asText();
    }

    private void getKeyCertForX509(int numberClient) {
        try {
            Certificate clientCertificate = (X509Certificate) this.context.getClientKeyStoreValue().getCertificate(this.context.getClientAlias(numberClient, false));
            String clientAlias = this.context.isLwm2mX509Trust() ? this.context.getClientAlias(numberClient, true) : this.context.getClientAliasNoTrust() ;
            PrivateKey clientPrivKey = (PrivateKey) this.context.getClientKeyStoreValue().getKey(clientAlias, this.context.getClientKeyStorePwd().toCharArray());
            Certificate serverCertificate = (X509Certificate) this.context.getServerKeyStoreValue().getCertificate(this.context.getServerAlias());
            Certificate bootStrapCertificate = (X509Certificate) this.context.getServerKeyStoreValue().getCertificate(this.context.getBootstrapAlias());
            this.clientPublicKey = Hex.encodeHexString(clientCertificate.getEncoded());
            this.clientPrivateKey = Hex.encodeHexString(clientPrivKey.getEncoded());
            this.getParamsInfoX509((X509Certificate) clientCertificate, "Client", clientPrivKey);
            this.serverPublicKey = Hex.encodeHexString(serverCertificate.getEncoded());
            this.getParamsInfoX509((X509Certificate) serverCertificate, "Server", null);
            this.bootstrapPublicKey = Hex.encodeHexString(bootStrapCertificate.getEncoded());
            this.getParamsInfoX509((X509Certificate) bootStrapCertificate, "Bootstrap", null);
        } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException | CertificateEncodingException e) {
            log.error("Unable to load key and certificates for X509: [{}]", e.getMessage());
        }
    }

    private static void getParamsInfoX509(X509Certificate certificate, String whose, PrivateKey privateKey) {
        try {
            log.info("{} uses X509 : " +
                            "\n Endpoint: [{}] " +
                            "\n X509 Certificate (Hex): [{}] " +
                            "\n X509 Certificate (Base64): [{}] " +
                            "\n PrivateKey (Hex): [{}] " +
                            "\n getSigAlgName: [{}] " +
                            "\n getSigAlgOID: [{}] " +
                            "\n type: [{}] " +
                            "\n IssuerDN().getName: [{}] " +
                            "\n SubjectDN().getName: [{}]",
                    whose,
                    certificate.getSubjectDN().getName(),
                    Hex.encodeHexString(certificate.getEncoded()),
                    Base64Utils.encodeToString(certificate.getEncoded()),
                    privateKey == null ? "null" : Hex.encodeHexString(privateKey.getEncoded()),
                    certificate.getSigAlgName(),
                    certificate.getSigAlgOID(),
                    certificate.getType(),
                    certificate.getIssuerDN().getName(),
                    certificate.getSubjectDN().getName()
            );
        } catch (CertificateEncodingException e) {
            log.error(" [{}]", e.getMessage());
        }
    }

    private void getParamsInfoRPK(String serverPublicKey) {
        log.info("{} uses RPK : " +
                        "\n clientPublicKey (Hex): [{}] " +
                        "\n clientPrivate: [{}] " +
                        "\n serverPublicKey: [{}] " ,
                this.endPoint,
                this.clientPublicKey,
                this.clientPrivateKey,
                serverPublicKey
        );
    }

    private void getParamsInfoPSK(String server) {
        log.info("{} uses PSK : " +
                        "\n server: [{}] " +
                        "\n clientPublicKeyOrId: [{}] " +
                        "\n clientPrivateKey (Hex): [{}] "  ,
                this.endPoint,
                server,
                this.clientPublicKey,
                this.clientPrivateKey
        );
    }

}
