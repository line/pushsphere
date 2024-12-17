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
package com.linecorp.pushsphere.mock.server

import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class MockRequest(
    val method: HttpMethod? = null,
    val pathPattern: String,
    val headers: Map<String, String?> = emptyMap(),
    val contentType: MediaType? = null,
    val body: String = "",
) {
    @Transient
    private var route: Route? = null

    fun route(): Route {
        var route = this.route
        if (route != null) {
            return route
        }

        val builder = Route.builder()
        if (method != null) {
            builder.methods(method)
        }
        if (contentType != null) {
            builder.consumes(contentType)
        }
        builder.path(pathPattern)
        for (header in headers) {
            if (header.value.isNullOrEmpty()) {
                builder.matchesHeaders(header.key)
            } else {
                builder.matchesHeaders(header.key) { value -> value == header.value }
            }
        }
        route = builder.build()
        this.route = route
        return route
    }

    fun matches(
        ctx: ServiceRequestContext,
        request: AggregatedHttpRequest,
    ): Boolean {
        if (ctx.routingContext().result().route() != route) {
            return false
        }

        if (body.isEmpty()) {
            return true
        }

        return body == request.contentUtf8()
    }
}

private typealias MediaType =
    @Serializable(with = MediaTypeSerializer::class)
    com.linecorp.armeria.common.MediaType

private class MediaTypeSerializer : KSerializer<MediaType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(MediaType::class.qualifiedName!!, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MediaType = MediaType.parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: MediaType,
    ) = encoder.encodeString(value.toString())
}
