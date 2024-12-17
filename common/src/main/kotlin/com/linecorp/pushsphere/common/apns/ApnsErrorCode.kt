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
package com.linecorp.pushsphere.common.apns

/**
 * see [Handling notification responses from APNs](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/handling_notification_responses_from_apns)
 */
enum class ApnsErrorCode(val errorString: String) {
    BAD_COLLAPSE_ID("BadCollapseId"),
    BAD_DEVICE_TOKEN("BadDeviceToken"),
    BAD_EXPIRATION_DATE("BadExpirationDate"),
    BAD_MESSAGE_ID("BadMessageId"),
    BAD_PRIORITY("BadPriority"),
    BAD_TOPIC("BadTopic"),
    DEVICE_TOKEN_NOT_FOR_TOPIC("DeviceTokenNotForTopic"),
    DUPLICATE_HEADERS("DuplicateHeaders"),
    IDLE_TIMEOUT("IdleTimeout"),
    INVALID_PUSH_TYPE("InvalidPushType"),
    MISSING_DEVICE_TOKEN("MissingDeviceToken"),
    MISSING_TOPIC("MissingTopic"),
    PAYLOAD_EMPTY("PayloadEmpty"),
    TOPIC_DISALLOWED("TopicDisallowed"),
    BAD_CERTIFICATE("BadCertificate"),
    BAD_CERTIFICATE_ENVIRONMENT("BadCertificateEnvironment"),
    EXPIRED_PROVIDER_TOKEN("ExpiredProviderToken"),
    FORBIDDEN("Forbidden"),
    INVALID_PROVIDER_TOKEN("InvalidProviderToken"),
    MISSING_PROVIDER_TOKEN("MissingProviderToken"),
    BAD_PATH("BadPath"),
    METHOD_NOT_ALLOWED("MethodNotAllowed"),
    EXPIRED_TOKEN("ExpiredToken"),
    UNREGISTERED("Unregistered"),
    PAYLOAD_TOO_LARGE("PayloadTooLarge"),
    TOO_MANY_PROVIDER_TOKEN_UPDATES("TooManyProviderTokenUpdates"),
    TOO_MANY_REQUESTS("TooManyRequests"),
    INTERNAL_SERVER_ERROR("InternalServerError"),
    SERVICE_UNAVAILABLE("ServiceUnavailable"),
    SHUTDOWN("Shutdown"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        private val apnsResponseErrorCodeMap = values().associateBy { it.errorString }

        fun from(errorString: String?): ApnsErrorCode? {
            if (errorString == null) {
                return null
            }
            return apnsResponseErrorCodeMap.getOrDefault(errorString, UNKNOWN)
        }
    }
}
