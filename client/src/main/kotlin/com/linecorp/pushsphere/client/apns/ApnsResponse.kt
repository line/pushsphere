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
package com.linecorp.pushsphere.client.apns

import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.pushsphere.client.apns.ApnsHeaders.APNS_ID
import com.linecorp.pushsphere.client.apns.ApnsHeaders.APNS_UNIQUE_ID
import com.linecorp.pushsphere.common.ApplePushResultProps
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * see [Handling Notification Responses from APNs](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/handling_notification_responses_from_apns)
 *
 * @property apnsId If you included `apns-id` at the request, it is the same with the value. Otherwise, APNs creates and return a new UUID.
 * @property apnsUniqueId Identifier that can be used for querying delivery log. **It is only included in the development environment.**
 * @property status HTTP status code of APNs response.
 * @property payload Payload from APNs response body.
 * @property retryAfter HTTP Retry-After header value.
 */
internal data class ApnsResponse(
    val apnsId: String,
    val apnsUniqueId: String?,
    val status: HttpStatus,
    val payload: ApnsResponsePayload? = null,
    val retryAfter: String? = null,
) {
    fun toPushResult(): PushResult {
        val applePushResultProps = ApplePushResultProps(apnsId, apnsUniqueId, payload?.reason, retryAfter)
        return PushResult(
            status = PushStatus.of(status.code()),
            resultSource = PushResultSource.PUSH_PROVIDER,
            reason = payload?.reason?.let { if (status.isSuccess) it else "$it ($status)" },
            applePushResultProps = applePushResultProps,
            pushResultProps = applePushResultProps,
            httpStatus = status.code(),
        )
    }

    companion object {
        fun from(res: AggregatedHttpResponse): ApnsResponse =
            ApnsResponse(
                apnsId = res.headers().get(APNS_ID)!!,
                apnsUniqueId = res.headers().get(APNS_UNIQUE_ID),
                status = res.status(),
                payload =
                    res.contentUtf8().let {
                        if (it.isEmpty()) {
                            null
                        } else {
                            Json.decodeFromString(ApnsResponsePayload.serializer(), it)
                        }
                    },
                retryAfter = res.headers().get(HttpHeaderNames.RETRY_AFTER),
            )
    }
}

/**
 * @property reason Specific reason of status code.
 * @property timestamp The time(in milliseconds since Epoch) at which APNs confirmed the token was no longer valid for the topic. **It is included only when the status code is 410**.
 */
@Serializable
internal data class ApnsResponsePayload(val reason: String, val timestamp: Long? = null)
