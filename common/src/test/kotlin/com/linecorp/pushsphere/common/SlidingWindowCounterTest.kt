/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.pushsphere.common

import com.linecorp.pushsphere.internal.common.SlidingWindowCounter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SlidingWindowCounterTest {
    @Test
    fun `SlidingWindowCounter implementation test`() {
        val windowSizeNanos = 1_000_000_000L // 10^9 ns == 1s
        val counter = SlidingWindowCounter(windowSizeNanos)

        var currentTime: Long = windowSizeNanos * 2 + 500_000_000L
        counter.setTicker { currentTime }

        // when elapsed time: 2.5s
        counter.count(10L)
        counter.get() shouldBe 10L

        // when elapsed time: 3.3s
        currentTime = windowSizeNanos * 3 + 300_000_000L
        counter.count(10L)
        counter.get() shouldBe 17L

        // when elapsed time: 5.8s
        currentTime = windowSizeNanos * 5 + 800_000_000L
        counter.count(10L)
        counter.get() shouldBe 10L
    }
}
