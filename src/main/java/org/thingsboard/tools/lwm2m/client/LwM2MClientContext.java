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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.eclipse.californium.elements.util.ExecutorsUtil;
import org.eclipse.californium.elements.util.NamedThreadFactory;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.util.Hex;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.tools.service.shared.BaseLwm2mAPITest;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode.X509;

@Slf4j
@Component("LwM2MClientContext")
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientContext extends BaseLwm2mAPITest {

    /**
     * link InetSocketAddress used for <code>coaps://</code>
     */
    public final static String coapLink = "coap://";
    public final static String coapLinkSec = "coaps://";

    protected static final String deviceConfigNoSec = "credentials/deviceConfigNoSec.json";
    protected static final String deviceConfigKeys = "credentials/deviceConfigKeys.json";

    private static final ScheduledExecutorService schedulerLwm2mTest = ExecutorsUtil.newScheduledThreadPool(2000,
            new NamedThreadFactory("CreatedKeyStoreX509"));

    @Getter
    @Value("${lwm2m.noSec.enabled:}")
    protected boolean lwm2mNoSecEnabled;

    @Getter
    @Value("${lwm2m.psk.enabled:}")
    protected boolean lwm2mPSKEnabled;

    @Getter
    @Value("${lwm2m.psk.identity_sub:}")
    protected String lwm2mPSKIdentitySub;

    @Getter
    @Value("${lwm2m.rpk.enabled:}")
    protected boolean lwm2mRPKEnabled;

    @Getter
    @Value("${lwm2m.x509.enabled:}")
    protected boolean lwm2mX509Enabled;

    @Getter
    protected JsonNode nodeConfig = getConfigNoSec();

    @Getter
    protected JsonNode nodeConfigKeys = getDeviceConfigKeys();

    @Getter
    @Value("${lwm2m.noSec.server.host:}")
    private String lwm2mHostNoSec;

    @Getter
    @Value("${lwm2m.noSec.server.port:}")
    private int lwm2mPortNoSec;

    @Getter
    @Value("${lwm2m.noSec.bootStrap.enabled:}")
    private boolean lwm2mNoSecBootStrapEnabled;

    @Getter
    @Value("${lwm2m.noSec.bootStrap.host:}")
    private String lwm2mHostNoSecBootStrap;

    @Getter
    @Value("${lwm2m.noSec.bootStrap.port:}")
    private int lwm2mPortNoSecBootStrap;

    @Getter
    @Value("${lwm2m.psk.server.host:}")
    private String lwm2mHostPSK;

    @Getter
    @Value("${lwm2m.psk.server.port:}")
    private int lwm2mPortPSK;

    @Getter
    @Value("${lwm2m.psk.bootStrap.enabled:}")
    protected boolean lwm2mPSKBootStrapEnabled;

    @Getter
    @Value("${lwm2m.psk.bootStrap.host:}")
    private String lwm2mHostPSKBootStrap;

    @Getter
    @Value("${lwm2m.psk.bootStrap.port:}")
    private int lwm2mPortPSKBootStrap;

    @Getter
    @Value("${lwm2m.rpk.server.host:}")
    private String lwm2mHostRPK;

    @Getter
    @Value("${lwm2m.rpk.server.port:}")
    private int lwm2mPortRPK;

    @Getter
    @Value("${lwm2m.rpk.bootStrap.enabled:}")
    protected boolean lwm2mRPKBootStrapEnabled;

    @Getter
    @Value("${lwm2m.rpk.bootStrap.host:}")
    private String lwm2mHostRPKBootStrap;

    @Getter
    @Value("${lwm2m.rpk.bootStrap.port:}")
    private int lwm2mPortRPKBootStrap;

    @Getter
    @Value("${lwm2m.x509.server.host:}")
    private String lwm2mHostX509;

    @Getter
    @Value("${lwm2m.x509.server.port:}")
    private int lwm2mPortX509;

    @Getter
    @Value("${lwm2m.x509.bootStrap.enabled:}")
    protected boolean lwm2mX509BootStrapEnabled;

    @Getter
    @Value("${lwm2m.x509.bootStrap.host:}")
    private String lwm2mHostX509BootStrap;

    @Getter
    @Value("${lwm2m.x509.bootStrap.port:}")
    private int lwm2mPortX509BootStrap;

    @Getter
    private final Integer serverShortId = 123;

    @Getter
    private final Integer bootstrapShortId = 456;

    @Getter
    @Value("${lwm2m.client.host:}")
    private String clientHost;

    @Getter
    @Value("${lwm2m.client.startPort:}")
    private int clientStartPort;

    @Getter
    @Value("${lwm2m.client.communication_period:}")
    private Integer communicationPeriod;

    @Getter
    @Value("${lwm2m.client.request_timeout:}")
    private Integer requestTimeoutInMs;

    @Getter
    @Value("${lwm2m.recommended_ciphers:}")
    private boolean recommendedCiphers;

    @Getter
    @Value("${lwm2m.recommended_supported_groups:}")
    private boolean recommendedSupportedGroups;

    @Getter
    @Value("${lwm2m.prefix_end_point:}")
    private String prefixEndPoint;

    @Getter
    private final Integer lifetime = 300;

    @Getter
    private final Boolean reconnectOnUpdate = false;

    @Getter
    private final Boolean forceFullHandshake = false;

    @Getter
    private final Boolean supportOldFormat = false;

    @Getter
    private final String addAttributes = "ManufactureX509:Thingsboard;Model:\"Device LwM2M\";Serial:1111-2222-3333-000";

    @Getter
    private final String locationPos = "50.4501:30.5234";

    @Getter
    private final Float locationScaleFactor = 1.0F;

    @Getter
    @Setter
    private List<ObjectModel> modelsValue;

    @Getter
    @Setter
    private Path pathForCreatedNewX509;

    @Getter
    private final String BASE_DIR_PATH = System.getProperty("user.dir");

    @Getter
    private final String PATH_DATA = "data";

    @Getter
    private final String SRC_DIR = "src";

    @Getter
    private final String MAIN_DIR = "main";

    @Getter
    private final String RESOURCES_DIR = "resources";

    @Getter
    private final String CREDENTIALS_DIR = "credentials";

    @Getter
    private final String MODEL_PATH_DEFAULT = "models";

    @Getter
    private final String CREATED_KEY_STORE_DEFAULT_PATH = "shell";

    @Getter
    private final String SH_CREATED_KEY_STORE_DEFAULT = "lwM2M_credentials.sh";

    @Getter
    private final String keyStoreServerFile = "serverKeyStore.jks";

    @Getter
    private final String keyStoreClientFile = "clientKeyStore.jks";

    @Getter
    private final String clientKeyStorePwd = "client_ks_password";

    @Getter
    private final String serverKeyStorePwd = "server_ks_password";

    @Getter
    @Value("${lwm2m.x509.prefix_client_alias:}")
    private String prefixClientAlias;

    @Getter
    @Value("${lwm2m.x509.prefix_client:}")
    private String prefixClient;

    @Getter
    @Value("${lwm2m.x509.prefix_client_self_alias:}")
    private String prefixClientSelfAlias;

    @Getter
    private final String rootAlias = "rootCA";

    @Getter
    private final String bootstrapAlias = "bootstrap";

    @Getter
    private final String serverAlias = "server";

    @Getter
    @Setter
    private String serverKeyStorePathFile;

    @Getter
    @Setter
    private String clientKeyStorePathFile;

    @Getter
    @Setter
    private KeyStore serverKeyStoreValue;

    @Getter
    @Setter
    private KeyStore clientKeyStoreValue;

    @Getter
//    private final String keyStoreType = "PKCS12";
    private final String keyStoreType = "JKS";

    @Getter
    @Value("${lwm2m.x509.create_new_key_store_sh:}")
    private boolean createNewKeyStoreSh;

    @Getter
    @Value("${lwm2m.x509.create_new_key_store_java:}")
    private boolean createNewKeyStoreJava;

    @PostConstruct
    public void init() {
        modelsValue = ObjectLoader.loadDefault();
        File path = getPathModels();
        if (path.isDirectory()) {
            try {
                modelsValue.addAll(ObjectLoader.loadObjectsFromDir(path));
                log.info(" [{}] Models directory is a directory", path.getAbsoluteFile());
            } catch (Exception e) {
                log.error(" [{}] Could not parse the resource definition file", e.toString());
            }
        } else {
            log.error(" [{}] Read Models", path.getAbsoluteFile());
        }
        if (this.lwm2mX509Enabled || this.lwm2mX509BootStrapEnabled) {
            this.setClientKeyStore();
            this.setServerKeyStore();
        }
    }

    private File getPathModels() {
        Path pathModels = (new File(Paths.get(getBaseDirPath(), PATH_DATA, MODEL_PATH_DEFAULT).toUri()).isDirectory()) ?
                Paths.get(getBaseDirPath(), PATH_DATA, MODEL_PATH_DEFAULT) :
                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, MODEL_PATH_DEFAULT);
        return (pathModels != null) ? new File(pathModels.toUri()) : null;
    }

    private void setClientKeyStore() {
        Path clientKeyStorePath = (new File(Paths.get(getBaseDirPath(), PATH_DATA, RESOURCES_DIR, keyStoreClientFile).toUri()).isFile()) ?
                Paths.get(getBaseDirPath(), PATH_DATA, PATH_DATA, RESOURCES_DIR, keyStoreClientFile) :
                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, CREDENTIALS_DIR, keyStoreClientFile);
        File keyStoreFile = new File(clientKeyStorePath.toUri());
        if (keyStoreFile.isFile()) {
            try {
                InputStream inKeyStore = new FileInputStream(keyStoreFile);
                this.clientKeyStoreValue = KeyStore.getInstance(this.keyStoreType);
                this.clientKeyStoreValue.load(inKeyStore, this.clientKeyStorePwd.toCharArray());
            } catch (CertificateException | NoSuchAlgorithmException | IOException | KeyStoreException e) {
                log.error("[{}] Unable to load KeyStore  files client, folder is not a directory", e.getMessage());
                this.clientKeyStoreValue = null;
            }
            log.info("[{}] Load KeyStore  files client, folder is a directory", keyStoreFile.getAbsoluteFile());
        } else {
            log.error("[{}] Unable to load KeyStore  files client, is not a file", keyStoreFile.getAbsoluteFile());
            this.clientKeyStoreValue = null;
        }
    }

    private void setServerKeyStore() {
        Path serverKeyStorePath = (new File(Paths.get(getBaseDirPath(), PATH_DATA, RESOURCES_DIR, keyStoreServerFile).toUri()).isFile()) ?
                Paths.get(getBaseDirPath(), PATH_DATA, PATH_DATA, RESOURCES_DIR, keyStoreServerFile) :
                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, CREDENTIALS_DIR, keyStoreServerFile);
        File keyStoreFile = new File(serverKeyStorePath.toUri());
        if (keyStoreFile.isFile()) {
            try {
                InputStream inKeyStore = new FileInputStream(keyStoreFile);
                this.serverKeyStoreValue = KeyStore.getInstance(this.keyStoreType);
                this.serverKeyStoreValue.load(inKeyStore, this.serverKeyStorePwd.toCharArray());
            } catch (CertificateException | NoSuchAlgorithmException | IOException | KeyStoreException e) {
                log.error("[{}] Unable to load KeyStore  files server, folder is not a directory", e.getMessage());
                this.serverKeyStoreValue = null;
            }
            log.info("[{}] Load KeyStore  files server, folder is a directory", keyStoreFile.getAbsoluteFile());
        } else {
            log.error("[{}] Unable to load KeyStore  files server, is not a file", keyStoreFile.getAbsoluteFile());
            this.serverKeyStoreValue = null;
        }
    }

    public Path getPathForCreatedNewX509() {
        return (this.pathForCreatedNewX509 != null) ? this.pathForCreatedNewX509 :
                new File(Paths.get(getBaseDirPath(), PATH_DATA, CREATED_KEY_STORE_DEFAULT_PATH).toUri()).isDirectory() ?
                        Paths.get(getBaseDirPath(), PATH_DATA, CREATED_KEY_STORE_DEFAULT_PATH) :
                        new File(Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, CREDENTIALS_DIR, CREATED_KEY_STORE_DEFAULT_PATH).toUri()).isDirectory() ?
                                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, CREDENTIALS_DIR, CREATED_KEY_STORE_DEFAULT_PATH) :
                                Paths.get(getBaseDirPath());
    }

    public File getPathForCreatedNewX509Sh() {
        return (new File(Paths.get(this.getPathForCreatedNewX509().toUri().getPath(), SH_CREATED_KEY_STORE_DEFAULT).toUri()).isFile()) ?
                new File(Paths.get(this.getPathForCreatedNewX509().toUri().getPath(), SH_CREATED_KEY_STORE_DEFAULT).toUri()) : null;
    }

    private String getBaseDirPath() {
        Path FULL_FILE_PATH;
        if (BASE_DIR_PATH.endsWith("bin")) {
            FULL_FILE_PATH = Paths.get(BASE_DIR_PATH.replaceAll("bin$", ""));
        } else if (BASE_DIR_PATH.endsWith("conf")) {
            FULL_FILE_PATH = Paths.get(BASE_DIR_PATH.replaceAll("conf$", ""));
        } else {
            FULL_FILE_PATH = Paths.get(BASE_DIR_PATH);
        }
        return FULL_FILE_PATH.toUri().getPath();
    }

    private JsonNode getConfigNoSec() {
        try {
            return loadJsonResource(deviceConfigNoSec, JsonNode.class);
        } catch (IOException e) {
            log.error("Error read ConfigNoSec from [{}] [{}]", deviceConfigNoSec, e.toString());
            return null;
        }
    }

    private JsonNode getDeviceConfigKeys() {
        try {
            return loadJsonResource(deviceConfigKeys, JsonNode.class);
        } catch (IOException e) {
            log.error("Error read ConfigKeys from [{}] [{}]", deviceConfigKeys, e.toString());
            return null;
        }
    }


    public String getEndPoint(int numberClient, LwM2MSecurityMode mode) {
        return getPrefEndPoint(mode) + String.format("%8d", numberClient).replace(" ", "0");
    }

    private String getPrefEndPoint(LwM2MSecurityMode mode) {
        return this.prefixEndPoint + LwM2MSecurityMode.fromNameCamelCase(mode.code);
    }

    /**
     * PrefixClientAlias = client_alias_
     * ClientAlias = client_alias_00000000
     *
     * @param numberClient
     * @return
     */
    public String getClientAlias(int numberClient) {
        return this.prefixClientAlias + String.format("%8d", numberClient).replace(" ", "0");
    }

    public Map<String, String> getAddAttrs(String addAttrs) {
        Map<String, String> additionalAttributes = new HashMap<>();
        Pattern p1 = Pattern.compile("(.*):\"(.*)\"");
        Pattern p2 = Pattern.compile("(.*):(.*)");
        String[] values = addAttrs.split(";");
        for (String v : values) {
            Matcher m = p1.matcher(v);
            if (m.matches()) {
                String attrName = m.group(1);
                String attrValue = m.group(2);
                additionalAttributes.put(attrName, attrValue);
            } else {
                m = p2.matcher(v);
                if (m.matches()) {
                    String attrName = m.group(1);
                    String attrValue = m.group(2);
                    additionalAttributes.put(attrName, attrValue);
                } else {
                    log.error("Invalid syntax for additional attributes : [{}]", v);
                    return null;
                }
            }
        }
        return additionalAttributes;
    }

    /**
     * prefixEndPoint = "Lw";
     * -p: getPrefEndPoint = "LwX509";
     * -s: start = deviceStartIdx;
     * -f: finish = deviceEndIdx;
     * -a: prefixClientAlias = "client_alias_";
     * -e: prefixClientSelfAlias = "client_self_alias_";
     * -b: bootstrapAlias = "bootstrap";
     * -d: serverAlias = "server";
     * getCreatedNewX509() => SH_CREATED_KEY_STORE_DEFAULT = "lwM2M_credentials.sh";
     * -j: keyStoreServerFile = "serverKeyStore.jks";
     * -k: keyStoreClientFile = "clientKeyStore.jks";
     * -c: clientKeyStorePwd = "client_ks_password";
     * -w: serverKeyStorePwd = "server_ks_password";
     *
     * @param start  - deviceStartIdx
     * @param finish - deviceEndIdx
     *               outPut: keyStoreType = "PKCS12";
     */
    public void generationX509ClientSh(int start, int finish) {
        File fileSh = this.getPathForCreatedNewX509Sh();
        if (fileSh != null) {
            try {

                String command = String.format(fileSh.getAbsolutePath() + " -p %s -s %s -f %s -a %s -e %s -b %s -d %s -j %s -k %s -c %s -w %s",
                        this.getPrefEndPoint(X509), start, finish,
                        this.prefixClientAlias, this.prefixClientSelfAlias, this.bootstrapAlias, this.serverAlias, this.keyStoreServerFile, this.keyStoreClientFile,
                        this.clientKeyStorePwd, this.serverKeyStorePwd, this.keyStoreType);
                String[] cmdAndArgs = command.split(" ");
                ProcessBuilder processBuilder = new ProcessBuilder(cmdAndArgs);
                Process process = launchProcessX509Client(processBuilder);
            } catch (IOException | InterruptedException e) {
                log.error("Could not parse the resource definition file", e);
            }
        } else {
            log.error("[{}]Read SH for created new X509", fileSh.getAbsoluteFile());
        }
        log.info("{} - [{}] have been created certificate x509 successfully!", "lwm2m", finish - start);
    }

    private static Process launchProcessX509Client(ProcessBuilder builder) throws InterruptedException, IOException {
        Process process = builder.start();
        LineIterator lines = IOUtils.lineIterator(process.getInputStream(), "UTF-8");
        while (lines.hasNext()) {
            String line = lines.next();
            log.info("line: {}", line + "\n");
        }
        int exitVal = process.waitFor();
        if (exitVal == 0) {
            log.info("Success creating x509 new KeyStore!");
        } else {
            log.error("Error x509! count last process: [{}]", exitVal);
        }

       /* new Thread(() -> {
            try {
                process.set(builder.start());
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.get().getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    lineInfo.set(line);
//                    output.append(line + "\n");
                    log.info("line: {}", line + "\n");
                }
                int exitVal = process.get().waitFor();
                if (exitVal == 0) {
                    log.info("Success creating x509 new KeyStore!");
                } else {
                    log.error("Error x509! count last process: [{}]", exitVal);
                }
                latch.countDown();
            } catch (IOException | InterruptedException e) {
                log.error("", e);
            } finally {
                latch.countDown();
            }
        }).start();*/
      /*  ScheduledFuture<?> logScheduleFuture = schedulerLwm2mTest.scheduleAtFixedRate(() -> {
            try {
                log.info("[{} {}] have been created x509 so far...", "readLined: ", lineInfo.get());
            } catch (Exception ignored) {
            }
        }, 0, 2, TimeUnit.SECONDS); */
        //  latch.await();
        //  logScheduleFuture.cancel(true);
        return process;
    }

    /**
     * 2:47:47.119 [pool-3-thread-2] INFO  o.t.t.l.secure.LwM2MSecurityStore - Client uses X509 :
     * X509 Certificate (Hex): [3082021b308201bfa003020102020442399826300c06082a8648ce3d04030205003075310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f6172643120301e060355040313176e69636b2d5468696e6773626f61726420726f6f7443413020170d3231303131363138313235355a180f32313230313232333138313235355a306c310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f617264311730150603550403130e4c775835303930303030303030303059301306072a8648ce3d020106082a8648ce3d03010703420004e6f349e1275cebeabb417afe7452a975f81d240e21e8f6763d887486ab6431a1c4afa90ca1de887a179a89c92a98c50e420c5813177e4a98137f74e31dff37e1a3423040301d0603551d0e0416041473cad8ff526a7511d52e950dff661c48be517ada301f0603551d2304183016801442d6b0539a6adad92783f43c8928b65e0f2a7eed300c06082a8648ce3d04030205000348003045022100fc48e91c391b6282d18b3cac9903072294f040cf71b68a4e6b48dfb3fc29eff20220167baefebee8b84e91e94942e9475ddfe880e04afc2be1a311c6e77e4ad99b08]
     * getSigAlgName: [SHA256withECDSA]
     * getSigAlgOID: [1.2.840.10045.4.3.2]
     * type: [X.509]
     * IssuerDN().getName: [CN=nick-Thingsboard rootCA, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     * SubjectDN().getName: [CN=LwX50900000000, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     *
     * 12:47:53.386 [pool-3-thread-2] INFO  o.t.t.l.secure.LwM2MSecurityStore - Server uses X509 :
     * X509 Certificate (Hex): [3082023d308201e1a00302010202041ca6324e300c06082a8648ce3d04030205003075310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f6172643120301e060355040313176e69636b2d5468696e6773626f61726420726f6f7443413020170d3231303131363138313235325a180f32313230313232333138313235325a30818d310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f617264313830360603550403132f6e69636b2d5468696e6773626f61726420736572766572204c774d324d207369676e656420627920726f6f742043413059301306072a8648ce3d020106082a8648ce3d0301070342000426d5de0a6becabdb9caba4375649245e7a27a0013f1f30432376e83cea16ea52af7c4255571ee1de644495c506953b30773d4172f8deb745022decf08a9a4427a3423040301d0603551d0e04160414f3156f006dfe60781704c20b00620c68d2451952301f0603551d2304183016801442d6b0539a6adad92783f43c8928b65e0f2a7eed300c06082a8648ce3d04030205000348003045022100aa939522badaba8825539de7539d4ec1d61b4e04f0e49c2bcdf9d0dc7e2a282f022008e7a9a6da8ca2d698a2af3b45fb0694d72f1c0b5b24448131f2d92490fe5aa8]
     * getSigAlgName: [SHA256withECDSA]
     * getSigAlgOID: [1.2.840.10045.4.3.2]
     * type: [X.509]
     * IssuerDN().getName: [CN=nick-Thingsboard rootCA, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     * SubjectDN().getName: [CN=nick-Thingsboard server LwM2M signed by root CA, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     *
     * 12:47:53.386 [pool-3-thread-2] INFO  o.t.t.l.secure.LwM2MSecurityStore - Bootstrap uses X509 :
     * X509 Certificate (Hex): [30820248308201eba00302010202046fdab804300c06082a8648ce3d04030205003075310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f6172643120301e060355040313176e69636b2d5468696e6773626f61726420726f6f7443413020170d3231303131363138313235335a180f32313230313232333138313235335a308197310b3009060355040613025553310b3009060355040813024341310b300906035504071302534631143012060355040a130b5468696e6773626f61726431143012060355040b130b5468696e6773626f61726431423040060355040313396e69636b2d5468696e6773626f61726420626f6f74737472617020736572766572204c774d324d207369676e656420627920726f6f742043413059301306072a8648ce3d020106082a8648ce3d03010703420004404400979de1ee59d382f2aba83beebf41399909db67c6b1ea3c0dd231262a27b0b5b4df0572a2d389a8c4b3f66ae302db98759f5d9bb02ded0cc9be779b756ca3423040301d0603551d0e0416041466e2cb1be2a7528469afcb2fbcfa1bf4080743f9301f0603551d2304183016801442d6b0539a6adad92783f43c8928b65e0f2a7eed300c06082a8648ce3d040302050003490030460221009d2746c1b49bc388eebd5a06fde13dcfcc6723b4438429463382a70285d1f007022100a54bf8f18b18282a7e7a1488f849734ab4395d2671d204f14801cd3022986744]
     * getSigAlgName: [SHA256withECDSA]
     * getSigAlgOID: [1.2.840.10045.4.3.2]
     * type: [X.509]
     * IssuerDN().getName: [CN=nick-Thingsboard rootCA, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     * SubjectDN().getName: [CN=nick-Thingsboard bootstrap server LwM2M signed by root CA, OU=Thingsboard, O=Thingsboard, L=SF, ST=CA, C=US]
     */

    static void getParamsX509(X509Certificate certificate, String whose) {
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

