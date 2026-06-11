/**
 * Copyright © 2016-2026 The Thingsboard Authors
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

public final class EntityNames {

    public static final String FORMAT_UUID = "UUID";

    private EntityNames() {
    }

    public static String toDeviceName(String nameFormat, int idx) {
        if (FORMAT_UUID.equalsIgnoreCase(nameFormat)) {
            return String.format("c1000000-0000-4000-8000-%012d", idx);
        }
        return "DW" + nameSuffix(idx);
    }

    /**
     * Gateway name carrying a per-tenant prefix. A gateway's MQTT access token == its name, and tokens are
     * globally unique in ThingsBoard, so giving each tenant a distinct prefix keeps concurrent multi-tenant
     * runs collision-free. A null/empty prefix yields the legacy {@code GW%08d} name.
     */
    public static String toGatewayName(String prefix, int idx) {
        if (prefix == null) {
            return toGatewayName(idx);
        }
        return prefix + toGatewayName(idx);
    }

    private static String toGatewayName(int idx) {
        return "GW" + nameSuffix(idx);
    }

    private static String nameSuffix(int idx) {
        return String.format("%8d", idx).replace(" ", "0");
    }
}
