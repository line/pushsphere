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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.regex.Pattern

private val logger = KotlinLogging.logger {}

object RemoteRetryOptionsSerDes {
    private val statusesPattern = Pattern.compile("^statuses=(?<statuses>[0-9,]*)$")
    private val backoffPattern = Pattern.compile("^backoff=(?<backoff>.*)$")
    private val noRetryPattern = Pattern.compile("^noRetry=(?<noRetry>true|false)$")
    private const val HTTP_STATUS_OPTION_ITEM_DELIMITER = "&"

    fun serialize(
        retryOptions: RetryOptions?,
        out: (String, String) -> Unit,
    ) {
        if (retryOptions == null || retryOptions == RetryOptions.EMPTY) {
            return
        }

        if (retryOptions.maxAttempts > 0) {
            out(PushsphereHeaderNames.REMOTE_RETRY_MAX_ATTEMPTS, retryOptions.maxAttempts.toString())
        }
        if (retryOptions.backoff.isNotEmpty()) {
            out(PushsphereHeaderNames.REMOTE_RETRY_BACKOFF, retryOptions.backoff)
        }
        if (retryOptions.timeoutPerAttemptMillis > 0) {
            out(PushsphereHeaderNames.REMOTE_RETRY_TIMEOUT_PER_ATTEMPT, retryOptions.timeoutPerAttemptMillis.toString())
        }
        if (retryOptions.retryPolicies.isNotEmpty()) {
            out(
                PushsphereHeaderNames.REMOTE_RETRY_POLICIES,
                retryOptions.retryPolicies.joinToString(separator = ",") { retryPolicy -> retryPolicy.name },
            )
        }
        retryOptions.httpStatusOptions.forEach {
            val statuses = it.statuses.joinToString(separator = ",") { status -> status.toString() }
            out(
                PushsphereHeaderNames.REMOTE_HTTP_STATUS_OPTIONS,
                "statuses=$statuses&backoff=${it.backoff}&noRetry=${it.noRetry}",
            )
        }
        if (retryOptions.retryAfterStrategy != null) {
            out(
                PushsphereHeaderNames.REMOTE_RETRY_AFTER_STRATEGY,
                retryOptions.retryAfterStrategy.name,
            )
        }
    }

    fun deserialize(
        maxAttemptsString: String?,
        backoff: String?,
        timeoutPerAttemptString: String?,
        retryPoliciesString: String?,
        httpStatusOptionsStringList: List<String>,
        retryAfterStrategyString: String?,
    ): RetryOptions? {
        val maxAttempts = maxAttemptsString?.toIntOrNull().takeIf { it != null && it > 1 }
        val timeoutPerAttemptMillis = timeoutPerAttemptString?.toLongOrNull().takeIf { it != null && it > 0L }
        val retryPolicies =
            retryPoliciesString
                ?.split(",")
                ?.mapNotNull { policyName ->
                    try {
                        RetryPolicy.valueOf(policyName)
                    } catch (e: IllegalArgumentException) {
                        logger.warn(
                            e,
                        ) {
                            "Not allowed RetryPolicyName is given. policyName: $policyName, " +
                                "headerString: $retryPoliciesString"
                        }
                        null
                    }
                }
                ?.ifEmpty { null }
        val httpStatusOptions =
            httpStatusOptionsStringList
                .mapNotNull { optionString ->
                    try {
                        var statuses: List<Int>? = null
                        var backoffPerOption: String? = null
                        var noRetry: Boolean? = null
                        optionString
                            .split(HTTP_STATUS_OPTION_ITEM_DELIMITER)
                            .map { optionItem ->
                                val trimmed = optionItem.trim()
                                val statusesMatcher = statusesPattern.matcher(trimmed)
                                val backoffMatcher = backoffPattern.matcher(trimmed)
                                val noRetryMatcher = noRetryPattern.matcher(trimmed)
                                if (statusesMatcher.matches()) {
                                    statuses =
                                        statusesMatcher
                                            .group("statuses")
                                            .takeIf { it.isNotEmpty() }
                                            ?.split(",")
                                            ?.map { it.toInt() }
                                            ?: listOf()
                                } else if (backoffMatcher.matches()) {
                                    backoffPerOption = backoffMatcher.group("backoff")
                                } else if (noRetryMatcher.matches()) {
                                    noRetry = noRetryMatcher.group("noRetry").toBoolean()
                                }
                            }

                        if (statuses != null && backoffPerOption != null && noRetry != null) {
                            HttpStatusOption(statuses!!, backoffPerOption!!, noRetry!!)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        logger.warn(e) {
                            "Unexpected error was occurred during deserialization, optionString: $optionString"
                        }
                        null
                    }
                }
        val retryAfterStrategy =
            if (retryAfterStrategyString != null) {
                try {
                    RetryAfterStrategy.valueOf(retryAfterStrategyString)
                } catch (e: IllegalArgumentException) {
                    logger.warn(
                        e,
                    ) {
                        "Not allowed RetryAfterStrategy is given. name: $retryAfterStrategyString"
                    }
                    null
                }
            } else {
                null
            }

        if (
            listOf(
                maxAttempts,
                backoff,
                timeoutPerAttemptMillis,
                retryPolicies,
                retryAfterStrategy,
            ).any { it != null } ||
            httpStatusOptions.isNotEmpty()
        ) {
            return RetryOptions(
                maxAttempts ?: -1,
                backoff ?: "",
                timeoutPerAttemptMillis ?: -1,
                retryPolicies ?: listOf(),
                httpStatusOptions,
                retryAfterStrategy,
            )
        }

        return null
    }
}
