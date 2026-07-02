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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publish-throughput block. Reads the run-total success/failed counters and reports the per-window
 * delta plus a windowed rate and the running totals. {@code summaryAndReset} is synchronized so two
 * reports cannot interleave the last-reported snapshot.
 */
public class ThroughputStats {

    private final AtomicInteger totalSuccess;
    private final AtomicInteger totalFailed;
    private int lastSuccess;
    private int lastFailed;

    public ThroughputStats(AtomicInteger totalSuccess, AtomicInteger totalFailed) {
        this.totalSuccess = totalSuccess;
        this.totalFailed = totalFailed;
    }

    public synchronized String summaryAndReset(int windowSec) {
        int success = totalSuccess.get();
        int failed = totalFailed.get();
        int deltaOk = success - lastSuccess;
        int deltaFail = failed - lastFailed;
        lastSuccess = success;
        lastFailed = failed;
        double rate = windowSec > 0 ? (double) deltaOk / windowSec : 0.0;
        return String.format(
                "Throughput [window %ds]: publishOk=%d, publishFail=%d, ~%.0f msg/s (total ok=%d, fail=%d)",
                windowSec, deltaOk, deltaFail, rate, success, failed);
    }
}
