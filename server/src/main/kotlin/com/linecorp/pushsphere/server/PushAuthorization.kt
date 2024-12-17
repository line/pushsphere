package com.linecorp.pushsphere.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = PushAuthorizationSerializer::class)
data class PushAuthorization(val scheme: String, val parameters: String) {
    init {
        require(scheme.trim().isNotEmpty()) { "scheme must not be empty" }
        require(parameters.trim().isNotEmpty()) { "parameters must not be empty" }
    }

    fun toHeaderValue() = "$scheme $parameters"

    override fun toString(): String {
        // Omit parameters for security.
        return "${PushAuthorization::class.simpleName}(scheme=$scheme)"
    }

    companion object {
        private val separator = Regex("\\s+")

        fun parse(authorization: String?): PushAuthorization? {
            if (authorization == null) {
                return null
            }

            val components = authorization.trim().split(separator, limit = 2)
            if (components.size != 2) {
                return null
            }

            return PushAuthorization(components[0], components[1])
        }
    }
}

internal class PushAuthorizationSerializer : KSerializer<PushAuthorization> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(PushAuthorization::class.qualifiedName!!, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PushAuthorization {
        val text = decoder.decodeString()
        return PushAuthorization.parse(text)
            ?: throw SerializationException("Failed to parse the authorization (expected: \"<scheme> <parameters>\")")
    }

    override fun serialize(
        encoder: Encoder,
        value: PushAuthorization,
    ) = encoder.encodeString(value.toHeaderValue())
}
