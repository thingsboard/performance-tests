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
package org.thingsboard.tools.lwm2m.client;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.LinkedList;

public enum LwM2MSecurityMode {

    PSK(0, "psk"),
    RPK(1, "rpk"),
    X509(2, "x509"),
    NO_SEC(3, "no_sec"),
    X509_EST(4, "x509_est"),
    REDIS(5, "redis"),
    DEFAULT_MODE(100, "default_mode");

    public int code;
    public String modeName;

    LwM2MSecurityMode(int code, String modeName) {
        this.code = code;
        this.modeName = modeName;
    }

    public static LwM2MSecurityMode fromSecurityMode(long code) {
        return fromSecurityMode((int) code);
    }

    public static LwM2MSecurityMode fromSecurityMode(int code) {
        for (LwM2MSecurityMode sm : LwM2MSecurityMode.values()) {
            if (sm.code == code) {
                return sm;
            }
        }
        throw new IllegalArgumentException(String.format("Unsupported security code : %d", code));
    }

    public static String fromNameCamelCase(int code) {
        for (LwM2MSecurityMode sm : LwM2MSecurityMode.values()) {
            if (sm.code == code) {
                String str = convertCamelCase (sm.modeName);
                return str.substring(0, 1).toUpperCase() + str.substring(1);
            }
        }
        throw new IllegalArgumentException(String.format("Unsupported security code : %d", code));
    }

    public static String convertCamelCase (String s) {
        LinkedList<String> linkedListOut = new LinkedList<>();
        LinkedList<String> linkedList = new LinkedList<String>((Arrays.asList(s.split(" |_"))));
        linkedList.forEach(str-> {
            String strOut = str.replaceAll("\\W", "").toUpperCase();
            if (strOut.length()>1) linkedListOut.add(strOut.charAt(0) + strOut.substring(1).toLowerCase());
            else linkedListOut.add(strOut);
        });
        linkedListOut.set(0, (linkedListOut.get(0).substring(0, 1).toLowerCase() + linkedListOut.get(0).substring(1)));
        return StringUtils.join(linkedListOut, "");
    }
}
