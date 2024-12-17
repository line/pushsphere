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

import kotlinx.serialization.Serializable

@Serializable
data class RetryOptions(
    val maxAttempts: Int = -1,
    val backoff: String = "",
    val timeoutPerAttemptMillis: Long = -1,
    val retryPolicies: List<RetryPolicy> = listOf(),
    val httpStatusOptions: List<HttpStatusOption> = listOf(),
    val retryAfterStrategy: RetryAfterStrategy? = null,
) {
    companion object {
        val EMPTY =
            RetryOptions(
                maxAttempts = -1,
            )

        val DEFAULT =
            RetryOptions(
                maxAttempts = 2,
                backoff = "exponential=200:10000:2.0,jitter=0.2",
                timeoutPerAttemptMillis = 5000,
                retryPolicies =
                    listOf(
                        RetryPolicy.SERVER_ERROR,
                        RetryPolicy.TIMEOUT,
                        RetryPolicy.ON_EXCEPTION,
                        RetryPolicy.ON_UNPROCESSED,
                    ),
                retryAfterStrategy = RetryAfterStrategy.IGNORE,
            )
    }
}

@Serializable
enum class RetryPolicy {
    CLIENT_ERROR,
    SERVER_ERROR,
    TIMEOUT,
    ON_EXCEPTION,
    ON_UNPROCESSED,
    FCM_DEFAULT,
}

@Serializable
data class HttpStatusOption(
    val statuses: List<Int> = listOf(),
    val backoff: String = "",
    val noRetry: Boolean = false,
)

@Serializable
enum class RetryAfterStrategy {
    NO_RETRY,
    IGNORE,
    COMPLY,
}
