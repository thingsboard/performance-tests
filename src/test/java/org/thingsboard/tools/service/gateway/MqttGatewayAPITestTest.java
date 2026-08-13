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
package org.thingsboard.tools.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.mqtt.DeviceClient;
import org.thingsboard.tools.service.shared.RestClientService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Same broker-free idiom as MqttGatewayBatchAPITestTest: this test class IS the SUT (extends
// MqttGatewayAPITest) so it can reach the package-private/protected inherited state
// (gatewayStartIdx/gatewayEndIdx/deviceStartIdx/deviceEndIdx/mqttClients/deviceClients/seed/...) without
// a Spring context or a real MQTT broker. init()/@PostConstruct is never invoked, so every field a test
// relies on is set explicitly.
class MqttGatewayAPITestTest extends MqttGatewayAPITest {

    ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = mock(ScheduledExecutorService.class);
        RestClientService rcs = mock(RestClientService.class);
        when(rcs.getScheduler()).thenReturn(scheduler);
        restClientService = rcs;
    }

    // --- prepareStaggeredModel() vs mapDevicesToGatewayClientConnections(): same device assignment ---

    @Test
    void prepareStaggeredModelAssignsDevicesIdenticallyToPhasedMapping() {
        gatewayStartIdx = 0;
        gatewayEndIdx = 3;
        deviceStartIdx = 0;
        deviceEndIdx = 7; // not a multiple of 3: exercises the uneven-remainder case too

        // PHASED reference: 3 already-connected gateway clients, grouped by mqttClients position.
        MqttClient gw0 = mock(MqttClient.class);
        MqttClient gw1 = mock(MqttClient.class);
        MqttClient gw2 = mock(MqttClient.class);
        mqttClients.add(gw0);
        mqttClients.add(gw1);
        mqttClients.add(gw2);
        clientNames.put(gw0, "GW00000000");
        clientNames.put(gw1, "GW00000001");
        clientNames.put(gw2, "GW00000002");
        mapDevicesToGatewayClientConnections();

        Map<MqttClient, List<String>> phasedGroups = new HashMap<>();
        for (DeviceClient dc : deviceClients) {
            phasedGroups.computeIfAbsent(dc.getMqttClient(), k -> new ArrayList<>()).add(dc.getDeviceName());
        }

        // STAGGERED model: index-based, built without any connected client.
        prepareStaggeredModel();

        List<MqttClient> byPosition = List.of(gw0, gw1, gw2);
        for (int gwIdx = 0; gwIdx < 3; gwIdx++) {
            List<String> staggered = staggeredGatewayDeviceNames.getOrDefault(gwIdx, List.of());
            List<String> phased = phasedGroups.getOrDefault(byPosition.get(gwIdx), List.of());
            assertThat(staggered).containsExactlyElementsOf(phased);
        }
        assertThat(staggeredGatewayNames).containsExactly("GW00000000", "GW00000001", "GW00000002");
    }

    // --- scheduleGatewayTelemetry(): MPS-derived period + jitter, and the no-timer guard ---

    @Test
    void telemetryPeriodIsDerivedFromEntityCountAndMessagesPerSecond() {
        gatewayStartIdx = 0;
        gatewayEndIdx = 10; // entityCount = 10
        testMessagesPerSecond = 5;
        seed = 0;

        // Raw type (not ScheduledFuture<?>): a wildcard-typed mock hits a generic-capture mismatch on
        // thenReturn (javac can't unify two independent "capture of ?" instantiations).
        @SuppressWarnings({"unchecked", "rawtypes"})
        ScheduledFuture fakeFuture = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(fakeFuture);

        MqttClient client = mock(MqttClient.class);
        scheduleGatewayTelemetry(3, client, "GW00000003", List.of("DW00000000"));

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> periodCaptor = ArgumentCaptor.forClass(Long.class);
        verify(scheduler).scheduleAtFixedRate(any(), delayCaptor.capture(), periodCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        long expectedPeriodMs = (10 * 1000L) / 5; // entityCount * 1000 / MPS = 2000ms
        assertThat(periodCaptor.getValue()).isEqualTo(expectedPeriodMs);
        assertThat(delayCaptor.getValue()).isBetween(0L, expectedPeriodMs - 1);
        assertThat(gatewayTelemetryTimers).containsExactly(fakeFuture);
    }

    @Test
    void noTelemetryTimerScheduledWhenMessagesPerSecondIsZeroOrLess() {
        gatewayStartIdx = 0;
        gatewayEndIdx = 10;
        testMessagesPerSecond = 0; // no-publish mode: mirrors AbstractAPITest.runApiTests' no-publish branch

        MqttClient client = mock(MqttClient.class);
        scheduleGatewayTelemetry(0, client, "GW00000000", List.of("DW00000000"));

        verify(scheduler, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        assertThat(gatewayTelemetryTimers).isEmpty();
    }

    @Test
    void noTelemetryTimerScheduledWhenGatewayHasNoSubDevices() {
        gatewayStartIdx = 0;
        gatewayEndIdx = 10;
        testMessagesPerSecond = 5;

        MqttClient client = mock(MqttClient.class);
        scheduleGatewayTelemetry(0, client, "GW00000000", List.of());

        verify(scheduler, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        assertThat(gatewayTelemetryTimers).isEmpty();
    }

    // --- checkStaggeredSupported(): fail fast on the unsupported configs instead of silently diverging ---

    @Test
    void checkStaggeredSupportedThrowsWhenGatewayBatchDisabled() {
        gatewayBatchEnabled = false;
        alarmsPerSecond = 0;
        assertThatThrownBy(this::checkStaggeredSupported).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkStaggeredSupportedThrowsWhenAlarmsEnabled() {
        gatewayBatchEnabled = true;
        alarmsPerSecond = 1;
        assertThatThrownBy(this::checkStaggeredSupported).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkStaggeredSupportedPassesForTheSupportedCombination() {
        gatewayBatchEnabled = true;
        alarmsPerSecond = 0;
        checkStaggeredSupported(); // must not throw
    }
}
