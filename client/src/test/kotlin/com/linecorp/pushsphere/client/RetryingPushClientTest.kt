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
package com.linecorp.pushsphere.client

import com.linecorp.armeria.client.ClientDecoration
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import com.linecorp.pushsphere.common.HttpStatusOption
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.Push
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RetryAfterStrategy
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryPolicy
import com.linecorp.pushsphere.common.RetryRateLimitOptions
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RetryingPushClientTest {
    companion object {
        private const val MAX_RETRY_ATTEMPTS: Int = 3
        private const val BACKOFF_DELAY_MILLIS: Long = 500

        @Volatile
        private var successFunction: (ServiceRequestContext) -> Boolean = { _ -> true }

        @Volatile
        private var errorCodeForFailure = HttpStatus.INTERNAL_SERVER_ERROR

        @Volatile
        private var retryAfterHeaderValue = "1"

        @JvmField
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.decorator(LoggingService.newDecorator())
                    sb.service(Route.ofCatchAll()) { ctx, _ ->
                        val httpStatus =
                            if (successFunction(ctx)) {
                                HttpStatus.OK
                            } else {
                                errorCodeForFailure
                            }

                        HttpResponse.builder()
                            .status(httpStatus)
                            .header("apns-id", "1")
                            .apply {
                                if (errorCodeForFailure == HttpStatus.TOO_MANY_REQUESTS) {
                                    header(HttpHeaderNames.RETRY_AFTER, retryAfterHeaderValue)
                                }
                            }
                            .build()
                    }
                }

                override fun runForEachTest(): Boolean {
                    return true
                }
            }
    }

    private val pushRequest =
        PushRequest(
            PushProvider.APPLE,
            "token",
            Push.forApple("title", "body"),
        )

    private var pushClient: PushClient? = null

    private fun pushClient(): PushClient {
        return pushClient!!
    }

    @BeforeEach
    fun setupTest() {
        val tokenProfile =
            Profile.forApple(
                endpointUri = server.httpUri(),
                accessToken = "accessToken",
                bundleId = "bundleId",
                retryOptions =
                    RetryOptions(
                        maxAttempts = MAX_RETRY_ATTEMPTS,
                        backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                        retryPolicies = RetryOptions.DEFAULT.retryPolicies,
                    ),
            )
        pushClient = PushClient.of(tokenProfile)

        successFunction = { _ -> true }
    }

    @Test
    fun successAtFirstAttempt() =
        runTest {
            val result = pushClient().send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe 1
        }

    @Test
    fun successAtLastAttempt() =
        runTest {
            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS
            }

            val result = pushClient().send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS
        }

    @Test
    fun failAtLastAttempt() =
        runTest {
            successFunction = { false }
            val result = pushClient().send(pushRequest)
            result.status shouldBe PushStatus.INTERNAL_ERROR
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS
        }

    @Test
    fun retryAfterBackoff() =
        runTest {
            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS
            }
            val result = pushClient().send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS
            val first = server.requestContextCaptor().take().log().whenComplete().join()
            val second = server.requestContextCaptor().take().log().whenComplete().join()
            val third = server.requestContextCaptor().take().log().whenComplete().join()

            val actualBackoff0 =
                TimeUnit.NANOSECONDS.toMillis(second.requestStartTimeNanos() - first.responseEndTimeNanos())
            // Tolerance is -100ms ~ +200ms
            actualBackoff0 shouldBeGreaterThanOrEqual BACKOFF_DELAY_MILLIS - 100
            actualBackoff0 shouldBeLessThanOrEqual BACKOFF_DELAY_MILLIS + 200

            val actualBackoff1 =
                TimeUnit.NANOSECONDS.toMillis(third.requestStartTimeNanos() - second.responseEndTimeNanos())
            // Tolerance is -100ms ~ +200ms
            actualBackoff1 shouldBeGreaterThanOrEqual BACKOFF_DELAY_MILLIS - 100
            actualBackoff1 shouldBeLessThanOrEqual BACKOFF_DELAY_MILLIS + 200
        }

    @Test
    fun retryOnUnprocessed() =
        runTest {
            val tokenProfile =
                Profile.forApple(
                    endpointUri = URI.create("https://localhost:" + server.httpPort()),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = listOf(RetryPolicy.ON_UNPROCESSED),
                        ),
                )
            val invocationCounter = AtomicInteger()
            val decoration =
                ClientDecoration.of { delegate, ctx, req ->
                    invocationCounter.incrementAndGet()
                    delegate.execute(ctx, req)
                }
            pushClient = PushClient.of(profile = tokenProfile, decoration = decoration)
            val result = pushClient().send(pushRequest)
            result.status shouldBe PushStatus.INTERNAL_ERROR
            result.cause should beInstanceOf<UnprocessedRequestException>()
            invocationCounter.get() shouldBe MAX_RETRY_ATTEMPTS
        }

    @Test
    fun overrideRetryOptions() =
        runTest {
            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS + 1
            }

            val pushOptions = PushOptions(RetryOptions(MAX_RETRY_ATTEMPTS + 1))
            val result = pushClient().send(pushRequest, pushOptions)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS + 1
        }

    @Test
    fun retryRateLimitationWithoutMinimumRetryCount() =
        runTest {
            val windowSizeNanos = 1_000_000_000L
            val tokenProfile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = RetryOptions.DEFAULT.retryPolicies,
                        ),
                    retryRateLimitOptions =
                        RetryRateLimitOptions(
                            windowSizeNanos = windowSizeNanos,
                            minimumRetryCount = -1,
                            retryThresholdRatio = 1.0,
                        ),
                )
            pushClient = PushClient.of(tokenProfile)

            // fixing the current time
            var currentTime: Long = windowSizeNanos * 2
            (pushClient as AbstractPushClient).getRequestCounter()?.setTicker { currentTime }
            (pushClient as AbstractPushClient).getRetryCounter()?.setTicker { currentTime }

            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.INTERNAL_ERROR
            server.requestContextCaptor().size() shouldBe 2

            // after 1 second have passed
            currentTime = windowSizeNanos * 3
            val resultAfterOneSecond = pushClient!!.send(pushRequest)
            resultAfterOneSecond.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS
        }

    @Test
    fun retryRateLimitationWithMinimumRetryCount() =
        runTest {
            val windowSizeNanos = 1_000_000_000L
            val tokenProfile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = RetryOptions.DEFAULT.retryPolicies,
                        ),
                    retryRateLimitOptions =
                        RetryRateLimitOptions(
                            windowSizeNanos = windowSizeNanos,
                            minimumRetryCount = MAX_RETRY_ATTEMPTS.toLong(),
                            retryThresholdRatio = 1.0,
                        ),
                )
            pushClient = PushClient.of(tokenProfile)

            // fixing the current time
            val currentTime: Long = windowSizeNanos * 2
            (pushClient as AbstractPushClient).getRequestCounter()?.setTicker { currentTime }
            (pushClient as AbstractPushClient).getRetryCounter()?.setTicker { currentTime }

            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS
        }

    @Test
    fun complyRetryAfterForFcmDefault() =
        runTest {
            val profile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = listOf(RetryPolicy.FCM_DEFAULT),
                        ),
                )
            pushClient = PushClient.of(profile)
            errorCodeForFailure = HttpStatus.TOO_MANY_REQUESTS

            // First call should always fail
            var lastCallTime: Long = System.currentTimeMillis() + BACKOFF_DELAY_MILLIS * 10
            successFunction = {
                val currentTime = System.currentTimeMillis()
                val result = currentTime - lastCallTime >= BACKOFF_DELAY_MILLIS * 2
                lastCallTime = currentTime

                result
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe 2

            // restore the error code
            errorCodeForFailure = HttpStatus.INTERNAL_SERVER_ERROR
        }

    @Test
    fun retryWithHttpStatusOptions() =
        runTest {
            val profile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = listOf(RetryPolicy.SERVER_ERROR),
                            httpStatusOptions =
                                listOf(
                                    HttpStatusOption(
                                        statuses = listOf(500),
                                        backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                                        noRetry = true,
                                    ),
                                ),
                        ),
                )
            pushClient = PushClient.of(profile)

            val invocationCounter = AtomicInteger()
            successFunction = {
                invocationCounter.incrementAndGet() == MAX_RETRY_ATTEMPTS
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.INTERNAL_ERROR
            server.requestContextCaptor().size() shouldBe 1
        }

    @Test
    fun retryRuleCombinationWithFcmDefault() =
        runTest {
            val profile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies =
                                listOf(
                                    RetryPolicy.CLIENT_ERROR,
                                    RetryPolicy.FCM_DEFAULT,
                                ),
                        ),
                )
            pushClient = PushClient.of(profile)

            val invocationCounter = AtomicInteger()
            successFunction = {
                val count = invocationCounter.incrementAndGet()
                errorCodeForFailure =
                    if (count == 2) {
                        HttpStatus.INTERNAL_SERVER_ERROR
                    } else {
                        // client should retry despite of FCM_DEFAULT rule
                        HttpStatus.BAD_REQUEST
                    }

                count == MAX_RETRY_ATTEMPTS
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe MAX_RETRY_ATTEMPTS

            // restore the error code
            errorCodeForFailure = HttpStatus.INTERNAL_SERVER_ERROR
        }

    @Test
    fun retryRuleWithRetryAfterStrategy() =
        runTest {
            val profile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = listOf(RetryPolicy.CLIENT_ERROR),
                            retryAfterStrategy = RetryAfterStrategy.COMPLY,
                        ),
                )
            pushClient = PushClient.of(profile)
            errorCodeForFailure = HttpStatus.TOO_MANY_REQUESTS

            // First call should always fail
            var lastCallTime: Long = System.currentTimeMillis() + BACKOFF_DELAY_MILLIS * 10
            successFunction = {
                val currentTime = System.currentTimeMillis()
                val result = currentTime - lastCallTime >= BACKOFF_DELAY_MILLIS * 2

                lastCallTime = currentTime

                result
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe 2

            // restore the error code
            errorCodeForFailure = HttpStatus.INTERNAL_SERVER_ERROR
        }

    @Test
    fun retryRuleWithRetryAfterStrategyWhenHeaderIsGivenInDate() =
        runTest {
            val profile =
                Profile.forApple(
                    endpointUri = server.httpUri(),
                    accessToken = "accessToken",
                    bundleId = "bundleId",
                    retryOptions =
                        RetryOptions(
                            maxAttempts = MAX_RETRY_ATTEMPTS,
                            backoff = "fixed=$BACKOFF_DELAY_MILLIS",
                            retryPolicies = listOf(RetryPolicy.CLIENT_ERROR),
                            retryAfterStrategy = RetryAfterStrategy.COMPLY,
                        ),
                )
            pushClient = PushClient.of(profile)
            errorCodeForFailure = HttpStatus.TOO_MANY_REQUESTS
            val previousRetryAfterHeaderValue = retryAfterHeaderValue

            // First call should always fail
            var lastCallTime: Long = System.currentTimeMillis() + BACKOFF_DELAY_MILLIS * 10
            successFunction = {
                val currentTime = System.currentTimeMillis()
                val result = currentTime - lastCallTime >= BACKOFF_DELAY_MILLIS * 2

                if (!result) {
                    retryAfterHeaderValue =
                        ZonedDateTime
                            .ofInstant(
                                Instant.ofEpochMilli(currentTime + 2000L),
                                ZoneId.of("UTC"),
                            )
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
                }

                lastCallTime = currentTime

                result
            }

            val result = pushClient!!.send(pushRequest)
            result.status shouldBe PushStatus.SUCCESS
            server.requestContextCaptor().size() shouldBe 2

            // restore the error code
            errorCodeForFailure = HttpStatus.INTERNAL_SERVER_ERROR
            retryAfterHeaderValue = previousRetryAfterHeaderValue
        }
}
