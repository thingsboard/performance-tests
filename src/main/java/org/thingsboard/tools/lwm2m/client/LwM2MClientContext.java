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
import org.eclipse.californium.elements.util.ExecutorsUtil;
import org.eclipse.californium.elements.util.NamedThreadFactory;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.thingsboard.tools.service.shared.BaseLwm2mAPITest;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    private static final ScheduledExecutorService schedulerLwm2mTest = ExecutorsUtil.newScheduledThreadPool(100,
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
    private final String keyStoreType = "PKCS12";

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

    public File getCreatedNewX509() {
        Path pathModels = (new File(Paths.get(getBaseDirPath(), PATH_DATA, CREATED_KEY_STORE_DEFAULT_PATH, SH_CREATED_KEY_STORE_DEFAULT).toUri()).isDirectory()) ?
                Paths.get(getBaseDirPath(), PATH_DATA, CREATED_KEY_STORE_DEFAULT_PATH, SH_CREATED_KEY_STORE_DEFAULT) :
                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, CREDENTIALS_DIR, CREATED_KEY_STORE_DEFAULT_PATH, SH_CREATED_KEY_STORE_DEFAULT);
        return (pathModels != null) ? new File(pathModels.toUri()) : null;
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
     * -b: bootstrapAlias = "bootstrap";
     * -d: serverAlias = "server";
     * getCreatedNewX509() => SH_CREATED_KEY_STORE_DEFAULT = "lwM2M_credentials.sh";
     * -j: keyStoreServerFile = "serverKeyStore.jks";
     * -k: keyStoreClientFile = "clientKeyStore.jks";
     * -c: clientKeyStorePwd = "client_ks_password";
     * -w: serverKeyStorePwd = "server_ks_password";
     * @param start - deviceStartIdx
     * @param finish - deviceEndIdx
     * outPut: keyStoreType = "PKCS12";
     */
    public void generationX509Client(int start, int finish) {
        File path = this.getCreatedNewX509();
        if (path.isFile()) {
            try {

                String command = String.format(path.getAbsolutePath() + " -p %s -s %s -f %s -a %s -b %s -d %s -j %s -k %s -c %s -w %s",
                        this.getPrefEndPoint(X509), start, finish,
                        this.prefixClientAlias, this.bootstrapAlias, this.serverAlias, this.keyStoreServerFile, this.keyStoreClientFile,
                        this.clientKeyStorePwd, this.serverKeyStorePwd, this.keyStoreType);
                String[] cmdAndArgs = command.split(" ");
                ProcessBuilder processBuilder = new ProcessBuilder(cmdAndArgs);
                Process process = launchProcessX509Client(processBuilder);
            } catch (InterruptedException e) {
                log.error("Could not parse the resource definition file", e);
            }
        } else {
            log.error("[{}]Read SH for created new X509", path.getAbsoluteFile());
        }
        log.info("{} - [{}] have been created certificate x509 successfully!", "lwm2m", finish - start);
    }

    private static Process launchProcessX509Client(ProcessBuilder builder) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Process> process = new AtomicReference<>();
        StringBuilder output = new StringBuilder();
        final int[] last_ind = new int[1];
        final int[] prev_ind = new int[1];
        new Thread(() -> {
            try {
                process.set(builder.start());
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.get().getInputStream()));
                String line;
                last_ind[0] = 0;
                prev_ind[0] = 0;
                while ((line = reader.readLine()) != null) {
                    prev_ind[0] = last_ind[0];
                    output.append(line + "\n");
                    last_ind[0] = output.lastIndexOf("\n");
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
        }).start();
        ScheduledFuture<?> logScheduleFuture = schedulerLwm2mTest.scheduleAtFixedRate(() -> {
            try {
                log.info("[{} {}] have been created x509 so far...", "readLined: ", output.substring(prev_ind[0], last_ind[0]));
            } catch (Exception ignored) {
            }
        }, 0, 2, TimeUnit.SECONDS);
        latch.await();
        logScheduleFuture.cancel(true);
        return process.get();
    }

}

