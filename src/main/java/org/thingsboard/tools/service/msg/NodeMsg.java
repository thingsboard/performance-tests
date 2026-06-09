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
package org.thingsboard.tools.service.msg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A generated message as a live JSON tree plus its alarm flag, before serialization.
 * The {@code node} is NOT defensively copied: the caller owns it and may mutate or merge it
 * (gateway batch mode merges several devices' nodes into one publish). Generators must therefore
 * return a freshly created node per call.
 */
@AllArgsConstructor
public class NodeMsg {

    @Getter
    private final ObjectNode node;
    @Getter
    private final boolean triggersAlarm;

    public NodeMsg(ObjectNode node) {
        this(node, false);
    }
}
