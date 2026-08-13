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
package org.thingsboard.tools.service.device;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.tools.service.shared.RestClientService;

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

// Same broker-free idiom as MqttGatewayAPITestTest: this test class IS the SUT (extends
// MqttDeviceAPITest) so it can reach the package-private/protected inherited state
// (deviceStartIdx/deviceEndIdx/mqttClients/deviceClients/seed/...) without a Spring context or a real
// MQTT broker. init()/@PostConstruct is never invoked, so every field a test relies on is set explicitly.
class MqttDeviceAPITestTest extends MqttDeviceAPITest {

    ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = mock(ScheduledExecutorService.class);
        RestClientService rcs = mock(RestClientService.class);
        when(rcs.getScheduler()).thenReturn(scheduler);
        restClientService = rcs;
    }

    // --- scheduleDeviceTelemetry(): MPS-derived period + jitter, and the no-timer guard ---

    @Test
    void telemetryPeriodIsDerivedFromEntityCountAndMessagesPerSecond() {
        deviceStartIdx = 0;
        deviceEndIdx = 10; // entityCount = 10
        testMessagesPerSecond = 5;
        seed = 0;

        // Raw type (not ScheduledFuture<?>): a wildcard-typed mock hits a generic-capture mismatch on
        // thenReturn (javac can't unify two independent "capture of ?" instantiations).
        @SuppressWarnings({"unchecked", "rawtypes"})
        ScheduledFuture fakeFuture = mock(ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(fakeFuture);

        MqttClient client = mock(MqttClient.class);
        scheduleDeviceTelemetry(3, client, "DW00000003");

        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> periodCaptor = ArgumentCaptor.forClass(Long.class);
        verify(scheduler).scheduleAtFixedRate(any(), delayCaptor.capture(), periodCaptor.capture(), eq(TimeUnit.MILLISECONDS));

        long expectedPeriodMs = (10 * 1000L) / 5; // entityCount * 1000 / MPS = 2000ms
        assertThat(periodCaptor.getValue()).isEqualTo(expectedPeriodMs);
        assertThat(delayCaptor.getValue()).isBetween(0L, expectedPeriodMs - 1);
        assertThat(deviceTelemetryTimers).containsExactly(fakeFuture);
    }

    @Test
    void noTelemetryTimerScheduledWhenMessagesPerSecondIsZeroOrLess() {
        deviceStartIdx = 0;
        deviceEndIdx = 10;
        testMessagesPerSecond = 0; // no-publish mode: mirrors AbstractAPITest.runApiTests' no-publish branch

        MqttClient client = mock(MqttClient.class);
        scheduleDeviceTelemetry(0, client, "DW00000000");

        verify(scheduler, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        assertThat(deviceTelemetryTimers).isEmpty();
    }

    // --- checkStaggeredSupported(): fail fast on the unsupported configs instead of silently diverging ---

    @Test
    void checkStaggeredSupportedThrowsWhenAlarmsEnabled() {
        alarmsPerSecond = 1;
        assertThatThrownBy(this::checkStaggeredSupported).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void checkStaggeredSupportedPassesWhenAlarmsDisabled() {
        alarmsPerSecond = 0;
        checkStaggeredSupported(); // must not throw
    }

    // --- prepareStaggeredModel(): same device-name resolution as PHASED's connectDevices() body ---

    @Test
    void prepareStaggeredModelResolvesNamesFromIndexRangeWhenNoDevicesLoaded() {
        deviceStartIdx = 100;
        deviceEndIdx = 103;

        prepareStaggeredModel();

        assertThat(staggeredDeviceNames).hasSize(3);
    }
}
