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
package com.linecorp.pushsphere.client.fcm

import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.fcm.FcmMessage
import com.linecorp.pushsphere.common.fcm.Notification
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [Request](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send) to send a message
 * to Firebase Cloud Messaging Service.
 */
@Serializable
internal data class FcmRequest(
    val message: FcmMessage,
    // TODO(ikhoon): Add support for dry-run.
    @SerialName("validate_only")
    val validateOnly: Boolean = false,
) {
    companion object {
        fun from(pushReq: PushRequest): FcmRequest {
            val push = pushReq.push
            val firebaseProps = push.firebaseProps ?: FcmMessage()
            var req = FcmRequest(message = firebaseProps)
            var message = req.message
            if (message.notification == null && (push.title != null || push.body != null || push.imageUri != null)) {
                val notification =
                    Notification(
                        title = push.title,
                        body = push.body,
                        image = push.imageUri?.toString(),
                    )
                message = message.copy(notification = notification)
            }
            if (message.token == null) {
                message = message.copy(token = pushReq.deviceToken)
            }

            if (message !== req.message) {
                req = req.copy(message = message)
            }
            return req
        }
    }
}
