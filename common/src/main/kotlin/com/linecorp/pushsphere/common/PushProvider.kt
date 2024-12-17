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

/**
 * Types of push provider that Pushsphere supports with their max payload size limit.
 *
 * For payload size limit of Apple, see [documentation](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/generating_a_remote_notification)
 * Although actual payload size limit of VoIP notification of APNs is 5120 bytes, we decided to apply common size limit
 * with normal remote notification(4096 bytes).
 *
 * For payload size limit of Firebase and web push, see [documentation](https://firebase.google.com/docs/cloud-messaging/concept-options#notifications_and_data_messages)
 */
enum class PushProvider(val maxPayloadByteLength: Int?) {
    GENERIC(null),
    APPLE(4096),

    /*
     * NOTE: (jiwoong)
     * FCM does not count every byte in the whole payload. Instead, it counts the bytes of the keys and values in `data` and `notification` part.
     * We decided not to measure the payload for FCM pushes, as its measurement spec is not concrete.
     * Please refer to the user experiment result (https://stackoverflow.com/a/56668037) for the detail.
     * Plus, for `topic messages`, the maximum payload size is 2048.
     */
    FIREBASE(4096),
    WEB(4000),
}
