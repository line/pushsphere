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
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.io.path.toPath

class PushServerConfigTest {
    @Test
    fun `should respect retry options in profile sets and server config`() {
        val configFile = ResourceUtil.getUrl("classpath:pushsphere-retry-test.conf").toURI().toPath()
        val config = PushServerConfig.load(configFile)
        val serverRetryOptions = config.retryOptions
        serverRetryOptions.maxAttempts shouldBe 3
        serverRetryOptions.timeoutPerAttemptMillis shouldBe 1000
        serverRetryOptions.backoff shouldBe "fixed=200"
        serverRetryOptions.retryPolicies shouldBe
            listOf(
                RetryPolicy.SERVER_ERROR,
                RetryPolicy.TIMEOUT,
                RetryPolicy.ON_UNPROCESSED,
            )

        val test1Profile = config.profileSets.find { it.name == "test1" }!!.profiles.first()
        test1Profile.retryOptions shouldBe serverRetryOptions

        val profileSet = config.profileSets.find { it.name == "test2" }!!
        val profileSetRetryOptions = profileSet.retryOptions
        profileSetRetryOptions.maxAttempts shouldBe 2
        profileSetRetryOptions.timeoutPerAttemptMillis shouldBe 500
        profileSetRetryOptions.backoff shouldBe "fixed=100"
        profileSetRetryOptions.retryPolicies shouldBe listOf()
        val test2Profile = profileSet.profiles.first()

        // Profile-specific retry options should override profile set options.
        test2Profile.retryOptions.maxAttempts shouldBe 4

        // Should merge timeoutPerAttemptMillis and backoff from profile sets.
        test2Profile.retryOptions.timeoutPerAttemptMillis shouldBe 500
        test2Profile.retryOptions.backoff shouldBe "fixed=100"

        // Should merge retry policies from server config.
        test2Profile.retryOptions.retryPolicies shouldBe
            listOf(
                RetryPolicy.SERVER_ERROR,
                RetryPolicy.TIMEOUT,
                RetryPolicy.ON_UNPROCESSED,
            )

        val test3Profile = config.profileSets.find { it.name == "test3" }!!.profiles.first()
        val profileRetryOptions = test3Profile.retryOptions
        profileRetryOptions.maxAttempts shouldBe 5
        profileRetryOptions.backoff shouldBe "exponential=200"
        profileRetryOptions.timeoutPerAttemptMillis shouldBe 4000
        profileRetryOptions.retryPolicies shouldBe listOf(RetryPolicy.ON_UNPROCESSED)
    }

    @Test
    fun `no retry options`() {
        val configFile = ResourceUtil.getUrl("classpath:pushsphere-test.conf").toURI().toPath()
        val config = PushServerConfig.load(configFile)
        config.retryOptions shouldBe RetryOptions.EMPTY
        config.profileSets[0].retryOptions shouldBe RetryOptions.EMPTY
        config.profileSets[0].profiles[0].retryOptions shouldBe RetryOptions.EMPTY
    }
}
