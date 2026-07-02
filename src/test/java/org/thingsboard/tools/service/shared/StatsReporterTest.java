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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StatsReporterTest {

    @Test
    void collectReturnsRegisteredBlocksInEnumOrder() {
        StatsReporter r = new StatsReporter(null, true, 10);
        // register out of canonical order on purpose
        r.register(StatsBlock.RPC, w -> "rpc " + w);
        r.register(StatsBlock.CONNECTIONS, w -> "conn " + w);
        r.register(StatsBlock.THROUGHPUT, w -> "thr " + w);
        assertThat(r.collect()).containsExactly("conn 10", "thr 10", "rpc 10");
    }

    @Test
    void collectSkipsUnregisteredBlocks() {
        StatsReporter r = new StatsReporter(null, true, 5);
        r.register(StatsBlock.CONNECTIONS, w -> "conn " + w);
        assertThat(r.collect()).containsExactly("conn 5");
    }

    @Test
    void eachCollectCallsSourceReset() {
        StatsReporter r = new StatsReporter(null, true, 10);
        AtomicInteger calls = new AtomicInteger();
        r.register(StatsBlock.CONNECTIONS, w -> "conn#" + calls.incrementAndGet());
        assertThat(r.collect()).containsExactly("conn#1");
        assertThat(r.collect()).containsExactly("conn#2");
    }

    @Test
    void oneFailingSourceDoesNotStopOthers() {
        StatsReporter r = new StatsReporter(null, true, 10);
        r.register(StatsBlock.CONNECTIONS, w -> { throw new RuntimeException("boom"); });
        r.register(StatsBlock.THROUGHPUT, w -> "thr " + w);
        List<String> lines = r.collect();
        assertThat(lines).containsExactly("thr 10");
    }

    @Test
    void reportOnceIsNoOpWhenDisabled() {
        StatsReporter r = new StatsReporter(null, false, 10); // enabled=false
        AtomicInteger calls = new AtomicInteger();
        r.register(StatsBlock.CONNECTIONS, w -> { calls.incrementAndGet(); return "x"; });
        r.reportOnce();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void reportOnceEmitsWhenEnabled() {
        StatsReporter r = new StatsReporter(null, true, 10);
        AtomicInteger calls = new AtomicInteger();
        r.register(StatsBlock.CONNECTIONS, w -> { calls.incrementAndGet(); return "x"; });
        r.reportOnce();
        assertThat(calls.get()).isEqualTo(1);
    }
}
