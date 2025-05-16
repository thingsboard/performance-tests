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
package org.thingsboard.tools.service.shared;

import org.thingsboard.tools.lwm2m.client.LwM2MSecurityMode;

public enum Lwm2mProfile {
    PSK(0, "lwm2mProfilePsk"),
    RPK(1, "lwm2mProfileRpk"),
    X509(2, "lwm2mProfileX509"),
    NO_SEC(3, "lwm2mProfileNoSec");

    public int code;
    public String profileName;

    Lwm2mProfile(int code, String profileName) {
        this.code = code;
        this.profileName = profileName;
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
}
