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
package com.linecorp.pushsphere.server

import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.linecorp.pushsphere.common.SelectionStrategy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.io.path.toPath

class AppleProfileEndpointGroupConfigTest {
    @Test
    fun `should parse and read endpointGroupOptions and circuitBreakerOptions`() {
        val configFile = ResourceUtil.getUrl("classpath:pushsphere-test.conf").toURI().toPath()
        val config = PushServerConfig.load(configFile)
        val appleConfig = config.profileSets[0].profiles[0] as PushServerAppleProfileConfig

        appleConfig.bundleId shouldBe "com.acme.MyApp"

        appleConfig.endpointGroupOptions.maxNumEndpoints shouldBe 5
        appleConfig.endpointGroupOptions.maxEndpointAgeMillis shouldBe 600000 // Use the default value
        appleConfig.endpointGroupOptions.selectionStrategy shouldBe SelectionStrategy.WEIGHTED_ROUND_ROBIN

        appleConfig.circuitBreakerOptions.namePrefix shouldBe "my_apple"
        appleConfig.circuitBreakerOptions.failureRateThreshold shouldBe 0.6
        appleConfig.circuitBreakerOptions.minimumRequestThreshold shouldBe 1
        appleConfig.circuitBreakerOptions.trialRequestIntervalMillis shouldBe 0 // Use the default value
        appleConfig.circuitBreakerOptions.circuitOpenWindowMillis shouldBe 20
        appleConfig.circuitBreakerOptions.counterSlidingWindowMillis shouldBe 30
        appleConfig.circuitBreakerOptions.counterUpdateIntervalMillis shouldBe 10
    }
}
