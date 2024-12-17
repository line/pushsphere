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
package com.linecorp.pushsphere.client

import com.linecorp.armeria.client.ClientDecoration
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.pushsphere.common.AppleProfile
import com.linecorp.pushsphere.common.FirebaseProfile
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushsphereProfile
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.common.SendRequest
import com.linecorp.pushsphere.common.exception.TooLargePayloadException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface PushClient : AutoCloseable {
    fun send(
        req: PushRequest,
        listener: PushResultListener<PushRequest>,
    ) {
        send(req, null, listener)
    }

    fun send(
        req: PushRequest,
        pushOptions: PushOptions? = null,
        listener: PushResultListener<PushRequest>,
    )

    suspend fun send(
        req: PushRequest,
        pushOptions: PushOptions? = null,
    ): PushResult {
        return suspendCoroutine { continuation ->
            send(
                req,
                pushOptions,
            ) { result, _, _ ->
                continuation.resume(result)
            }
        }
    }

    fun sendRaw(
        req: RawPushRequest,
        listener: PushResultListener<RawPushRequest>,
    ) {
        sendRaw(req, null, listener)
    }

    fun sendRaw(
        req: RawPushRequest,
        pushOptions: PushOptions? = null,
        listener: PushResultListener<RawPushRequest>,
    )

    suspend fun sendRaw(
        req: RawPushRequest,
        pushOptions: PushOptions? = null,
    ): PushResult {
        return suspendCoroutine { continuation ->
            sendRaw(
                req,
                pushOptions,
            ) { result, _, _ ->
                continuation.resume(result)
            }
        }
    }

    override fun close()

    companion object {
        private val logger = KotlinLogging.logger {}

        fun of(
            profile: Profile,
            meterIdPrefix: MeterIdPrefix? = null,
            meterRegistry: MeterRegistry? = null,
            decoration: ClientDecoration = ClientDecoration.of(),
            shouldRemoveConnectionPoolMonitoring: Boolean = false,
        ): PushClient {
            return when (profile) {
                is AppleProfile ->
                    ApplePushClient(profile, meterIdPrefix, meterRegistry, decoration)

                is FirebaseProfile ->
                    FirebasePushClient(profile, meterIdPrefix, meterRegistry, decoration)

                is PushsphereProfile ->
                    PushspherePushClient(
                        profile,
                        meterIdPrefix,
                        meterRegistry,
                        decoration,
                        shouldRemoveConnectionPoolMonitoring,
                    )
            }
        }

        internal fun <T : SendRequest> invokeHandleResult(
            ctx: ClientRequestContext?,
            listener: PushResultListener<T>,
            result: PushResult,
            req: T,
            endpointUri: URI,
        ) {
            try {
                ctx?.push().use {
                    listener.handleResult(result, req, endpointUri)
                }
            } catch (t: Throwable) {
                logger.warn(t) { "Unexpected exception from PushResultListener.handleResult():" }
            }
        }

        internal fun verifyPayloadSize(
            provider: PushProvider,
            payload: String,
        ) {
            provider.maxPayloadByteLength?.let {
                val jsonByteLength = payload.toByteArray().size
                if (jsonByteLength > it) {
                    throw TooLargePayloadException(provider, jsonByteLength)
                }
            }
        }

        internal fun verifyJsonString(maybeJson: String) {
            if (!maybeJson.startsWith('{')) {
                throw IllegalArgumentException("Provided argument is not JSON format")
            }

            try {
                Json.decodeFromString<JsonObject>(maybeJson)
            } catch (e: Exception) {
                throw IllegalArgumentException("Provided argument is not JSON format")
            }
        }

        /**
         * Encode [payload] and check its size with the limit of each provider's policy.
         * To use it properly, [payload] should be the final state ready to send.
         *
         * @param provider [PushProvider] Provider of current request.
         * In case of [provider] is [PushProvider.GENERIC], it does not check payload size.
         * @param payload Payload that will be sent to provider. [payload] should be serializable.
         * @throws TooLargePayloadException if encoded payload is bigger than push provider's payload size limit.
         */
        internal inline fun <reified T> encodePayloadAndVerify(
            provider: PushProvider,
            payload: T,
        ): String {
            val json = Json.encodeToString(payload)
            verifyPayloadSize(provider, json)
            return json
        }
    }
}

fun interface PushResultListener<T : SendRequest> {
    /**
     * Invoked when the final [PushResult] of the specified [req] has been created.
     * Notice that it can be invoked before sending request to [endpointUri],
     * so you have to check [PushResult.resultSource] to handle [result] properly.
     */
    fun handleResult(
        result: PushResult,
        req: T,
        endpointUri: URI,
    )
}
