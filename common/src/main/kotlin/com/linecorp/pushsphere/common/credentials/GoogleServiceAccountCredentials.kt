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
package com.linecorp.pushsphere.common.credentials

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * A class that represents the Google Service Account Credentials.
 */
class GoogleServiceAccountCredentials(val serviceAccount: ServiceAccount) {
    // Don't use Json.decodeFromStream() until we upgrade kotlinx.serialization to 1.6.0+.
    // It raises 'NoSuchMethodError ByteBuffer.position' on Java 8
    // See: https://github.com/Kotlin/kotlinx.serialization/pull/2350
    constructor(serviceAccountFile: InputStream) : this(Json.decodeFromString<ServiceAccount>(String(serviceAccountFile.readBytes())))

    constructor(serviceAccountFile: File) : this(FileInputStream(serviceAccountFile))

    private val privateKey: RSAPrivateKey = convertPEMToPrivateKey()

    // Forked from https://github.com/googleapis/google-auth-library-java/blob/9e11763e79127b3691533488482575adef6f73d2/oauth2_http/java/com/google/auth/oauth2/ServiceAccountCredentials.java#L803
    fun createAssertion(): String {
        val currentTime = Instant.now()
        return JWT.create()
            .withKeyId(serviceAccount.privateKeyId)
            .withIssuer(serviceAccount.clientEmail)
            .withIssuedAt(currentTime)
            .withExpiresAt(currentTime.plusSeconds(DEFAULT_LIFETIME_IN_SECONDS))
            .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .withAudience(serviceAccount.tokenUri)
            .sign(Algorithm.RSA256(privateKey))
    }

    companion object {
        // The default lifetime in seconds specified in the Google Auth Library for Java.
        // https://github.com/googleapis/google-auth-library-java/blob/9e11763e79127b3691533488482575adef6f73d2/oauth2_http/java/com/google/auth/oauth2/ServiceAccountCredentials.java#L91
        private const val DEFAULT_LIFETIME_IN_SECONDS = 3600L
    }

    private fun convertPEMToPrivateKey(): RSAPrivateKey {
        // Remove the first and last lines
        val sanitizedPem =
            serviceAccount.privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                // Remove the line breaks
                .replace("\\s+".toRegex(), "")
        // Decode the Base64 content
        val pkcs8EncodedBytes = Base64.getDecoder().decode(sanitizedPem)
        val keySpec = PKCS8EncodedKeySpec(pkcs8EncodedBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(keySpec) as RSAPrivateKey
    }
}

@Serializable
data class ServiceAccount(
    val type: String,
    @SerialName("project_id")
    val projectId: String,
    @SerialName("private_key_id")
    val privateKeyId: String,
    @SerialName("private_key")
    val privateKey: String,
    @SerialName("client_email")
    val clientEmail: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("auth_uri")
    val authUri: String,
    @SerialName("token_uri")
    val tokenUri: String,
    @SerialName("auth_provider_x509_cert_url")
    val authProviderX509CertUrl: String,
    @SerialName("client_x509_cert_url")
    val clientX509CertUrl: String,
    @SerialName("universe_domain")
    val universeDomain: String,
)
