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
package com.linecorp.pushsphere.internal.common

import com.linecorp.pushsphere.common.HttpStatusOption
import com.linecorp.pushsphere.common.RetryAfterStrategy
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryPolicy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RemoteRetryOptionsSerDesTest {
    private fun buildSerializerTester(
        maxAttempt: String?,
        backoff: String?,
        timeoutPerAttemptMillis: String?,
        retryPolicies: String?,
        httpStatusOptions: Collection<String>?,
        retryAfterStrategy: String?,
    ): (String, String) -> Unit {
        val expected: MutableMap<String, String?> = mutableMapOf()
        expected[PushsphereHeaderNames.REMOTE_RETRY_MAX_ATTEMPTS] = maxAttempt
        expected[PushsphereHeaderNames.REMOTE_RETRY_BACKOFF] = backoff
        expected[PushsphereHeaderNames.REMOTE_RETRY_TIMEOUT_PER_ATTEMPT] = timeoutPerAttemptMillis
        expected[PushsphereHeaderNames.REMOTE_RETRY_POLICIES] = retryPolicies
        expected[PushsphereHeaderNames.REMOTE_RETRY_AFTER_STRATEGY] = retryAfterStrategy

        return { headerName, headerValue ->
            if (headerName == PushsphereHeaderNames.REMOTE_HTTP_STATUS_OPTIONS) {
                httpStatusOptions shouldNotBe null
                httpStatusOptions?.shouldContain(headerValue)
            } else {
                headerValue shouldBe expected[headerName]
            }
        }
    }

    @Test
    fun `serializer should gracefully pass through invalid inputs`() {
        RemoteRetryOptionsSerDes.serialize(
            null,
            buildSerializerTester(
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )

        RemoteRetryOptionsSerDes.serialize(
            RetryOptions.EMPTY,
            buildSerializerTester(
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
        RemoteRetryOptionsSerDes.serialize(
            RetryOptions(),
            buildSerializerTester(
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
        val invalidRetryOptions =
            RetryOptions(
                maxAttempts = -1,
                backoff = "",
                timeoutPerAttemptMillis = 0,
                retryPolicies = listOf(),
            )
        RemoteRetryOptionsSerDes.serialize(
            invalidRetryOptions,
            buildSerializerTester(
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )
    }

    @Test
    fun `serializer should represent fully-valid remote retry options into a map`() {
        val retryOptions =
            RetryOptions(
                maxAttempts = 2,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            retryOptions,
            buildSerializerTester(
                retryOptions.maxAttempts.toString(),
                retryOptions.backoff,
                retryOptions.timeoutPerAttemptMillis.toString(),
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                RetryAfterStrategy.COMPLY.name,
            ),
        )
    }

    @Test
    fun `serializer should represent partially-valid remote retry options into a map`() {
        val maxAttemptInvalidCase =
            RetryOptions(
                maxAttempts = -1,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            maxAttemptInvalidCase,
            buildSerializerTester(
                null,
                maxAttemptInvalidCase.backoff,
                maxAttemptInvalidCase.timeoutPerAttemptMillis.toString(),
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                RetryAfterStrategy.COMPLY.name,
            ),
        )

        val backoffInvalidCase =
            RetryOptions(
                maxAttempts = 2,
                backoff = "",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            backoffInvalidCase,
            buildSerializerTester(
                backoffInvalidCase.maxAttempts.toString(),
                null,
                backoffInvalidCase.timeoutPerAttemptMillis.toString(),
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                RetryAfterStrategy.COMPLY.name,
            ),
        )

        val timeoutPerAttemptInvalidCase =
            RetryOptions(
                maxAttempts = 2,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = -1L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            timeoutPerAttemptInvalidCase,
            buildSerializerTester(
                timeoutPerAttemptInvalidCase.maxAttempts.toString(),
                timeoutPerAttemptInvalidCase.backoff,
                null,
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                RetryAfterStrategy.COMPLY.name,
            ),
        )

        val retryPolicyInvalidCase =
            RetryOptions(
                maxAttempts = 2,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            retryPolicyInvalidCase,
            buildSerializerTester(
                retryPolicyInvalidCase.maxAttempts.toString(),
                retryPolicyInvalidCase.backoff,
                retryPolicyInvalidCase.timeoutPerAttemptMillis.toString(),
                null,
                HTTP_STATUS_OPTIONS_STRING_LIST,
                RetryAfterStrategy.COMPLY.name,
            ),
        )

        val httpStatusOptionsInvalidCase =
            RetryOptions(
                maxAttempts = 2,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = listOf(),
                retryAfterStrategy = RetryAfterStrategy.COMPLY,
            )
        RemoteRetryOptionsSerDes.serialize(
            httpStatusOptionsInvalidCase,
            buildSerializerTester(
                retryPolicyInvalidCase.maxAttempts.toString(),
                retryPolicyInvalidCase.backoff,
                retryPolicyInvalidCase.timeoutPerAttemptMillis.toString(),
                "TIMEOUT,CLIENT_ERROR",
                null,
                RetryAfterStrategy.COMPLY.name,
            ),
        )

        val retryAfterStrategyInvalidCase =
            RetryOptions(
                maxAttempts = 2,
                backoff = "fixed=200",
                timeoutPerAttemptMillis = 1000L,
                retryPolicies = listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                httpStatusOptions = HTTP_STATUS_OPTIONS,
                retryAfterStrategy = null,
            )
        RemoteRetryOptionsSerDes.serialize(
            retryAfterStrategyInvalidCase,
            buildSerializerTester(
                retryPolicyInvalidCase.maxAttempts.toString(),
                retryPolicyInvalidCase.backoff,
                retryPolicyInvalidCase.timeoutPerAttemptMillis.toString(),
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                null,
            ),
        )
    }

    @Test
    fun `deserializer should return null when all inputs are empty`() {
        RemoteRetryOptionsSerDes.deserialize(
            null,
            null,
            null,
            null,
            listOf(),
            null,
        ) shouldBe null
    }

    @Test
    fun `deserializer should omit invalid header values`() {
        RemoteRetryOptionsSerDes.deserialize(
            "NaN",
            null,
            "NaN",
            "OMAE-WA-MO-SHINDEIRU,NANI",
            listOf("HI", "DE", "BU"),
            "MY_WAY",
        ) shouldBe null
    }

    @Test
    fun `deserializer should format the header values into RetryOptions`() {
        val retryOptions =
            RetryOptions(
                2,
                "fixed=200",
                5000L,
                listOf(RetryPolicy.TIMEOUT, RetryPolicy.CLIENT_ERROR),
                HTTP_STATUS_OPTIONS,
                RetryAfterStrategy.NO_RETRY,
            )
        val deserializedResult =
            RemoteRetryOptionsSerDes.deserialize(
                "2",
                "fixed=200",
                "5000",
                "TIMEOUT,CLIENT_ERROR",
                HTTP_STATUS_OPTIONS_STRING_LIST,
                "NO_RETRY",
            )

        deserializedResult shouldNotBe null
        deserializedResult?.maxAttempts shouldBe retryOptions.maxAttempts
        deserializedResult?.backoff shouldBe retryOptions.backoff
        deserializedResult?.timeoutPerAttemptMillis shouldBe retryOptions.timeoutPerAttemptMillis
        deserializedResult?.retryPolicies shouldBe retryOptions.retryPolicies
        deserializedResult?.httpStatusOptions shouldBe retryOptions.httpStatusOptions
        deserializedResult?.retryAfterStrategy shouldBe retryOptions.retryAfterStrategy
    }

    @Test
    fun `deserializer should format httpStatusOptionsString in any order`() {
        val deserializeHttpStatusOptionsOnly = { httpStatusOptions: String ->
            RemoteRetryOptionsSerDes.deserialize(
                null,
                null,
                null,
                null,
                listOf(httpStatusOptions),
                null,
            )
        }

        // partial option tests (should not allow any of them)
        val partialOptionStrings =
            listOf(
                STATUSES_OPTION_ONE_STRING,
                STATUSES_OPTION_TWO_STRING,
                NO_RETRY_OPTION_ONE_STRING,
                NO_RETRY_OPTION_TWO_STRING,
                BACKOFF_OPTION_ONE_STRING,
                BACKOFF_OPTION_TWO_STRING,
            )

        partialOptionStrings.forEach {
            deserializeHttpStatusOptionsOnly(it) shouldBe null
        }

        // empty option test for option string with empty status
        val emptyStatusOptionString = "statuses=&$BACKOFF_OPTION_ONE_STRING&$NO_RETRY_OPTION_ONE_STRING"
        val emptyStatusOptions =
            listOf(
                HttpStatusOption(
                    backoff = BACKOFF_OPTION_ONE_STRING.removePrefix("backoff="),
                    noRetry = NO_RETRY_OPTION_ONE_STRING.toBoolean(),
                ),
            )
        val result = deserializeHttpStatusOptionsOnly(emptyStatusOptionString)

        result shouldNotBe null
        result?.httpStatusOptions shouldBe emptyStatusOptions

        // option whitespace trimming test
        val optionStringWithWhitespaces = "  $STATUSES_OPTION_ONE_STRING & $BACKOFF_OPTION_ONE_STRING & $NO_RETRY_OPTION_ONE_STRING  "
        val httpStatusOptionsFromWhitespace = listOf(HTTP_STATUS_OPTIONS[0])
        val resultFromWhitespace = deserializeHttpStatusOptionsOnly(optionStringWithWhitespaces)

        resultFromWhitespace shouldNotBe null
        resultFromWhitespace?.httpStatusOptions shouldBe httpStatusOptionsFromWhitespace

        // order-independence test
        val optionStringList =
            listOf(
                STATUSES_OPTION_ONE_STRING,
                BACKOFF_OPTION_ONE_STRING,
                NO_RETRY_OPTION_ONE_STRING,
            )

        permutation(optionStringList).map {
            it.joinToString(separator = "&")
        }.forEach {
            val res = deserializeHttpStatusOptionsOnly(it)
            res shouldNotBe null
            res?.httpStatusOptions shouldBe HTTP_STATUS_OPTIONS[0]
        }
    }

    private fun <T> permutation(
        list: List<T>,
        picked: List<T> = listOf(),
        remaining: List<T> = list,
    ): List<List<T>> {
        return if (remaining.isEmpty()) {
            listOf(picked)
        } else {
            picked.flatMap { permutation(list, picked + it, remaining - it) }
        }
    }

    companion object {
        private const val STATUSES_OPTION_ONE_STRING = "statuses=400,401,403,404"
        private const val BACKOFF_OPTION_ONE_STRING = "backoff=200;10000,jitter=0.2"
        private const val NO_RETRY_OPTION_ONE_STRING = "noRetry=false"
        private const val STATUSES_OPTION_TWO_STRING = "statuses=429"
        private const val BACKOFF_OPTION_TWO_STRING = "backoff="
        private const val NO_RETRY_OPTION_TWO_STRING = "noRetry=true"
        private val HTTP_STATUS_OPTION_ONE_STRING =
            listOf(
                STATUSES_OPTION_ONE_STRING,
                BACKOFF_OPTION_ONE_STRING,
                NO_RETRY_OPTION_ONE_STRING,
            ).joinToString(separator = "&")
        private val HTTP_STATUS_OPTION_TWO_STRING =
            listOf(
                STATUSES_OPTION_TWO_STRING,
                BACKOFF_OPTION_TWO_STRING,
                NO_RETRY_OPTION_TWO_STRING,
            ).joinToString(separator = "&")
        private val HTTP_STATUS_OPTIONS_STRING_LIST =
            listOf(
                HTTP_STATUS_OPTION_ONE_STRING,
                HTTP_STATUS_OPTION_TWO_STRING,
            )
        private val HTTP_STATUS_OPTIONS =
            listOf(
                HttpStatusOption(
                    listOf(400, 401, 403, 404),
                    "200;10000,jitter=0.2",
                    false,
                ),
                HttpStatusOption(
                    listOf(429),
                    "",
                    true,
                ),
            )
    }
}
