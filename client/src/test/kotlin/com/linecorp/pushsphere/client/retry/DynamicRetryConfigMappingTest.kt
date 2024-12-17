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
package com.linecorp.pushsphere.client.retry

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.retry.RetryConfig
import com.linecorp.armeria.client.retry.RetryDecision
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.HttpStatusClass
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.TimeoutException
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.logging.RequestLogAccess
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.internal.shaded.guava.collect.ImmutableList
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryPolicy
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DynamicRetryConfigMappingTest {
    private fun testTimeout(
        retryConfig: RetryConfig<HttpResponse>,
        shouldRetry: Boolean,
        backoffString: String? = null,
    ) {
        val testCtx: ClientRequestContext = mockk()
        val logAccess: RequestLogAccess = mockk()
        val log: RequestLog = mockk()

        every { testCtx.isTimedOut } returns true

        every { testCtx.log() } returns logAccess
        every { logAccess.partial() } returns log
        every { log.isAvailable(eq(RequestLogProperty.REQUEST_HEADERS)) } returns false
        every { log.isAvailable(eq(RequestLogProperty.RESPONSE_HEADERS)) } returns false

        retryConfig.retryRule()!!.shouldRetry(testCtx, TimeoutException()).thenAccept { retryDecision ->
            if (shouldRetry) {
                retryDecision.toString() shouldContain backoffString!!
            } else {
                retryDecision shouldBe RetryDecision.next()
            }
        }
    }

    private fun testClientError(
        retryConfig: RetryConfig<HttpResponse>,
        shouldRetry: Boolean,
        backoffString: String? = null,
    ) {
        val testCtx: ClientRequestContext = mockk()
        val logAccess: RequestLogAccess = mockk()
        val log: RequestLog = mockk()
        val headers: ResponseHeaders = mockk()
        val clientErrorHttpStatus: HttpStatus = mockk()

        every { testCtx.log() } returns logAccess
        every { logAccess.partial() } returns log
        every { log.isAvailable(eq(RequestLogProperty.REQUEST_HEADERS)) } returns false
        every { log.isAvailable(eq(RequestLogProperty.RESPONSE_HEADERS)) } returns true
        every { log.responseHeaders() } returns headers
        every { headers.status() } returns clientErrorHttpStatus
        every { clientErrorHttpStatus.codeClass() } returns HttpStatusClass.CLIENT_ERROR

        retryConfig.retryRule()!!.shouldRetry(testCtx, null).thenAccept { retryDecision ->
            if (shouldRetry) {
                retryDecision.toString() shouldContain backoffString!!
            } else {
                retryDecision shouldBe RetryDecision.next()
            }
        }
    }

    private fun testServerError(
        retryConfig: RetryConfig<HttpResponse>,
        shouldRetry: Boolean,
        backoffString: String? = null,
    ) {
        val testCtx: ClientRequestContext = mockk()
        val logAccess: RequestLogAccess = mockk()
        val log: RequestLog = mockk()
        val headers: ResponseHeaders = mockk()
        val serverErrorHttpStatus: HttpStatus = mockk()

        every { testCtx.log() } returns logAccess
        every { logAccess.partial() } returns log
        every { log.isAvailable(eq(RequestLogProperty.REQUEST_HEADERS)) } returns false
        every { log.isAvailable(eq(RequestLogProperty.RESPONSE_HEADERS)) } returns true
        every { log.responseHeaders() } returns headers
        every { headers.status() } returns serverErrorHttpStatus
        every { serverErrorHttpStatus.codeClass() } returns HttpStatusClass.SERVER_ERROR

        retryConfig.retryRule()!!.shouldRetry(testCtx, null).thenAccept { retryDecision ->
            if (shouldRetry) {
                retryDecision.toString() shouldContain backoffString!!
            } else {
                retryDecision shouldBe RetryDecision.next()
            }
        }
    }

    @Test
    fun `should not retry when default option is given empty and user option is not given`() {
        val defaultRetryOptions = RetryOptions.EMPTY

        val ctx: ClientRequestContext = mockk()
        val req: Request = mockk()

        every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns null

        val dynamicRetryConfigMapping = DynamicRetryConfigMapping(defaultRetryOptions)
        val testCtx: ClientRequestContext = mockk()
        dynamicRetryConfigMapping.get(ctx, req).retryRule()!!.shouldRetry(testCtx, null).thenAccept { retryDecision ->
            retryDecision shouldBe RetryDecision.noRetry()
        }
    }

    @Test
    fun `should override to user-defined retry option if possible`() {
        val defaultRetryOptions =
            RetryOptions(
                10,
                "exponential=200:10000:2.0,jitter=0.9",
                1000,
                ImmutableList.of(
                    RetryPolicy.TIMEOUT,
                    RetryPolicy.CLIENT_ERROR,
                ),
            )

        val userDefinedBackoffString = "exponential=200:10000:2.0,jitter=0.5"
        val userDefinedRetryOptions =
            RetryOptions(
                2,
                userDefinedBackoffString,
                timeoutPerAttemptMillis = 2000,
            )
        val userDefinedPushOptions = PushOptions(localRetryOptions = userDefinedRetryOptions)

        val ctx: ClientRequestContext = mockk()
        val req: Request = mockk()

        every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns userDefinedPushOptions

        val dynamicRetryConfigMapping = DynamicRetryConfigMapping(defaultRetryOptions)
        val retryConfig = dynamicRetryConfigMapping.get(ctx, req)

        retryConfig.maxTotalAttempts() shouldBe userDefinedRetryOptions.maxAttempts
        retryConfig.responseTimeoutMillisForEachAttempt() shouldBe userDefinedRetryOptions.timeoutPerAttemptMillis

        testTimeout(retryConfig, true, userDefinedBackoffString)
        testClientError(retryConfig, true, userDefinedBackoffString)
        testServerError(retryConfig, false)
    }

    @Test
    fun `should override to a pre-defined configuration if default option is partially defined`() {
        val backoffString = "fixed=200"
        val defaultRetryOptions =
            RetryOptions(
                backoff = backoffString,
                timeoutPerAttemptMillis = 200,
                retryPolicies = ImmutableList.of(RetryPolicy.TIMEOUT, RetryPolicy.SERVER_ERROR),
            )
        val configMapping = DynamicRetryConfigMapping(defaultRetryOptions)
        val defaultRetryConfig = configMapping.getDefaultRetryConfig()

        defaultRetryConfig.maxTotalAttempts() shouldBe RetryOptions.DEFAULT.maxAttempts
        defaultRetryConfig.maxTotalAttempts() shouldBeGreaterThan 1
        defaultRetryConfig.responseTimeoutMillisForEachAttempt() shouldBe defaultRetryOptions.timeoutPerAttemptMillis
        testTimeout(defaultRetryConfig, true, backoffString)
        testClientError(defaultRetryConfig, false)
        testServerError(defaultRetryConfig, true, backoffString)
    }

    @Test
    fun `should override to pre-defined configuration when both default and user options are partially missing`() {
        val retryOptions =
            RetryOptions(
                3,
                "fixed=200",
                retryPolicies = ImmutableList.of(RetryPolicy.TIMEOUT),
            )
        val configMapping = DynamicRetryConfigMapping(retryOptions)
        val defaultRetryConfig = configMapping.getDefaultRetryConfig()

        defaultRetryConfig.maxTotalAttempts() shouldBe retryOptions.maxAttempts
        defaultRetryConfig.responseTimeoutMillisForEachAttempt() shouldBe RetryOptions.DEFAULT.timeoutPerAttemptMillis
        testTimeout(defaultRetryConfig, true, retryOptions.backoff)

        val ctx: ClientRequestContext = mockk()
        val req: Request = mockk()
        val pushOptions =
            PushOptions(
                RetryOptions(
                    4,
                    "fixed=300",
                    retryPolicies = ImmutableList.of(RetryPolicy.CLIENT_ERROR),
                ),
            )

        defaultRetryConfig.retryRule()

        every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns pushOptions
        val computedRetryConfig = configMapping.get(ctx, req)

        computedRetryConfig.maxTotalAttempts() shouldBe pushOptions.localRetryOptions!!.maxAttempts
        computedRetryConfig.responseTimeoutMillisForEachAttempt() shouldBe RetryOptions.DEFAULT.timeoutPerAttemptMillis

        testTimeout(computedRetryConfig, false)
        testClientError(computedRetryConfig, true, pushOptions.localRetryOptions!!.backoff)
    }

    @Test
    fun `should override to pre-defined configuration when default option is empty and user option is given`() {
        val configMapping = DynamicRetryConfigMapping(RetryOptions.EMPTY)
        val defaultRetryConfig = configMapping.getDefaultRetryConfig()

        val ctx: ClientRequestContext = mockk()
        val req: Request = mockk()
        defaultRetryConfig
            .retryRule()!!
            .shouldRetry(ctx, null)
            .thenAccept { retryDecision ->
                retryDecision shouldBe RetryDecision.noRetry()
            }

        val maxAttempts = 5
        val backoffString = "fixed=200"
        val timeoutPerAttemptMillis = 1500L
        val retryPolicies = ImmutableList.of(RetryPolicy.CLIENT_ERROR)

        val retryOptionsList =
            ImmutableList.of(
                // wrong maxAttempts
                RetryOptions(
                    -1,
                    backoffString,
                    timeoutPerAttemptMillis,
                    retryPolicies,
                ),
                // empty backoff
                RetryOptions(
                    maxAttempts,
                    "",
                    timeoutPerAttemptMillis,
                    retryPolicies,
                ),
                // wrong timeoutPerAttemptMillis
                RetryOptions(
                    maxAttempts,
                    backoffString,
                    -1,
                    retryPolicies,
                ),
                // empty RetryPolicies
                RetryOptions(
                    maxAttempts,
                    backoffString,
                    timeoutPerAttemptMillis,
                    ImmutableList.of(),
                ),
            )

        for (retryOption: RetryOptions in retryOptionsList) {
            every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns PushOptions(retryOption)
            val retryConfig = configMapping.get(ctx, req)

            if (retryOption.maxAttempts == maxAttempts) {
                retryConfig.maxTotalAttempts() shouldBe maxAttempts
            } else {
                retryConfig.maxTotalAttempts() shouldBe RetryOptions.DEFAULT.maxAttempts
            }

            if (retryOption.timeoutPerAttemptMillis == timeoutPerAttemptMillis) {
                retryConfig.responseTimeoutMillisForEachAttempt() shouldBe timeoutPerAttemptMillis
            } else {
                retryConfig.responseTimeoutMillisForEachAttempt() shouldBe RetryOptions.DEFAULT.timeoutPerAttemptMillis
            }

            if (retryOption.retryPolicies.isNotEmpty()) {
                if (retryOption.backoff.isNotEmpty()) {
                    testClientError(retryConfig, true, backoffString)
                } else {
                    testClientError(retryConfig, true, RetryOptions.DEFAULT.backoff)
                }
            } else {
                testClientError(retryConfig, false)
            }
        }
    }

    @Test
    fun `should apply NO_RETRY_CONFIG for maxAttempts = 1 case`() {
        val ordinaryOptions =
            RetryOptions(
                2,
                "fixed=100",
                2000L,
                listOf(RetryPolicy.CLIENT_ERROR),
            )
        val noRetryOptions =
            RetryOptions(
                1,
                "fixed=100",
                2000L,
                listOf(RetryPolicy.CLIENT_ERROR),
            )
        val emptyMaxAttempt =
            RetryOptions(
                maxAttempts = 0,
                backoff = "fixed=100",
                timeoutPerAttemptMillis = 2000L,
                retryPolicies = listOf(RetryPolicy.CLIENT_ERROR),
            )
        val ctx: ClientRequestContext = mockk()
        val req: Request = mockk()

        withClue("should return NO_RETRY_CONFIG when dynamic option is given as 1") {
            every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns PushOptions(noRetryOptions)
            DynamicRetryConfigMapping(ordinaryOptions).get(ctx, req) shouldBe DynamicRetryConfigMapping.NO_RETRY_CONFIG
        }

        withClue("should return NO_RETRY_CONFIG when there is no dynamic option but default maxAttempt is set to 1") {
            every { ctx.attr(DynamicRetryConfigMapping.PUSH_OPTIONS) } returns PushOptions(emptyMaxAttempt)
            DynamicRetryConfigMapping(noRetryOptions).get(ctx, req) shouldBe DynamicRetryConfigMapping.NO_RETRY_CONFIG
        }
    }
}
