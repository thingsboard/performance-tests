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
package org.thingsboard.tools.service.shared.onboarding;

/**
 * One entity's onboarding step for the staggered mode: bring a single gateway/device fully online
 * (connect -> optionally announce sub-devices -> subscribe -> start its own telemetry cadence).
 * Implementations are mode-specific; the engine only paces the calls.
 */
public interface EntityLifecycle {

    /** Number of entities to onboard; the engine drives indices [0, entityCount()). */
    int entityCount();

    /**
     * Onboard entity {@code idx} synchronously. Must throw on failure — the engine counts it as
     * failed, releases its slot, and continues (never blocks the ramp).
     */
    void onboard(int idx) throws Exception;
}
