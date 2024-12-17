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

import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.pushsphere.common.Push

/**
 * see [Sending Notification Requests to APNs](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/sending_notification_requests_to_apns)
 */
internal object ApnsHeaders {
    private const val APNS_PUSH_TYPE = "apns-push-type"
    const val APNS_ID = "apns-id"
    private const val APNS_EXPIRATION = "apns-expiration"
    private const val APNS_PRIORITY = "apns-priority"
    const val APNS_TOPIC = "apns-topic"
    private const val APNS_COLLAPSE_ID = "apns-collapse-id"

    // For APNs response.
    const val APNS_UNIQUE_ID = "apns-unique-id"

    private val allowedHeadersName =
        setOf(APNS_PUSH_TYPE, APNS_ID, APNS_EXPIRATION, APNS_PRIORITY, APNS_TOPIC, APNS_COLLAPSE_ID, APNS_UNIQUE_ID)

    fun from(push: Push): HttpHeaders {
        val builder = HttpHeaders.builder()
        push.appleProps?.headers?.let { applePropsHeaders ->
            val (
                apnsPushType,
                apnsId,
                apnsExpiration,
                apnsPriority,
                apnsCollapseId,
            ) = applePropsHeaders

            apnsPushType?.let { builder.add(APNS_PUSH_TYPE, it.value) }
            apnsId?.let { builder.add(APNS_ID, it) }
            apnsExpiration?.let { builder.add(APNS_EXPIRATION, it.toString()) }
            apnsPriority?.let { builder.add(APNS_PRIORITY, it.toString()) }
            apnsCollapseId?.let { builder.add(APNS_COLLAPSE_ID, it) }
        }
        return builder.build()
    }

    fun from(headersMap: Map<String, Any?>?): HttpHeaders {
        return HttpHeaders.builder().apply {
            headersMap
                ?.filter { (k, _) -> allowedHeadersName.contains(k) }
                ?.forEach { (k, v) -> this.add(k, v.toString()) }
        }.build()
    }
}
