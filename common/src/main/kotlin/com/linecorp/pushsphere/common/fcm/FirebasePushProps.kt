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
package com.linecorp.pushsphere.common.fcm

import com.linecorp.pushsphere.common.CustomPropertySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.beans.Visibility

/**
 * [Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#resource:-message)
 * to send by Firebase Cloud Messaging Service.
 *
 * ```json
 * {
 *   "name": string,
 *   "data": {
 *     string: string,
 *     ...
 *   },
 *   "notification": {
 *     object (Notification)
 *   },
 *   "android": {
 *     object (AndroidConfig)
 *   },
 *   "webpush": { // Unsupported.
 *     object (WebpushConfig)
 *   },
 *   "apns": { // Unsupported. Use ApplePushClient instead.
 *     object (ApnsConfig)
 *   },
 *   "fcm_options": {
 *     object (FcmOptions)
 *   },
 *
 *   // Union field target can be only one of the following:
 *   "token": string,
 *   "topic": string,
 *   "condition": string
 *   // End of list of possible types for union field target.
 * }
 * ```
 */
@Serializable
data class FirebasePushProps(
    val name: String? = null,
    val data: Map<String, String>? = null,
    val notification: Notification? = null,
    val android: AndroidConfig? = null,
    @SerialName("fcm_options")
    val fcmOptions: FcmOptions? = null,
    val token: String? = null,
    val topic: String? = null,
    val condition: String? = null,
)

typealias FcmMessage = FirebasePushProps

/**
 * Basic [Notification](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#notification)
 * template to use across all platforms.
 */
@Serializable
data class Notification(
    val title: String? = null,
    val body: String? = null,
    val image: String? = null,
)

/**
 * [Android specific options](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#androidconfig)
 * for messages sent through FCM connection server.
 *
 * ```json
 * {
 *   "collapse_key": string,
 *   "priority": enum (AndroidMessagePriority),
 *   "ttl": string,
 *   "restricted_package_name": string,
 *   "data": {
 *     string: string,
 *     ...
 *   },
 *   "notification": {
 *     object (AndroidNotification)
 *   },
 *   "fcm_options": {
 *     object (AndroidFcmOptions)
 *   },
 *   "direct_boot_ok": boolean
 * }
 * ```
 *
 */
@Serializable
data class AndroidConfig(
    @SerialName("collapse_key")
    val collapseKey: String? = null,
    val priority: AndroidMessagePriority? = null,
    val ttl: String? = null,
    @SerialName("restricted_package_name")
    val restrictedPackageName: String? = null,
    val data: Map<String, String>? = null,
    val notification: AndroidNotification? = null,
    @SerialName("direct_boot_ok")
    val directBootOk: Boolean? = null,
)

/**
 * [Priority of a message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#androidmessagepriority)
 * to send to Android devices.
 */
enum class AndroidMessagePriority {
    NORMAL,
    HIGH,
}

/**
 * [Notification](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#AndroidNotification)
 * to send to Android devices.
 * ```json
 * {
 *   "title": string,
 *   "body": string,
 *   "icon": string,
 *   "color": string,
 *   "sound": string,
 *   "tag": string,
 *   "click_action": string,
 *   "body_loc_key": string,
 *   "body_loc_args": [
 *     string
 *   ],
 *   "title_loc_key": string,
 *   "title_loc_args": [
 *     string
 *   ],
 *   "channel_id": string,
 *   "ticker": string,
 *   "sticky": boolean,
 *   "event_time": string,
 *   "local_only": boolean,
 *   "notification_priority": enum (NotificationPriority),
 *   "default_sound": boolean,
 *   "default_vibrate_timings": boolean,
 *   "default_light_settings": boolean,
 *   "vibrate_timings": [
 *     string
 *   ],
 *   "visibility": enum (Visibility),
 *   "notification_count": integer,
 *   "light_settings": {
 *     object (LightSettings)
 *   },
 *   "image": string,
 * }
 * ```
 */
@Serializable
data class AndroidNotification(
    val icon: String? = null,
    val color: String? = null,
    val sound: String? = null,
    val tag: String? = null,
    @SerialName("click_action")
    val clickAction: String? = null,
    @SerialName("body_loc_key")
    val bodyLocKey: String? = null,
    @SerialName("body_loc_args")
    val bodyLocArgs: List<String>? = null,
    @SerialName("title_loc_key")
    val titleLocKey: String? = null,
    @SerialName("title_loc_args")
    val titleLocArgs: List<String>? = null,
    @SerialName("channel_id")
    val channelId: String? = null,
    val ticker: String? = null,
    val sticky: Boolean? = null,
    @SerialName("event_time")
    val eventTime: String? = null,
    @SerialName("local_only")
    val localOnly: Boolean? = null,
    @SerialName("notification_priority")
    val notificationPriority: NotificationPriority? = null,
    @SerialName("default_sound")
    val defaultSound: Boolean? = null,
    @SerialName("default_vibrate_timings")
    val defaultVibrateTimings: Boolean? = null,
    @SerialName("default_light_settings")
    val defaultLightSettings: Boolean? = null,
    @SerialName("vibrate_timings")
    val vibrateTimings: List<String>? = null,
    val visibility: Visibility? = null,
    @SerialName("notification_count")
    val notificationCount: Int? = null,
    @SerialName("light_settings")
    val lightSettings: LightSettings? = null,
)

/**
 * [Priority levels](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#notificationpriority)
 * of a notification.
 *
 */
@Serializable
enum class NotificationPriority {
    PRIORITY_UNSPECIFIED,
    PRIORITY_MIN,
    PRIORITY_LOW,
    PRIORITY_DEFAULT,
    PRIORITY_HIGH,
    PRIORITY_MAX,
}

/**
 * Different [visibility](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#visibility)
 * levels of a notification.
 */
@Serializable
enum class Visibility {
    VISIBILITY_UNSPECIFIED,
    PRIVATE,
    PUBLIC,
    SECRET,
}

/**
 * Settings to control [notification LED](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#lightsettings).
 *
 * ```json
 * {
 *   "color": {
 *     object (Color)
 *   },
 *   "light_on_duration": string,
 *   "light_off_duration": string
 * }
 * ```
 */
@Serializable
data class LightSettings(
    val color: Color? = null,
    @SerialName("light_on_duration")
    val lightOnDuration: String? = null,
    @SerialName("light_off_duration")
    val lightOffDuration: String? = null,
)

/**
 * Represents a [color](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#color)
 * in the RGBA color space.
 *
 * ```json
 * {
 *   "red": number,
 *   "green": number,
 *   "blue": number,
 *   "alpha": number
 * }
 * ```
 */
@Serializable
data class Color(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
)

/**
 * [Platform independent options](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages#fcmoptions)
 * for features provided by the FCM SDKs.
 *
 * ```json
 * {
 *   "analytics_label": string
 * }
 * ```
 */
@Serializable
data class FcmOptions(
    @SerialName("analytics_label")
    val analyticsLabel: String? = null,
)

/**
 * FCM Error response.
 *
 * ```json
 * {
 *   "error": {
 *     "code": 400,
 *     "message": "The registration token is not a valid FCM registration token",
 *     "status": "INVALID_ARGUMENT",
 *     "details": [
 *       {
 *         "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
 *         "errorCode": "INVALID_ARGUMENT"
 *       }
 *     ]
 *   }
 * }
 * ```
 *
 */
@Serializable
data class FcmErrorResponse(val error: FcmError)

@Serializable
data class FcmError(
    val code: Int,
    val message: String,
    val status: String,
    val details: List<FcmErrorDetails> = emptyList(),
)

@Serializable(with = FcmErrorDetailsSerializer::class)
data class FcmErrorDetails(
    @SerialName("@type") val type: String,
    val customData: Map<
        String,
        @Serializable(with = CustomPropertySerializer::class)
        Any?,
        >? = null,
)

private object FcmErrorDetailsSerializer : KSerializer<FcmErrorDetails> {
    private val jsonElementSerializer = MapSerializer(String.serializer(), JsonElement.serializer())
    override val descriptor: SerialDescriptor = jsonElementSerializer.descriptor
    private const val TYPE_KEY = "@type"

    override fun deserialize(decoder: Decoder): FcmErrorDetails {
        val payload = decoder.decodeSerializableValue(jsonElementSerializer)
        val type =
            payload[TYPE_KEY]?.let {
                Json.decodeFromJsonElement(String.serializer(), it)
            } ?: "unknown"

        val errorDetails: Map<
            String,
            @Serializable(with = CustomPropertySerializer::class)
            Any,
            >? =
            payload.filter { (k, _) -> k != TYPE_KEY }
                .ifEmpty { null }
                ?.mapValues { Json.decodeFromJsonElement(it.value) }

        return FcmErrorDetails(type, errorDetails)
    }

    override fun serialize(
        encoder: Encoder,
        value: FcmErrorDetails,
    ) {
        val map = mutableMapOf<String, JsonElement>()
        map[TYPE_KEY] = Json.encodeToJsonElement(value.type)
        value.customData?.forEach { (k, v) -> map[k] = Json.encodeToJsonElement(v) }
        encoder.encodeSerializableValue(jsonElementSerializer, map)
    }
}
