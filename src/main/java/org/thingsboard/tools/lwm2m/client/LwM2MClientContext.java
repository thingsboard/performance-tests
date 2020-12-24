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
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
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
import java.security.cert.CertificateException;
import java.util.List;

@Slf4j
@Component("LwM2MClientContext")
@ConditionalOnProperty(prefix = "device", value = "api", havingValue = "LWM2M")
public class LwM2MClientContext extends BaseLwm2mAPITest {

    //    @Value("#{${lwm2m.server.secure.bind_port_cert:} ?: 5688}")
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
    private final Boolean oldCiphers = false;

    @Getter
    private final Integer lifetime= 300;

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
    private final String clientKeyStorePwd = "client_ks_password";

    @Getter
    private final String serverKeyStorePwd = "server_ks_password";

    @Getter
    private final String clientAlias = "client";

    @Getter
    private final String bootstrapAlias = "bootstrap";

    @Getter
    private final String serverAlias = "server";

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
    private final String MODEL_PATH_DEFAULT = "models";

    @Getter
    private final String KEY_STORE_DEFAULT_RESOURCE_PATH = "credentials";


    @Getter
    private JsonNode keyStoreValue;

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
    }

    private File getPathModels() {
        Path pathModels = (new File(Paths.get(getBaseDirPath(), PATH_DATA, MODEL_PATH_DEFAULT).toUri()).isDirectory()) ?
                Paths.get(getBaseDirPath(), PATH_DATA, MODEL_PATH_DEFAULT) :
                Paths.get(getBaseDirPath(), SRC_DIR, MAIN_DIR, RESOURCES_DIR, MODEL_PATH_DEFAULT);
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

}
