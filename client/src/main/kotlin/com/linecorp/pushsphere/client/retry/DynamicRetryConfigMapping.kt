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
import com.linecorp.armeria.client.retry.Backoff
import com.linecorp.armeria.client.retry.RetryConfig
import com.linecorp.armeria.client.retry.RetryConfigMapping
import com.linecorp.armeria.client.retry.RetryDecision
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.HttpStatusClass
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.util.UnmodifiableFuture
import com.linecorp.armeria.internal.shaded.guava.cache.Cache
import com.linecorp.armeria.internal.shaded.guava.cache.CacheBuilder
import com.linecorp.pushsphere.common.HttpStatusOption
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.RetryAfterStrategy
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryPolicy
import com.linecorp.pushsphere.common.RetryRateLimitOptions
import com.linecorp.pushsphere.internal.common.SlidingWindowCounter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.AttributeKey
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.CompletionStage
import kotlin.math.max

internal class DynamicRetryConfigMapping(
    private val defaultRetryOptions: RetryOptions,
    private val retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
    private val requestCounter: SlidingWindowCounter? = null,
    private val retryCounter: SlidingWindowCounter? = null,
    cacheSize: Long = 1000L,
    meterIdPrefix: MeterIdPrefix? = null,
    meterRegistry: MeterRegistry? = null,
) : RetryConfigMapping<HttpResponse> {
    private val retryConfigCache: Cache<RetryOptions, RetryConfig<HttpResponse>> =
        CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .build()
    private val defaultRetryConfig: RetryConfig<HttpResponse> =
        if (defaultRetryOptions == RetryOptions.EMPTY) {
            NO_RETRY_CONFIG
        } else {
            defaultRetryOptions.toRetryConfig(RetryOptions.DEFAULT)
        }

    init {
        if (meterRegistry != null && meterIdPrefix != null && this.getRetryLimit() != null) {
            val name = meterIdPrefix.name("retry.limit")
            Gauge.builder(name, this) { it.getRetryLimit()!! }
                .tags(meterIdPrefix.tags())
                .description("current retry limitation based on the total request rate")
                .register(meterRegistry)
        }
    }

    companion object {
        val PUSH_OPTIONS: AttributeKey<PushOptions> =
            AttributeKey.valueOf(DynamicRetryConfigMapping::class.java, "PUSH_OPTIONS")

        internal val NO_RETRY_CONFIG =
            RetryConfig.builder { _, _ ->
                UnmodifiableFuture.completedFuture(RetryDecision.noRetry())
            }
                .build()

        private val logger = KotlinLogging.logger {}

        private val RETRY_RULE_BUILDER = { policy: RetryPolicy, backoff: Backoff ->
            when (policy) {
                RetryPolicy.CLIENT_ERROR -> RetryRule.builder().onStatusClass(HttpStatusClass.CLIENT_ERROR).thenBackoff(backoff)
                RetryPolicy.SERVER_ERROR -> RetryRule.builder().onServerErrorStatus().thenBackoff(backoff)
                RetryPolicy.TIMEOUT -> RetryRule.builder().onTimeoutException().thenBackoff(backoff)
                RetryPolicy.ON_EXCEPTION -> RetryRule.builder().onException().thenBackoff(backoff)
                RetryPolicy.ON_UNPROCESSED -> RetryRule.builder().onUnprocessed().thenBackoff(backoff)
                RetryPolicy.FCM_DEFAULT -> fcmRetryRule
            }
        }
        private val NEXT = UnmodifiableFuture.completedFuture(RetryDecision.next())
        private val NO_RETRY = UnmodifiableFuture.completedFuture(RetryDecision.noRetry())
        private val FCM_RETRY_AFTER_DEFAULT = UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(60000L)))

        private val SKIP_RULE = RetryRule { _, _ -> NEXT }

        // The default timeout for each attempt is 10 seconds. A long `retry-after' value may not be practical
        // for non-batch requests that will cause response timeout. Hence, a backoff that has a higher initial
        // delay and multiplier is used.
        private val retryAfterBackoff = Backoff.exponential(10_000, 60_000, 3.0)

        /**
         * [[RetryRule]] for FCM.
         *
         * Reference: https://firebase.google.com/docs/cloud-messaging/scale-fcm#errors
         *
         * ### Errors
         * - For 400, 401, 403, 404 errors: abort, and do not retry.
         * - For 429 errors: retry with a given backoff duration from FCM by Retry-After header, having 60 seconds as default if not given.
         * (Please note that this behavior is only applied when RetryAfterStrategy is null or [RetryAfterStrategy.IGNORE] is set.)
         * - For 5xx errors : retry with [retryAfterBackoff].
         * - For other exceptions: retry with default backoff.
         */
        private fun buildFcmRetryRule(): RetryRule {
            val fcmNoRetryRule =
                RetryRule.builder()
                    .onStatus(
                        HttpStatus.BAD_REQUEST,
                        HttpStatus.UNAUTHORIZED,
                        HttpStatus.FORBIDDEN,
                        HttpStatus.NOT_FOUND,
                    )
                    .thenNoRetry()
            val fcmRetryForTooManyRequestsRule =
                buildRetryAfterRule { responseHeaders, retryAfter ->
                    if (responseHeaders.status() == HttpStatus.TOO_MANY_REQUESTS) {
                        retryAfter?.let {
                            UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.fixed(it)))
                        } ?: FCM_RETRY_AFTER_DEFAULT
                    } else {
                        NEXT
                    }
                }
            val fcmRetryForServerErrorsRule =
                RetryRule.builder()
                    .onServerErrorStatus()
                    .thenBackoff(retryAfterBackoff)
            val fcmRetryOnExceptionRule =
                RetryRule.builder()
                    .onException()
                    .thenBackoff()

            return RetryRule.of(
                fcmNoRetryRule,
                fcmRetryForTooManyRequestsRule,
                fcmRetryForServerErrorsRule,
                fcmRetryOnExceptionRule,
            )
        }

        private fun buildRetryAfterRule(decider: (ResponseHeaders, Long?) -> CompletionStage<RetryDecision>): RetryRule =
            RetryRule { ctx, _ ->
                val log = ctx.log()
                log.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS)?.let { requestLog ->
                    val responseHeaders = requestLog.responseHeaders()
                    val retryAfter =
                        responseHeaders.get(HttpHeaderNames.RETRY_AFTER)?.let {
                            it.toLongOrNull()
                                ?.let { retryAfter -> retryAfter * 1000L }
                                ?: try {
                                    val dateTime = ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    val currentTimeMillis = System.currentTimeMillis()
                                    val retryAfterMillis = dateTime.toInstant().toEpochMilli() - currentTimeMillis

                                    if (retryAfterMillis > 0) {
                                        retryAfterMillis
                                    } else {
                                        null
                                    }
                                } catch (e: DateTimeParseException) {
                                    logger.warn { "Retry-After header is given in an unrecognized format: $it" }
                                    null
                                }
                        }
                    decider(responseHeaders, retryAfter)
                } ?: run {
                    logger.warn {
                        "response header is unavailable even when Retry-After rule is evaluated"
                    }
                    NEXT
                }
            }

        private val fcmRetryRule = buildFcmRetryRule()
    }

    override fun get(
        ctx: ClientRequestContext,
        req: Request,
    ): RetryConfig<HttpResponse> {
        val pushOptions = ctx.attr(PUSH_OPTIONS)
        pushOptions?.localTotalTimeoutMillis?.let {
            if (it > 0L) {
                ctx.setResponseTimeoutMillis(it)
            }
        }
        val retryOptions = pushOptions?.localRetryOptions ?: return defaultRetryConfig
        return retryConfigCache.get(retryOptions) {
            retryOptions.toRetryConfig(defaultRetryOptions)
        }
    }

    internal fun getDefaultRetryConfig(): RetryConfig<HttpResponse> {
        return defaultRetryConfig
    }

    private fun getRetryLimit(): Double? {
        return if (
            retryRateLimitOptions == RetryRateLimitOptions.EMPTY ||
            requestCounter == null ||
            retryCounter == null ||
            retryRateLimitOptions.retryThresholdRatio < 0
        ) {
            null
        } else {
            val threshold =
                max(
                    requestCounter.get() * retryRateLimitOptions.retryThresholdRatio,
                    retryRateLimitOptions.minimumRetryCount.toDouble(),
                )
            threshold - retryCounter.get()
        }
    }

    private fun RetryOptions.toRetryConfig(defaultRetryOptions: RetryOptions): RetryConfig<HttpResponse> {
        if (this == RetryOptions.EMPTY && defaultRetryOptions == RetryOptions.EMPTY) {
            return NO_RETRY_CONFIG
        }

        var maxAttempt =
            if (this.maxAttempts > 0) {
                this.maxAttempts
            } else {
                defaultRetryOptions.maxAttempts
            }
        val backoff =
            Backoff.of(
                this.backoff.ifEmpty {
                    defaultRetryOptions.backoff.ifEmpty {
                        RetryOptions.DEFAULT.backoff
                    }
                },
            )
        var timeoutPerAttemptMillis =
            if (this.timeoutPerAttemptMillis > 0) {
                this.timeoutPerAttemptMillis
            } else {
                defaultRetryOptions.timeoutPerAttemptMillis
            }

        if (maxAttempt == 1) {
            return NO_RETRY_CONFIG
        } else if (maxAttempt < 1) {
            maxAttempt = RetryOptions.DEFAULT.maxAttempts
        }

        if (timeoutPerAttemptMillis <= 0) {
            timeoutPerAttemptMillis = RetryOptions.DEFAULT.timeoutPerAttemptMillis
        }

        val rateLimitRule =
            RetryRule { _, _ ->
                val retryLimit = getRetryLimit()
                if (retryLimit == null || retryLimit > 0) {
                    NEXT
                } else {
                    NO_RETRY
                }
            }

        val retryAfterStrategy =
            if (this.retryAfterStrategy != null) {
                this.retryAfterStrategy
            } else if (defaultRetryOptions.retryAfterStrategy != null) {
                defaultRetryOptions.retryAfterStrategy
            } else {
                RetryOptions.DEFAULT.retryAfterStrategy
            }

        val retryAfterRule =
            buildRetryAfterRule { responseHeaders, retryAfter ->
                if (responseHeaders.status().codeClass() == HttpStatusClass.SUCCESS) {
                    NEXT
                } else if (retryAfter != null) {
                    when (retryAfterStrategy) {
                        RetryAfterStrategy.NO_RETRY -> NO_RETRY
                        RetryAfterStrategy.IGNORE, null -> NEXT
                        RetryAfterStrategy.COMPLY ->
                            UnmodifiableFuture.completedFuture(
                                RetryDecision.retry(Backoff.fixed(retryAfter)),
                            )
                    }
                } else {
                    NEXT
                }
            }

        val retryRuleOnStatus = httpStatusOptionsToRetryRule(this.httpStatusOptions)

        val retryRule =
            if (this.retryPolicies.isNotEmpty()) {
                this.retryPolicies.toRetryRule(backoff)
            } else if (defaultRetryOptions.retryPolicies.isNotEmpty()) {
                defaultRetryOptions.retryPolicies.toRetryRule(backoff)
            } else {
                RetryOptions.DEFAULT.retryPolicies.toRetryRule(backoff)
            }

        // Rule combination priority:
        // 1. Rate limitation rule
        // 2. Retry-After rule
        // 3. HttpStatus dependent rule
        // 4. Predefined retry rule
        val combinedRule = RetryRule.of(rateLimitRule, retryAfterRule, retryRuleOnStatus, retryRule)

        return RetryConfig.builder(combinedRule)
            .maxTotalAttempts(maxAttempt)
            .responseTimeoutMillisForEachAttempt(timeoutPerAttemptMillis)
            .build()
    }

    private fun List<RetryPolicy>.toRetryRule(backoff: Backoff): RetryRule {
        return RetryRule.of(this.map { policy -> RETRY_RULE_BUILDER(policy, backoff) })
    }

    private fun httpStatusOptionsToRetryRule(options: List<HttpStatusOption>): RetryRule {
        if (options.isEmpty()) {
            return SKIP_RULE
        }

        return RetryRule.of(
            options.map { option ->
                if (option.statuses.isEmpty()) {
                    SKIP_RULE
                } else {
                    RetryRule
                        .builder()
                        .onStatus(option.statuses.map { HttpStatus.valueOf(it) })
                        .let {
                            if (option.noRetry) {
                                return it.thenNoRetry()
                            } else if (option.backoff.isNotEmpty()) {
                                return it.thenBackoff(Backoff.of(option.backoff))
                            } else {
                                return it.thenBackoff()
                            }
                        }
                }
            },
        )
    }
}
