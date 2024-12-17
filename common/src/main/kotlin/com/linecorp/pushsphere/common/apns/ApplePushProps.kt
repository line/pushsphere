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

import com.linecorp.pushsphere.common.CustomPropertySerializer
import com.linecorp.pushsphere.common.EnumStringFieldSerializer
import kotlinx.serialization.KSerializer
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

@Serializable(with = ApplePushPropsSerializer::class)
data class ApplePushProps(
    val headers: AppleHeaders? = null,
    val aps: AppleAps? = null,
    val customData: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        >? = null,
)

@Serializable
data class AppleHeaders(
    val apnsPushType: ApplePushType? = null,
    val apnsId: String? = null,
    val apnsExpiration: Long? = null,
    val apnsPriority: Int? = null,
    val apnsCollapseId: String? = null,
)

@Serializable
data class AppleAps(
    val alert: ApplePushAlert? = null,
    val badge: Int? = null,
    val sound: AppleSound? = null,
    val threadId: String? = null,
    val category: String? = null,
    val contentAvailable: Int? = null,
    val mutableContent: Int? = null,
    val targetContentId: String? = null,
    val interruptionLevel: AppleInterruptionLevel? = null,
    val relevanceScore: Double? = null,
    val filterCriteria: String? = null,
    val staleDate: Int? = null,
    val contentState: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        >? = null,
    val timestamp: Long? = null,
    val events: String? = null,
    val dismissalDate: Long? = null,
)

@Serializable(with = ApplePushAlertSerializer::class)
sealed class ApplePushAlert private constructor() {
    @Serializable
    data class String(val value: kotlin.String) : ApplePushAlert()

    @Serializable
    data class Dict(
        val subtitle: kotlin.String? = null,
        val titleLocKey: kotlin.String? = null,
        val titleLocArgs: List<kotlin.String>? = null,
        val subtitleLocKey: kotlin.String? = null,
        val subtitleLocArgs: List<kotlin.String>? = null,
        val locKey: kotlin.String? = null,
        val locArgs: List<kotlin.String>? = null,
    ) : ApplePushAlert()
}

@Serializable(with = AppleSoundSerializer::class)
sealed class AppleSound private constructor() {
    @Serializable
    data class String(val name: kotlin.String) : AppleSound()

    @Serializable
    data class Dict(val critical: Int, val name: kotlin.String, val volume: Float) : AppleSound()
}

@Serializable(with = ApplePushTypeSerializer::class)
enum class ApplePushType {
    ALERT,
    BACKGROUND,
    LOCATION,
    VOIP,
    COMPLICATION,
    FILEPROVIDER,
    MDM,
    LIVEACTIVITY,
    PUSHTOTALK,
    ;

    val value: String = this.name.lowercase()
}

@Serializable(with = AppleInterruptionLevelSerializer::class)
enum class AppleInterruptionLevel(val value: String) {
    PASSIVE("passive"),
    ACTIVE("active"),
    TIMESENSITIVE("time-sensitive"),
    CRITICAL("critical"),
}

internal object ApplePushPropsSerializer : KSerializer<ApplePushProps> {
    private val stringToJsonElementSerializer = MapSerializer(String.serializer(), JsonElement.serializer())

    override val descriptor: SerialDescriptor = stringToJsonElementSerializer.descriptor

    override fun serialize(
        encoder: Encoder,
        value: ApplePushProps,
    ) {
        val map = mutableMapOf<String, JsonElement>()

        value.headers?.let { map["headers"] = Json.encodeToJsonElement(AppleHeaders.serializer(), it) }
        value.aps?.let { map["aps"] = Json.encodeToJsonElement(AppleAps.serializer(), it) }
        value.customData?.forEach { (k, v) -> map[k] = Json.encodeToJsonElement(v) }

        encoder.encodeSerializableValue(stringToJsonElementSerializer, map)
    }

    override fun deserialize(decoder: Decoder): ApplePushProps {
        val applePushPropsMap = decoder.decodeSerializableValue(stringToJsonElementSerializer)
        val knownKeys = setOf("headers", "aps")

        val headers =
            applePushPropsMap["headers"]?.let {
                Json.decodeFromJsonElement(AppleHeaders.serializer(), it)
            }
        val aps =
            applePushPropsMap["aps"]?.let {
                Json.decodeFromJsonElement(AppleAps.serializer(), it)
            }
        val customData: Map<
            String,
            @Serializable(with = CustomPropertySerializer::class)
            Any,
            >? =
            applePushPropsMap.filter { (k, _) -> !knownKeys.contains(k) }
                .ifEmpty { null }
                ?.mapValues { Json.decodeFromJsonElement(it.value) }

        return ApplePushProps(headers, aps, customData)
    }
}

internal object ApplePushAlertSerializer : KSerializer<ApplePushAlert> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ApplePushAlert", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ApplePushAlert,
    ) {
        when (value) {
            is ApplePushAlert.String -> encoder.encodeString(value.value)
            is ApplePushAlert.Dict ->
                encoder.encodeSerializableValue(
                    ApplePushAlert.Dict.serializer(),
                    value,
                )
        }
    }

    override fun deserialize(decoder: Decoder): ApplePushAlert {
        val jsonElement = decoder.decodeSerializableValue(JsonElement.serializer())
        return when {
            jsonElement is JsonPrimitive && jsonElement.isString -> ApplePushAlert.String(jsonElement.content)
            jsonElement is JsonObject -> decoder.decodeSerializableValue(ApplePushAlert.Dict.serializer())
            else -> throw SerializationException("Invalid JSON for ApplePushAlert")
        }
    }
}

internal object AppleSoundSerializer : KSerializer<AppleSound> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Sound", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AppleSound,
    ) {
        when (value) {
            is AppleSound.String -> encoder.encodeString(value.name)
            is AppleSound.Dict -> encoder.encodeSerializableValue(AppleSound.Dict.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): AppleSound {
        val jsonElement = decoder.decodeSerializableValue(JsonElement.serializer())
        return when {
            jsonElement is JsonPrimitive && jsonElement.isString -> AppleSound.String(jsonElement.content)
            jsonElement is JsonObject -> decoder.decodeSerializableValue(AppleSound.Dict.serializer())
            else -> throw SerializationException("Invalid JSON for Sound")
        }
    }
}

internal object ApplePushTypeSerializer : EnumStringFieldSerializer<ApplePushType>(
    "ApplePushType",
    { it.value },
    { value -> ApplePushType.values().first { it.value == value } },
)

internal object AppleInterruptionLevelSerializer : EnumStringFieldSerializer<AppleInterruptionLevel>(
    "AppleInterruptionLevel",
    { it.value },
    { value -> AppleInterruptionLevel.values().first { it.value == value } },
)
