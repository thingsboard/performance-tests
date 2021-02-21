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
package org.thingsboard.tools.lwm2m.client;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECNamedDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.eclipse.leshan.core.util.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.FileSystemException;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;

@Slf4j
@Component("CertificateGenerator")
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class CertificateGenerator {

    private static final String BC_PROVIDER = BouncyCastleProvider.PROVIDER_NAME;
    private static final String SUN_EC_PROVIDER = "SunEC";
    // RSA - Generates keypairs for the RSA algorithm (Signature/Cipher).
    private static final String KEY_ALGORITHM = "EC";
    // The signature algorithm with SHA-* and the RSA encryption algorithm as defined in the OSI Interoperability Workshop,
    // using the padding conventions described in PKCS #1.
//    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    //    private String DOMAIN_SUFFIX;
    private String NAME_CERT_GEO_SUFFIX;
    //    private String ROOT_CN;
    private static final String ORGANIZATIONAL_UNIT = "Thingsboard";
    private static final String ORGANIZATION = "Thingsboard";
    private static final String CITY = "Kyiv";
    private static final String STATE_OR_PROVINCE = "Kyiv Oblast";
    private static final String TWO_LETTER_COUNTRY_CODE = "UA";
    private static final String STORETYPE = "PKCS12";
    private KeyPairGenerator keyPairGenerator;
    private Date startDate;
    private Date endDate;
    private KeyPair rootKeyPair;
    private X500Name rootCertIssuer;
    private X509Certificate rootCert;
    private ContentSigner rootCsrContentSigner;
    private KeyStore sslKeyStoreServer;
    private KeyStore sslKeyStoreClient;
    private static final String fileNameKeyStore = "KeyStore";
    private static final String keyStorePrefixJks = ".jks";


//   SERVER_STORE=serverKeyStore1.jks
//SERVER_STORE_PWD=server_ks_password1
//SERVER_ALIAS=server1
//SERVER_CN="$DOMAIN_SUFFIX server LwM2M signed by root CA"
//SERVER_SELF_ALIAS=server_self_signed
//SERVER_SELF_CN="$DOMAIN_SUFFIX server LwM2M self-signed"


    @Autowired
    private LwM2MClientContext context;

    @PostConstruct
    public void init() throws NoSuchProviderException, NoSuchAlgorithmException, KeyStoreException, IOException, CertificateException {
//        this.DOMAIN_SUFFIX = context.getLwm2mHostX509();
        this.NAME_CERT_GEO_SUFFIX = ", OU=" + ORGANIZATIONAL_UNIT +
                ", O=" + ORGANIZATION +
                ", L=" + CITY +
                ", ST=" + STATE_OR_PROVINCE +
                ", C=" + TWO_LETTER_COUNTRY_CODE;
        // Add the BouncyCastle Provider
        Security.addProvider(new BouncyCastleProvider());
        // Initialize a new KeyPair generator
        this.keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, BC_PROVIDER);
        this.keyPairGenerator.initialize(256);
        // Setup start date to yesterday and end date for 1 year validity
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        this.startDate = calendar.getTime();
        calendar.add(Calendar.YEAR, 1);
        this.endDate = calendar.getTime();
//        this.sslKeyStoreServer = KeyStore.getInstance(this.STORETYPE, BC_PROVIDER);
        this.sslKeyStoreServer = KeyStore.getInstance(KeyStore.getDefaultType());
        this.sslKeyStoreServer.load(null, null);
        this.sslKeyStoreClient = KeyStore.getInstance(KeyStore.getDefaultType());
        this.sslKeyStoreClient.load(null, null);
    }

    /**
     * Server
     * Long randomServer = 8860302994796090120L;
     * Server uses [X509]: serverNoSecureURI : [0.0.0.0:5685], serverSecureURI : [0.0.0.0:5686]
     * - X509 Certificate (Hex): [308202b83082025ea00302010202087af61ea13f269308300a06082a8648ce3d04030230793119301706035504030c106c6f63616c686f737420726f6f74434131143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b3009060355040613025541301e170d3231303231313039343733375a170d3232303231313039343733375a30793119301706035504030c106c6f63616c686f73742073657276657231143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b30090603550406130255413059301306072a8648ce3d020106082a8648ce3d03010703420004db470ccca469246af9a5dd9a6665c2ae90dc85577135de3d1cce3f70e186aa8a54c6135b2d498807a212e0fbee76cc035b29dbf6c73f59b224789e568d1d149ca381cf3081cc3081aa0603551d230481a230819f8014741cee2a15b03c1fa6477b0b46d71a8574647d5aa17da47b30793119301706035504030c106c6f63616c686f737420726f6f74434131143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b30090603550406130255418208b6981aa86276381f301d0603551d0e0416041414eca384b63444b42879cc0b80ca808e129bfae3300a06082a8648ce3d0403020348003045022100ba9387b95e371273ca9fecbdd1acebea9b95c00a7589445ccd5fef1a682017bf02200210243ea8b178105bb8b278d1c60443b50261ee64eb8b2d549125a7c059f203]
     * - Public Key (Hex): [3059301306072a8648ce3d020106082a8648ce3d03010703420004db470ccca469246af9a5dd9a6665c2ae90dc85577135de3d1cce3f70e186aa8a54c6135b2d498807a212e0fbee76cc035b29dbf6c73f59b224789e568d1d149c]
     * - Private Key (Hex): [308193020100301306072a8648ce3d020106082a8648ce3d03010704793077020101042036e872060fa68fc669af529a696b1774e168aa573b3d306b4976d66ffc20f95da00a06082a8648ce3d030107a14403420004db470ccca469246af9a5dd9a6665c2ae90dc85577135de3d1cce3f70e186aa8a54c6135b2d498807a212e0fbee76cc035b29dbf6c73f59b224789e568d1d149c],
     * - public_x  : [db470ccca469246af9a5dd9a6665c2ae90dc85577135de3d1cce3f70e186aa8a]
     * - public_y  : [54c6135b2d498807a212e0fbee76cc035b29dbf6c73f59b224789e568d1d149c]
     * - private_s : [54c6135b2d498807a212e0fbee76cc035b29dbf6c73f59b224789e568d1d149c]
     * - Elliptic Curve parameters  : [secp256r1 [NIST P-256, X9.62 prime256v1] (1.2.840.10045.3.1.7)]
     *
     * Bootstrap:
     * Long randomBootstrapServer = -3862183349175192571L;
     * Bootstrap Server uses [X509]: serverNoSecureURI : [0.0.0.0:5687], serverSecureURI : [0.0.0.0:5688]
     * - X509 Certificate (Hex): [308202bc30820261a0030201020208ca66c4b4e6fbf405300a06082a8648ce3d04030230793119301706035504030c106c6f63616c686f737420726f6f74434131143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b3009060355040613025541301e170d3231303231313039343733375a170d3232303231313039343733375a307c311c301a06035504030c136c6f63616c686f737420626f6f74737472617031143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b30090603550406130255413059301306072a8648ce3d020106082a8648ce3d03010703420004720310a8f15646dc779b1ae2ae52794a164f7c9f8a73512a0965d353db9c63e107083ce0cf130aae792b487a20159a80a2a18a115dff64d832fd68294a590495a381cf3081cc3081aa0603551d230481a230819f8014741cee2a15b03c1fa6477b0b46d71a8574647d5aa17da47b30793119301706035504030c106c6f63616c686f737420726f6f74434131143012060355040b0c0b5468696e6773626f61726431143012060355040a0c0b5468696e6773626f617264310d300b06035504070c044b7969763114301206035504080c0b4b796976204f626c617374310b30090603550406130255418208b6981aa86276381f301d0603551d0e04160414ec538d357af4ff62f88f2e2be2d1d9ce0037e0dd300a06082a8648ce3d04030203490030460221008466b7f8bf6533fd24ee85a0b4b5908afa6e8744fa755ec4af9f9ce615ca2690022100f8e718c5144788c63be2ef9a5b528d279dff52704e0e3b6869b384259a3d2938]
     * - Public Key (Hex): [3059301306072a8648ce3d020106082a8648ce3d03010703420004720310a8f15646dc779b1ae2ae52794a164f7c9f8a73512a0965d353db9c63e107083ce0cf130aae792b487a20159a80a2a18a115dff64d832fd68294a590495]
     * - Private Key (Hex): [308193020100301306072a8648ce3d020106082a8648ce3d0301070479307702010104200526c40aebf616dc58b53f440c41e6182bb39e332811ea0e1e78e11f7e073652a00a06082a8648ce3d030107a14403420004720310a8f15646dc779b1ae2ae52794a164f7c9f8a73512a0965d353db9c63e107083ce0cf130aae792b487a20159a80a2a18a115dff64d832fd68294a590495],
     * - public_x :  [720310a8f15646dc779b1ae2ae52794a164f7c9f8a73512a0965d353db9c63e1]
     * - public_y :  [07083ce0cf130aae792b487a20159a80a2a18a115dff64d832fd68294a590495]
     * - private_s : [07083ce0cf130aae792b487a20159a80a2a18a115dff64d832fd68294a590495]
     * - Elliptic Curve parameters  : [secp256r1 [NIST P-256, X9.62 prime256v1] (1.2.840.10045.3.1.7)]
     */
    public void generationX509WithRootAndJks(int start, int finish) throws Exception {
        String fileNameJks = this.context.getServerAlias() + fileNameKeyStore + keyStorePrefixJks;
        this.generationX509RootJava();
        this.generationX509(this.sslKeyStoreServer, this.context.getServerAlias(),
                context.getLwm2mHostX509() + " " + this.context.getServerAlias(),
                context.getServerKeyStorePwd());
        this.generationX509(this.sslKeyStoreServer, this.context.getBootstrapAlias(),
                context.getLwm2mHostX509() + " " + this.context.getBootstrapAlias(),
                context.getServerKeyStorePwd());
        this.infoParamsServersX509(this.sslKeyStoreServer);
        this.exportKeyPairToKeystoreFile(this.sslKeyStoreServer, context.returnPathForCreatedNewX509().toUri().getPath() + fileNameJks, context.getServerKeyStorePwd());
//        this.verifyKeyStore (fileNameJks, context.getServerKeyStorePwd());

        for (int i = start; i < finish; i++) {
            this.generationX509(this.sslKeyStoreClient,
                    this.context.getClientAlias(i), this.context.getEndPoint(i, LwM2MSecurityMode.X509),
                    context.getClientKeyStorePwd());
        }
        fileNameJks = this.context.getPrefixClient() + fileNameKeyStore + keyStorePrefixJks;
        this.exportKeyPairToKeystoreFile(this.sslKeyStoreClient, context.returnPathForCreatedNewX509().toUri().getPath() + fileNameJks, context.getClientKeyStorePwd());
        this.verifyKeyStore(context.returnPathForCreatedNewX509().toUri().getPath() + fileNameJks, context.getClientKeyStorePwd());
    }

    private void generationX509RootJava() throws NoSuchAlgorithmException, CertIOException, CertificateException, OperatorCreationException {
        // First step is to create a root certificate
        // First Generate a KeyPair,
        // then a random serial number
        // then generate a certificate using the KeyPair
        this.rootKeyPair = keyPairGenerator.generateKeyPair();
        BigInteger rootSerialNum = new BigInteger(Long.toString(new SecureRandom().nextLong()));
        // Issued By and Issued To same for root certificate
        this.rootCertIssuer = new X500Name("CN=" + context.getLwm2mHostX509() + " " + context.getRootAlias() + this.NAME_CERT_GEO_SUFFIX);
        X500Name rootCertSubject = this.rootCertIssuer;
        ContentSigner rootCertContentSigner = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC_PROVIDER).build(this.rootKeyPair.getPrivate());
        X509v3CertificateBuilder rootCertBuilder = new JcaX509v3CertificateBuilder(this.rootCertIssuer, rootSerialNum, startDate, endDate, rootCertSubject, this.rootKeyPair.getPublic());

        // Add Extensions
        // A BasicConstraint to mark root certificate as CA certificate
        JcaX509ExtensionUtils rootCertExtUtils = new JcaX509ExtensionUtils();
        rootCertBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        rootCertBuilder.addExtension(Extension.subjectKeyIdentifier, false, rootCertExtUtils.createSubjectKeyIdentifier(this.rootKeyPair.getPublic()));
        // Create a cert holder
        X509CertificateHolder rootCertHolder = rootCertBuilder.build(rootCertContentSigner);
        this.rootCert = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(rootCertHolder);
        // Export to X509Certificate if need
//        writeCertToFileBase64Encoded(this.rootCert, "root-cert.cer");
//        exportKeyPairToKeystoreFile(this.rootKeyPair, this.rootCert, "root-cert", "root-cert.pfx", "PKCS12", "pass");
//        exportKeyPairToKeystoreFile(this.rootKeyPair, this.rootCert, "root-cert", "root-cert.pfx", "pass");
        JcaContentSignerBuilder csrBuilder = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC_PROVIDER);
        // Sign the new KeyPair with the root cert Private Key
        this.rootCsrContentSigner = csrBuilder.build(this.rootKeyPair.getPrivate());
    }

    public void generationX509(KeyStore sslKeyStore, String alias, String subjectDnNameCN, String keyStorePwd) throws Exception {
        // Generate a new KeyPair and sign it using the Root Cert Private Key
        // by generating a CSR (Certificate Signing Request)
        // Creating server certificate signed by root CA...
        X500Name certSubject = new X500Name("CN=" + subjectDnNameCN + this.NAME_CERT_GEO_SUFFIX);
        BigInteger certSerialNum = new BigInteger(Long.toString(new SecureRandom().nextLong()));
        KeyPair certKeyPair = keyPairGenerator.generateKeyPair();
        PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(certSubject, certKeyPair.getPublic());

        // Sign the new KeyPair with the root cert Private Key
        PKCS10CertificationRequest certCsr = p10Builder.build(this.rootCsrContentSigner);

        // Use the Signed KeyPair and CSR to generate an issued Certificate
        // Here serial number is randomly generated. In general, CAs use
        // a sequence to generate Serial number and avoid collisions
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(this.rootCertIssuer, certSerialNum, this.startDate, this.endDate, certCsr.getSubject(), certCsr.getSubjectPublicKeyInfo());
        JcaX509ExtensionUtils certExtUtils = new JcaX509ExtensionUtils();

        // Add Extensions
        // Use BasicConstraints to say that this Cert is not a CA
//        issuedCertBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        // Add Issuer cert identifier as Extension
        certBuilder.addExtension(Extension.authorityKeyIdentifier, false, certExtUtils.createAuthorityKeyIdentifier(this.rootCert));
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false, certExtUtils.createSubjectKeyIdentifier(certCsr.getSubjectPublicKeyInfo()));

        // Add DNS name is cert is to used for SSL
//        serverCertBuilder.addExtension(Extension.subjectAlternativeName, false, new DERSequence(new ASN1Encodable[] {
//                new GeneralName(GeneralName.dNSName, "mydomain.local"),
//                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
//        }));

        X509CertificateHolder certHolder = certBuilder.build(this.rootCsrContentSigner);
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(certHolder);

        // Verify the issued cert signature against the root (issuer) cert
        certificate.verify(this.rootCert.getPublicKey(), BC_PROVIDER);
        X509Certificate[] certificateChain = new X509Certificate[2];
        certificateChain[0] = certificate;
        certificateChain[1] = this.rootCert;
//        byte [] privB = certKeyPair.getPrivate().getEncoded();
//        String privS = Hex.encodeHexString(privB);
//        byte [] privBnew = Hex.decodeHex(privS.toCharArray());
//        PrivateKeyInfo privateKeyInfo = PrivateKeyInfo.getInstance( privB);
//        PrivateKeyInfo privateKeyInfoNew = PrivateKeyInfo.getInstance( privBnew);
//        if (privateKeyInfo.getPrivateKey().equals(privateKeyInfoNew.getPrivateKey())) {
//            System.out.println("Ok");
//        }
//
//        ByteString byteString =  ByteString.of(privateKeyInfo.parsePrivateKey().toASN1Primitive().getEncoded());
        sslKeyStore.setKeyEntry(alias, certKeyPair.getPrivate(), keyStorePwd.toCharArray(), certificateChain);
    }

    void exportKeyPairToKeystoreFile(KeyStore sslKeyStore, String fileName, String storePass) throws Exception {
        if (new File(fileName).isFile()) {
            boolean isMoved = (new File(fileName)).renameTo(new File(fileName + "_1"));
            if (!isMoved) {
                throw new FileSystemException(fileName);
            }
//            try {
//                Path fileToMovePath = Paths.get(fileName);
//                Path targetPath = Paths.get(fileName + "_1");
//                Files.move(fileToMovePath, targetPath);
//            } catch (Exception e) {
//                throw new FileSystemException(fileName);
//            }
        }
        FileOutputStream keyStoreOs = new FileOutputStream(fileName);
//        PKCS12KeyStoreSpi - two
        sslKeyStore.store(keyStoreOs, storePass.toCharArray());

    }

    private void infoParamsServersX509(KeyStore sslKeyStore) {
        try {
            X509Certificate serverCertificate = (X509Certificate) sslKeyStore.getCertificate(this.context.getServerAlias());
            PrivateKey privateKey = (PrivateKey) sslKeyStore.getKey(this.context.getServerAlias(), this.context.getServerKeyStorePwd().toCharArray());
            PublicKey publicKey = serverCertificate.getPublicKey();
            log.info("\n- X509 Certificate Server (Hex): [{}]",
                    Hex.encodeHexString(serverCertificate.getEncoded()));
            this.infoParamsServerKey(publicKey, privateKey);
            serverCertificate = (X509Certificate) sslKeyStore.getCertificate(this.context.getBootstrapAlias());
            privateKey = (PrivateKey) sslKeyStore.getKey(this.context.getBootstrapAlias(), context.getServerKeyStorePwd().toCharArray());
            publicKey = serverCertificate.getPublicKey();
            log.info("\n- X509 Certificate Bootstrap Server (Hex): [{}]",
                    Hex.encodeHexString(serverCertificate.getEncoded()));
            this.infoParamsServerKey(publicKey, privateKey);
        } catch (CertificateEncodingException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            log.error("", e);
        }
    }

    private void infoParamsServerKey(PublicKey publicKey, PrivateKey privateKey) {
        /** Get x coordinate */
        byte[] x = ((ECPublicKey) publicKey).getW().getAffineX().toByteArray();
        if (x[0] == 0)
            x = Arrays.copyOfRange(x, 1, x.length);

        /** Get Y coordinate */
        byte[] y = ((ECPublicKey) publicKey).getW().getAffineY().toByteArray();
        if (y[0] == 0)
            y = Arrays.copyOfRange(y, 1, y.length);
        byte[] s1 = new byte[0];

        /** Get Curves params */
        String params = ((ECPublicKey) publicKey).getParams().toString();
        String privHex = Hex.encodeHexString(privateKey.getEncoded());
        log.info(" \n- Public Key (Hex): [{}] \n" +
                        "- Private Key (Hex): [{}], \n" +
                        "- public_x  : [{}] \n" +
                        "- public_y  : [{}] \n" +
                        "- private_s : [{}] \n" +
                        "- Elliptic Curve parameters  : [{}]",
                Hex.encodeHexString(publicKey.getEncoded()),
                Hex.encodeHexString(privateKey.getEncoded()),
                Hex.encodeHexString(x),
                Hex.encodeHexString(y),
                privHex.substring(privHex.length() - 64),
                params);
    }

    public static AsymmetricKeyParameter paramsPrivateKey(PrivateKey key) throws InvalidKeyException {
        if (key instanceof ECPrivateKey) {
            ECPrivateKey k = (ECPrivateKey) key;
            ECParameterSpec s = k.getParameters();

            if (s == null) {
                s = BouncyCastleProvider.CONFIGURATION.getEcImplicitlyCa();
            }

            if (k.getParameters() instanceof ECNamedCurveParameterSpec) {
                String name = ((ECNamedCurveParameterSpec) k.getParameters()).getName();
                return new ECPrivateKeyParameters(
                        k.getD(),
                        new ECNamedDomainParameters(ECNamedCurveTable.getOID(name),
                                s.getCurve(), s.getG(), s.getN(), s.getH(), s.getSeed()));
            } else {
                return new ECPrivateKeyParameters(
                        k.getD(),
                        new ECDomainParameters(s.getCurve(), s.getG(), s.getN(), s.getH(), s.getSeed()));
            }
        } else if (key instanceof java.security.interfaces.ECPrivateKey) {
            java.security.interfaces.ECPrivateKey privKey = (java.security.interfaces.ECPrivateKey) key;
            ECParameterSpec s = EC5Util.convertSpec(privKey.getParams());
            return new ECPrivateKeyParameters(
                    privKey.getS(),
                    new ECDomainParameters(s.getCurve(), s.getG(), s.getN(), s.getH(), s.getSeed()));
        } else {
            // see if we can build a key from key.getEncoded()
            try {
                byte[] bytes = key.getEncoded();

                if (bytes == null) {
                    throw new InvalidKeyException("no encoding for EC private key");
                }

                PrivateKey privateKey = BouncyCastleProvider.getPrivateKey(PrivateKeyInfo.getInstance(bytes));

                if (privateKey instanceof java.security.interfaces.ECPrivateKey) {
                    return ECUtil.generatePrivateKeyParameter(privateKey);
                }
            } catch (Exception e) {
                throw new InvalidKeyException("cannot identify EC private key: " + e.toString());
            }
        }

        throw new InvalidKeyException("can't identify EC private key.");
    }

    static void writeCertToFileBase64Encoded(Certificate certificate, String fileName) throws Exception {
        FileOutputStream certificateOut = new FileOutputStream(fileName);
        certificateOut.write("-----BEGIN CERTIFICATE-----".getBytes());
        certificateOut.write(Base64.encode(certificate.getEncoded()));
        certificateOut.write("-----END CERTIFICATE-----".getBytes());
        certificateOut.close();
    }

    static void convertPksToJks(String fileNameIn, String fileNameOut, String storePass) throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        KeyStore sslKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        FileInputStream is = new FileInputStream(fileNameIn);
//        File file= new File(fileName);
//        InputStream is = new InputStream() {
//            @Override
//            public int read() throws IOException {
//                return 0;
//            }
//        };
        sslKeyStore.load(is, storePass.toCharArray());
        FileOutputStream keyStoreOs = new FileOutputStream(fileNameOut);
//        sslKeyStore.load(keyStore.LoadStoreParameter);
        sslKeyStore.store(keyStoreOs, storePass.toCharArray());


    }

    private void verifyKeyStore(String fileInput, String keyStorePwd) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException {
        File keyStoreFile = new File(fileInput);
        InputStream inKeyStore = new FileInputStream(keyStoreFile);
        KeyStore keyStoreValue = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStoreValue.load(inKeyStore, keyStorePwd.toCharArray());
        for (Enumeration<String> aliases = keyStoreValue.aliases();
             aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();
            Certificate cert = keyStoreValue.getCertificate(alias);
            PrivateKey privateKey = (PrivateKey) keyStoreValue.getKey(alias, keyStorePwd.toCharArray());
            if (cert != null) {
                LwM2MClientContext.getParamsX509((X509Certificate) cert, alias, privateKey);
            }
        }
        keyStoreValue.aliases();
    }

    private void readParamsRootCert(String path, String storePass) {
        try {
            FileInputStream is = new FileInputStream(path);

            this.sslKeyStoreServer = KeyStore.getInstance(KeyStore.getDefaultType());
            this.sslKeyStoreServer.load(is, storePass.toCharArray());

            String alias = this.context.getRootAlias();

            Key key = this.sslKeyStoreServer.getKey(alias, storePass.toCharArray());
            java.security.interfaces.ECPrivateKey privKey = (java.security.interfaces.ECPrivateKey) key;
//            if (key instanceof PrivateKey) {
            // Get certificate of public key
            this.rootCert = (X509Certificate) this.sslKeyStoreServer.getCertificate(alias);

            // Get public key
            PublicKey publicKey = this.rootCert.getPublicKey();

            // Return a key pair
//                this.rootKeyPair = new KeyPair(publicKey, (PrivateKey) key);
            this.rootKeyPair = keyPairGenerator.generateKeyPair();
            JcaContentSignerBuilder csrBuilder = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC_PROVIDER);
            // Sign the new KeyPair with the root cert Private Key
//                this.rootCsrContentSigner = csrBuilder.build(this.rootKeyPair.getPrivate());
            this.rootCsrContentSigner = csrBuilder.build(this.rootKeyPair.getPrivate());
//            }
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException | OperatorCreationException e) {
            e.printStackTrace();
        }
    }
}
