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

import com.linecorp.pushsphere.common.apns.ApnsErrorCode
import com.linecorp.pushsphere.common.fcm.FcmError
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PushResult(
    val status: PushStatus,
    val resultSource: PushResultSource,
    val reason: String? = null,
    @Transient
    val cause: Throwable? = null,
    @Deprecated("Use pushResultProps instead", replaceWith = ReplaceWith("pushResultProps"))
    val applePushResultProps: ApplePushResultProps? = null,
    val pushResultProps: PushResultProps? = null,
    val httpStatus: Int? = null,
)

enum class PushStatus {
    SUCCESS,
    INVALID_REQUEST,
    TOO_LARGE_PAYLOAD,
    DEVICE_UNREGISTERED,
    QUOTA_EXCEEDED,
    UNAVAILABLE,
    INTERNAL_ERROR,
    AUTH_FAILURE,
    PROFILE_MISSING,
    INVALID_SERVER_RESPONSE,
    UNKNOWN,
    ;

    fun httpStatus(): Int? {
        return when (this) {
            SUCCESS -> 200 // OK
            INVALID_REQUEST -> 400 // Bad Request
            TOO_LARGE_PAYLOAD -> 413 // Payload Too Large
            DEVICE_UNREGISTERED -> 410 // Gone
            QUOTA_EXCEEDED -> 429 // Too Many Requests
            UNAVAILABLE -> 503 // Service Unavailable
            INTERNAL_ERROR -> 500 // Internal Server Error
            AUTH_FAILURE -> 401 // Unauthorized
            PROFILE_MISSING -> 501 // Not Implemented
            INVALID_SERVER_RESPONSE -> 520 // Invalid Server Response
            UNKNOWN -> null
        }
    }

    companion object {
        @Suppress("ktlint:standard:comment-wrapping")
        fun of(httpStatus: Int?): PushStatus {
            return when (httpStatus) {
                200 /* OK */ -> SUCCESS
                400 /* Bad Request */ -> INVALID_REQUEST
                401 /* Unauthorized */ -> AUTH_FAILURE
                403 /* Forbidden */ -> AUTH_FAILURE
                404 /* Not Found */ -> DEVICE_UNREGISTERED
                410 /* Gone */ -> DEVICE_UNREGISTERED
                429 /* Too Many Requests */ -> QUOTA_EXCEEDED
                500 /* Internal Server Error */ -> INTERNAL_ERROR
                501 /* Not Implemented */ -> PROFILE_MISSING
                503 /* Service Unavailable */ -> UNAVAILABLE
                520 /* Invalid Server Response  */ -> INVALID_SERVER_RESPONSE
                else -> UNKNOWN
            }
        }
    }
}

enum class PushResultSource {
    CLIENT,
    SERVER,
    PUSH_PROVIDER,
}

@Serializable
sealed interface PushResultProps {
    val retryAfter: String?
}

@Serializable
data class ApplePushResultProps(
    val apnsId: String,
    val apnsUniqueId: String? = null,
    val reason: String? = null,
    override val retryAfter: String? = null,
) : PushResultProps {
    fun getApnsErrorCode(): ApnsErrorCode? {
        return ApnsErrorCode.from(reason)
    }
}

@Serializable
data class FirebasePushResultProps(
    val messageId: String? = null,
    val error: FcmError? = null,
    override val retryAfter: String? = null,
) : PushResultProps
