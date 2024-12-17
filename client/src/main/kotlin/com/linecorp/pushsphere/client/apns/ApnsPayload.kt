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

import com.linecorp.pushsphere.common.CustomPropertySerializer
import com.linecorp.pushsphere.common.Push
import com.linecorp.pushsphere.common.apns.AppleInterruptionLevel
import com.linecorp.pushsphere.common.apns.ApplePushAlert
import com.linecorp.pushsphere.common.apns.ApplePushType
import com.linecorp.pushsphere.common.apns.AppleSound
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/**
 * see [Generating a remote notification](https://developer.apple.com/documentation/usernotifications/setting_up_a_remote_notification_server/generating_a_remote_notification)
 */
@Serializable(with = ApnsPayloadSerializer::class)
internal data class ApnsPayload(
    val aps: ApnsAps,
    val customData: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        >? = null,
) {
    companion object {
        fun from(push: Push): ApnsPayload {
            verifyPush(push)

            val aps = push.appleProps?.aps

            return ApnsPayload(
                aps =
                    ApnsAps(
                        alert = ApnsAlert.from(push),
                        badge = aps?.badge,
                        sound = aps?.sound,
                        threadId = aps?.threadId,
                        category = aps?.category,
                        contentAvailable = aps?.contentAvailable,
                        mutableContent = aps?.mutableContent,
                        targetContentId = aps?.targetContentId,
                        interruptionLevel = aps?.interruptionLevel,
                        relevanceScore = aps?.relevanceScore,
                        filterCriteria = aps?.filterCriteria,
                        staleDate = aps?.staleDate,
                        contentState = aps?.contentState,
                        timestamp = aps?.timestamp,
                        events = aps?.events,
                        dismissalDate = aps?.dismissalDate,
                    ),
                customData = push.appleProps?.customData,
            )
        }

        private fun verifyPush(push: Push) {
            val headers = push.appleProps?.headers ?: return

            try {
                headers.apnsId?.let { UUID.fromString(it) }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("'apnsId' should be UUID format")
            }

            headers.apnsExpiration?.let {
                if (it < 0) {
                    throw IllegalArgumentException("'apnsExpiration' should be 0 or UNIX epoch expressed in seconds (UTC)")
                }
            }

            val pushType = headers.apnsPushType
            val pushAps = push.appleProps?.aps ?: return

            if (pushType == ApplePushType.BACKGROUND) {
                if (pushAps.contentAvailable != 1 ||
                    headers.apnsPriority != 5 ||
                    pushAps.alert != null ||
                    pushAps.badge != null ||
                    pushAps.sound != null
                ) {
                    throw IllegalArgumentException("Invalid Push for background notification")
                }
            }

            pushAps.timestamp?.let {
                if (it < 0) {
                    throw IllegalArgumentException("'timestamp' should be UNIX timestamp format")
                }
            }

            pushAps.dismissalDate?.let {
                if (it < 0) {
                    throw IllegalArgumentException("'dismissalDate' should be UNIX timestamp format")
                }
            }
        }
    }
}

@Serializable
internal data class ApnsAps(
    val alert: ApnsAlert? = null,
    val badge: Int? = null,
    val sound: AppleSound? = null,
    @SerialName("thread-id")
    val threadId: String? = null,
    val category: String? = null,
    @SerialName("content-available")
    val contentAvailable: Int? = null,
    @SerialName("mutable-content")
    val mutableContent: Int? = null,
    @SerialName("target-content-id")
    val targetContentId: String? = null,
    @SerialName("interruption-level")
    val interruptionLevel: AppleInterruptionLevel? = null,
    @SerialName("relevance-score")
    val relevanceScore: Double? = null,
    @SerialName("filter-criteria")
    val filterCriteria: String? = null,
    @SerialName("stale-date")
    val staleDate: Int? = null,
    @SerialName("content-state")
    val contentState: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        >? = null,
    val timestamp: Long? = null,
    val events: String? = null,
    @SerialName("dismissal-date")
    val dismissalDate: Long? = null,
)

@Serializable(with = ApnsAlertSerializer::class)
internal sealed class ApnsAlert private constructor() {
    @Serializable
    data class String(val value: kotlin.String) : ApnsAlert()

    @Serializable
    data class Dict(
        val title: kotlin.String? = null,
        val subtitle: kotlin.String? = null,
        val body: kotlin.String? = null,
        @SerialName("launch-image")
        val launchImage: kotlin.String? = null,
        @SerialName("title-loc-key")
        val titleLocKey: kotlin.String? = null,
        @SerialName("title-loc-args")
        val titleLocArgs: List<kotlin.String>? = null,
        @SerialName("subtitle-loc-key")
        val subtitleLocKey: kotlin.String? = null,
        @SerialName("subtitle-loc-args")
        val subtitleLocArgs: List<kotlin.String>? = null,
        @SerialName("loc-key")
        val locKey: kotlin.String? = null,
        @SerialName("loc-args")
        val locArgs: List<kotlin.String>? = null,
    ) : ApnsAlert()

    companion object {
        private val emptyAlertDict = Dict()

        fun from(push: Push): ApnsAlert? {
            val aps = push.appleProps?.aps
            val applePushAlert =
                aps?.alert?.let {
                    when (it) {
                        is ApplePushAlert.String -> return@from String(it.value)
                        is ApplePushAlert.Dict -> it
                        else -> throw IllegalArgumentException("'alert' should be one of AlertString or AlertDict")
                    }
                }

            val alertDict =
                Dict(
                    title = push.title,
                    body = push.body,
                    launchImage = push.imageUri?.toString(),
                    subtitle = applePushAlert?.subtitle,
                    titleLocKey = applePushAlert?.titleLocKey,
                    titleLocArgs = applePushAlert?.titleLocArgs,
                    subtitleLocKey = applePushAlert?.subtitleLocKey,
                    subtitleLocArgs = applePushAlert?.subtitleLocArgs,
                    locKey = applePushAlert?.locKey,
                    locArgs = applePushAlert?.locArgs,
                )

            if (alertDict == emptyAlertDict) {
                return null
            }

            return alertDict
        }
    }
}

internal object ApnsPayloadSerializer : KSerializer<ApnsPayload> {
    private val jsonElementSerializer = MapSerializer(String.serializer(), JsonElement.serializer())
    override val descriptor: SerialDescriptor = jsonElementSerializer.descriptor
    private const val APS_KEY = "aps"

    override fun serialize(
        encoder: Encoder,
        value: ApnsPayload,
    ) {
        val map = mutableMapOf<String, JsonElement>()

        map["aps"] = Json.encodeToJsonElement(ApnsAps.serializer(), value.aps)
        value.customData?.forEach { (k, v) -> map[k] = Json.encodeToJsonElement(v) }

        encoder.encodeSerializableValue(jsonElementSerializer, map)
    }

    override fun deserialize(decoder: Decoder): ApnsPayload {
        val apnsPayloadMap = decoder.decodeSerializableValue(jsonElementSerializer)
        val aps =
            apnsPayloadMap["aps"]?.let {
                Json.decodeFromJsonElement(ApnsAps.serializer(), it)
            } ?: throw SerializationException("Invalid JSON for ApnsPayload. 'aps' is null")

        val customData: Map<
            String,
            @Serializable(with = CustomPropertySerializer::class)
            Any,
            >? =
            apnsPayloadMap.filter { (k, _) -> k != APS_KEY }
                .ifEmpty { null }
                ?.mapValues { Json.decodeFromJsonElement(it.value) }

        return ApnsPayload(aps, customData)
    }
}

internal object ApnsAlertSerializer : KSerializer<ApnsAlert> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Alert", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ApnsAlert,
    ) {
        when (value) {
            is ApnsAlert.String -> encoder.encodeString(value.value)
            is ApnsAlert.Dict -> encoder.encodeSerializableValue(ApnsAlert.Dict.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): ApnsAlert {
        val jsonElement = decoder.decodeSerializableValue(JsonElement.serializer())
        return when {
            jsonElement is JsonPrimitive && jsonElement.isString -> ApnsAlert.String(jsonElement.content)
            jsonElement is JsonObject -> decoder.decodeSerializableValue(ApnsAlert.Dict.serializer())
            else -> throw IllegalArgumentException("Invalid JSON for Alert")
        }
    }
}
