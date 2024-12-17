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

import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.pushsphere.common.credentials.GoogleServiceAccountCredentials
import java.security.PrivateKey
import java.security.cert.X509Certificate

sealed interface Profile {
    val provider: PushProvider
    val endpointUri: URI
    val networkOptions: NetworkOptions
    val retryOptions: RetryOptions
    val retryRateLimitOptions: RetryRateLimitOptions
    val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions

    companion object {
        fun forApple(
            endpointUri: URI,
            accessToken: String,
            bundleId: String,
            networkOptions: NetworkOptions = NetworkOptions.EMPTY,
            retryOptions: RetryOptions = RetryOptions.EMPTY,
            retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
            connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
            endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
            circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
        ): AppleProfile =
            AppleProfile(
                endpointUri,
                networkOptions,
                retryOptions,
                retryRateLimitOptions,
                connectionOutlierDetectionOptions,
                endpointGroupOptions,
                circuitBreakerOptions,
                AppleTokenCredentials(accessToken),
                bundleId,
            )

        fun forApple(
            endpointUri: URI,
            certChain: List<X509Certificate>,
            privateKey: PrivateKey,
            bundleId: String,
            networkOptions: NetworkOptions = NetworkOptions.EMPTY,
            retryOptions: RetryOptions = RetryOptions.EMPTY,
            retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
            connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
            endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
            circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
        ): AppleProfile =
            AppleProfile(
                endpointUri,
                networkOptions,
                retryOptions,
                retryRateLimitOptions,
                connectionOutlierDetectionOptions,
                endpointGroupOptions,
                circuitBreakerOptions,
                AppleKeyPairCredentials(certChain, privateKey),
                bundleId,
            )

        fun forFirebase(
            endpointUri: URI,
            credentials: GoogleServiceAccountCredentials,
            networkOptions: NetworkOptions = NetworkOptions.EMPTY,
            retryOptions: RetryOptions = RetryOptions.EMPTY,
            retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
            connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
            endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
            circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
        ): FirebaseProfile =
            FirebaseProfile(
                endpointUri,
                networkOptions,
                retryOptions,
                retryRateLimitOptions,
                connectionOutlierDetectionOptions,
                endpointGroupOptions,
                circuitBreakerOptions,
                credentials,
            )

        fun forPushsphere(
            endpointUri: URI,
            authScheme: String,
            accessToken: String,
            profileSetGroup: String,
            profileSet: String,
            networkOptions: NetworkOptions = NetworkOptions.EMPTY,
            retryOptions: RetryOptions = RetryOptions.EMPTY,
            retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
            connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
        ): PushsphereProfile =
            PushsphereProfile(
                endpointUri,
                null,
                networkOptions,
                retryOptions,
                retryRateLimitOptions,
                connectionOutlierDetectionOptions,
                authScheme,
                accessToken,
                profileSetGroup,
                profileSet,
            )

        fun forPushsphere(
            endpointUri: URI,
            endpointGroup: EndpointGroup,
            authScheme: String,
            accessToken: String,
            profileSetGroup: String,
            profileSet: String,
            networkOptions: NetworkOptions = NetworkOptions.EMPTY,
            retryOptions: RetryOptions = RetryOptions.EMPTY,
            retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
            connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
        ): PushsphereProfile =
            PushsphereProfile(
                endpointUri,
                endpointGroup,
                networkOptions,
                retryOptions,
                retryRateLimitOptions,
                connectionOutlierDetectionOptions,
                authScheme,
                accessToken,
                profileSetGroup,
                profileSet,
            )

        // TODO: Could have forKafka() here or in a separate module.
    }
}

private fun Profile.validateCommonProperties() {
    require(endpointUri.isAbsolute && endpointUri.rawAuthority != null) { "endpointUri: $endpointUri (expected: an absolute URI)" }
    require(retryOptions.maxAttempts <= 1 || retryOptions.retryPolicies.isNotEmpty()) {
        "retryOptions.retryPolicies is empty (expected: non-empty)"
    }
}

data class AppleProfile internal constructor(
    override val endpointUri: URI,
    override val networkOptions: NetworkOptions,
    override val retryOptions: RetryOptions,
    override val retryRateLimitOptions: RetryRateLimitOptions,
    override val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions,
    val endpointGroupOptions: EndpointGroupOptions,
    val circuitBreakerOptions: CircuitBreakerOptions,
    val credentials: AppleCredentials,
    val bundleId: String,
) : Profile {
    override val provider: PushProvider = PushProvider.APPLE

    init {
        this.validateCommonProperties()
    }
}

sealed interface AppleCredentials

data class AppleKeyPairCredentials(
    val certChain: List<X509Certificate>,
    val privateKey: PrivateKey,
) : AppleCredentials {
    init {
        require(certChain.isNotEmpty()) { "certChain cannot be empty." }
    }
}

data class AppleTokenCredentials(val accessToken: String) : AppleCredentials

data class FirebaseProfile internal constructor(
    override val endpointUri: URI,
    override val networkOptions: NetworkOptions,
    override val retryOptions: RetryOptions,
    override val retryRateLimitOptions: RetryRateLimitOptions,
    override val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions,
    val endpointGroupOptions: EndpointGroupOptions,
    val circuitBreakerOptions: CircuitBreakerOptions,
    val credentials: GoogleServiceAccountCredentials,
) : Profile {
    override val provider: PushProvider = PushProvider.FIREBASE

    init {
        this.validateCommonProperties()
    }
}

data class PushsphereProfile internal constructor(
    override val endpointUri: URI,
    // If endpointGroup is set, `endpointUri` is only used to set SessionProtocol and `:authority` header.
    val endpointGroup: EndpointGroup?,
    override val networkOptions: NetworkOptions,
    override val retryOptions: RetryOptions,
    override val retryRateLimitOptions: RetryRateLimitOptions,
    override val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions,
    val authScheme: String,
    val accessToken: String,
    val profileSetGroup: String,
    val profileSet: String,
) : Profile {
    override val provider: PushProvider = PushProvider.GENERIC

    init {
        this.validateCommonProperties()
        require(authScheme.isNotEmpty()) { "authScheme is empty." }
        require(accessToken.isNotEmpty()) { "accessToken is empty." }
        require(profileSetGroup.isNotEmpty()) { "profileSetGroup is empty." }
        require(profileSet.isNotEmpty()) { "profileSet is empty." }
    }
}
