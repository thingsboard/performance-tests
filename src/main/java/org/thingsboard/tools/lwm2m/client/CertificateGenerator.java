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
import org.apache.commons.lang3.tuple.Pair;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;

@Slf4j
@Component("CertificateGenerator")
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class CertificateGenerator {

    private static final String BC_PROVIDER = BouncyCastleProvider.PROVIDER_NAME;
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
    private static final String keyStorePrefixPfx = ".pfx";
    private static final String keyStorePrefixJks= ".jks";



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

    public void generationX509WithRootAndJks (int start, int finish) throws Exception {
        this.generationX509RootJava();
        this.generationX509(this.sslKeyStoreServer, this.context.getServerAlias(),
                context.getLwm2mHostX509() + " " + this.context.getServerAlias(),
                context.getServerKeyStorePwd());
        this.generationX509(this.sslKeyStoreServer, this.context.getBootstrapAlias(),
                context.getLwm2mHostX509() + " " + this.context.getBootstrapAlias(),
                context.getServerKeyStorePwd());

        String fileNameJks = this.context.getServerAlias() + this.fileNameKeyStore + this.keyStorePrefixJks;
        this.exportKeyPairToKeystoreFile(this.sslKeyStoreServer, context.getPathForCreatedNewX509().toUri().getPath() +  fileNameJks, context.getServerKeyStorePwd());
//        this.verifyKeyStore (fileNameJks, context.getServerKeyStorePwd());

        for (int i = start; i < finish; i++) {
            this.generationX509(this.sslKeyStoreClient,
                    this.context.getClientAlias(i), this.context.getEndPoint(i, LwM2MSecurityMode.X509),
                    context.getClientKeyStorePwd());
        }
        fileNameJks = this.context.getPrefixClient() + this.fileNameKeyStore + this.keyStorePrefixJks;
        this.exportKeyPairToKeystoreFile(this.sslKeyStoreClient, context.getPathForCreatedNewX509().toUri().getPath() +  fileNameJks, context.getClientKeyStorePwd());
        this.verifyKeyStore (context.getPathForCreatedNewX509().toUri().getPath() +  fileNameJks, context.getClientKeyStorePwd());
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
        sslKeyStore.setKeyEntry(alias, certKeyPair.getPrivate(), keyStorePwd.toCharArray(), certificateChain);
    }

//    public void generationX509Java(int start, int finish) throws Exception {
//
//        // Generate a new KeyPair and sign it using the Root Cert Private Key
//        // by generating a CSR (Certificate Signing Request)
//        // Creating server certificate signed by root CA...
////        X500Name issuedCertSubject = new X500Name("CN=issued-cert");
//        X500Name serverCertSubject = new X500Name("CN=" + context.getLwm2mHostX509() + " " + context.getServerAlias() + this.NAME_CERT_GEO_SUFFIX);
//        X500Name bootstrapCertSubject = new X500Name("CN=" + context.getLwm2mHostX509() + " " + context.getBootstrapAlias() + this.NAME_CERT_GEO_SUFFIX);
//
//        BigInteger serverCertSerialNum = new BigInteger(Long.toString(new SecureRandom().nextLong()));
//        BigInteger bootstrapCertSerialNum = new BigInteger(Long.toString(new SecureRandom().nextLong()));
//
//        KeyPair serverCertKeyPair = keyPairGenerator.generateKeyPair();
//        KeyPair bootstrapCertKeyPair = keyPairGenerator.generateKeyPair();
//
//        PKCS10CertificationRequestBuilder serverP10Builder = new JcaPKCS10CertificationRequestBuilder(serverCertSubject, serverCertKeyPair.getPublic());
//        PKCS10CertificationRequestBuilder bootstrapP10Builder = new JcaPKCS10CertificationRequestBuilder(bootstrapCertSubject, bootstrapCertKeyPair.getPublic());
//
//        // Sign the new KeyPair with the root cert Private Key
//        PKCS10CertificationRequest serverCsr = serverP10Builder.build(this.rootCsrContentSigner);
//        PKCS10CertificationRequest bootstrapCsr = bootstrapP10Builder.build(this.rootCsrContentSigner);
//
//
//        // Use the Signed KeyPair and CSR to generate an issued Certificate
//        // Here serial number is randomly generated. In general, CAs use
//        // a sequence to generate Serial number and avoid collisions
//        X509v3CertificateBuilder serverCertBuilder = new X509v3CertificateBuilder(this.rootCertIssuer, serverCertSerialNum, this.startDate, this.endDate, serverCsr.getSubject(), serverCsr.getSubjectPublicKeyInfo());
//        X509v3CertificateBuilder bootstrapCertBuilder = new X509v3CertificateBuilder(this.rootCertIssuer, bootstrapCertSerialNum, this.startDate, this.endDate, bootstrapCsr.getSubject(), bootstrapCsr.getSubjectPublicKeyInfo());
//
//        JcaX509ExtensionUtils serverCertExtUtils = new JcaX509ExtensionUtils();
//        JcaX509ExtensionUtils bootstrapCertExtUtils = new JcaX509ExtensionUtils();
//
//        // Add Extensions
//        // Use BasicConstraints to say that this Cert is not a CA
////        issuedCertBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
//
//        // Add Issuer cert identifier as Extension
//        serverCertBuilder.addExtension(Extension.authorityKeyIdentifier, false, serverCertExtUtils.createAuthorityKeyIdentifier(this.rootCert));
//        serverCertBuilder.addExtension(Extension.subjectKeyIdentifier, false, serverCertExtUtils.createSubjectKeyIdentifier(serverCsr.getSubjectPublicKeyInfo()));
//
//        bootstrapCertBuilder.addExtension(Extension.authorityKeyIdentifier, false, bootstrapCertExtUtils.createAuthorityKeyIdentifier(this.rootCert));
//        bootstrapCertBuilder.addExtension(Extension.subjectKeyIdentifier, false, bootstrapCertExtUtils.createSubjectKeyIdentifier(bootstrapCsr.getSubjectPublicKeyInfo()));
//
//        // Add intended key usage extension if needed
////        serverCertBuilder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.keyEncipherment));
//
//        // Add DNS name is cert is to used for SSL
////        serverCertBuilder.addExtension(Extension.subjectAlternativeName, false, new DERSequence(new ASN1Encodable[] {
////                new GeneralName(GeneralName.dNSName, "mydomain.local"),
////                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
////        }));
//
//        X509CertificateHolder serverCertHolder = serverCertBuilder.build(this.rootCsrContentSigner);
//        X509Certificate serverCert = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(serverCertHolder);
//        X509CertificateHolder bootstrapCertHolder = bootstrapCertBuilder.build(this.rootCsrContentSigner);
//        X509Certificate bootstrapCert = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(bootstrapCertHolder);
//
//        // Verify the issued cert signature against the root (issuer) cert
//        serverCert.verify(this.rootCert.getPublicKey(), BC_PROVIDER);
//        bootstrapCert.verify(this.rootCert.getPublicKey(), BC_PROVIDER);
//        context.getParamsX509(serverCert, "server");
//        context.getParamsX509(bootstrapCert, "bootstrap");
////        writeCertToFileBase64Encoded(serverCert, "server-cert.cer");
////        exportKeyPairToKeystoreFile(issuedCertKeyPair, issuedCert, "issued-cert", "issued-cert.pfx", "PKCS12", "pass");
//        String fileNameServerPfx = context.getServerAlias() + this.fileNameKeyStore + this.keyStorePrefixPfx;
//        String fileNameServerJks = context.getServerAlias() + this.fileNameKeyStore + this.keyStorePrefixJks;
//        String alias = context.getServerAlias();
//        KeyPair keyPair = serverCertKeyPair;
//        Certificate certificate = serverCert;
//        this.sslKeyStoreServer.setKeyEntry(alias, keyPair.getPrivate(), context.getServerKeyStorePwd().toCharArray(), new Certificate[]{certificate});
//        alias = context.getBootstrapAlias();
//        keyPair = bootstrapCertKeyPair;
//        certificate = bootstrapCert;
//        this.sslKeyStoreServer.setKeyEntry(alias, keyPair.getPrivate(), context.getServerKeyStorePwd().toCharArray(), new Certificate[]{certificate});
////        exportKeyPairToKeystoreFile(fileNameServerPfx, context.getServerKeyStorePwd());
//        exportKeyPairToKeystoreFile(this.sslKeyStoreServer, fileNameServerJks, context.getServerKeyStorePwd());
////        exportKeyPairToKeystoreFile(bootstrapCertKeyPair, bootstrapCert, context.getBootstrapAlias(), "serverKeyStore.pfx", context.getServerKeyStorePwd());
////        convertPksToJks(fileNameServerPfx, fileNameServerJks, context.getServerKeyStorePwd());
//    }

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

    private void verifyKeyStore (String fileInput, String keyStorePwd) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException {
        File keyStoreFile = new File(fileInput);
        InputStream inKeyStore = new FileInputStream(keyStoreFile);
        KeyStore keyStoreValue = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStoreValue.load(inKeyStore, keyStorePwd.toCharArray());
        int size = keyStoreValue.size();
        for (Enumeration<String> aliases = keyStoreValue.aliases();
             aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();
            Certificate cert = keyStoreValue.getCertificate(alias);
            PrivateKey privKey = (PrivateKey) keyStoreValue.getKey(alias, keyStorePwd.toCharArray());
            if (cert != null) {

                context.getParamsX509((X509Certificate) cert, alias);
//                try {
//                    cert.verify(trustedCert.getPublicKey());
//                    return new Pair<>(name, trustedCert);
//                } catch (Exception e) {
//                    // Not verified, skip to the next one
//                }
            }
        }
        keyStoreValue.aliases();

//        Certificate clientCertificate = (X509Certificate) context.getClientKeyStoreValue().getCertificate(context.getClientAlias(numberClient));
//        PrivateKey clientPrivKey = (PrivateKey) context.getClientKeyStoreValue().getKey(context.getClientAlias(numberClient), context.getClientKeyStorePwd().toCharArray());

    }
}
