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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class CustomPropertySerializer : KSerializer<Any?> {
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("CustomPropertySerializer", PolymorphicKind.OPEN)

    override fun deserialize(decoder: Decoder): Any? {
        val jsonDecoder = decoder as JsonDecoder
        return fromJsonElement(jsonDecoder.decodeJsonElement())
    }

    private fun fromJsonElement(elem: JsonElement): Any? {
        return when (elem) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (elem.isString) {
                    elem.content
                } else if (elem.booleanOrNull != null) {
                    elem.boolean
                } else if (elem.intOrNull != null) {
                    elem.int
                } else if (elem.longOrNull != null) {
                    elem.long
                } else if (elem.doubleOrNull != null) {
                    elem.double
                } else {
                    elem.content
                }
            }

            is JsonObject -> elem.jsonObject.toMap().mapValues { (_, v) -> fromJsonElement(v) }
            is JsonArray -> elem.jsonArray.toList().map { fromJsonElement(it) }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Any?,
    ) {
        (encoder as JsonEncoder).encodeJsonElement(toJsonElement(value))
    }

    private fun toJsonElement(value: Any?): JsonElement {
        if (value == null) {
            return JsonNull
        }

        return when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is CharSequence -> JsonPrimitive(value.toString())
            is Array<*> -> JsonArray(value.map { toJsonElement(it) })
            is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
            is Map<*, *> -> {
                JsonObject(
                    value
                        .filterValues { it != null }
                        .map {
                            "${it.key}" to toJsonElement(it.value)
                        }.toMap(),
                )
            }

            else -> JsonPrimitive(value.toString())
        }
    }
}
