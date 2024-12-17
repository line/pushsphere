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
import com.linecorp.armeria.client.InvalidHttpResponseException
import com.linecorp.armeria.client.RequestOptions
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.common.ExchangeType
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.util.Exceptions
import com.linecorp.pushsphere.client.apns.ApnsHeaders
import com.linecorp.pushsphere.client.apns.ApnsHeaders.APNS_TOPIC
import com.linecorp.pushsphere.client.apns.ApnsPayload
import com.linecorp.pushsphere.client.apns.ApnsResponse
import com.linecorp.pushsphere.client.retry.DynamicRetryConfigMapping
import com.linecorp.pushsphere.common.AppleKeyPairCredentials
import com.linecorp.pushsphere.common.AppleProfile
import com.linecorp.pushsphere.common.AppleTokenCredentials
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.common.SendRequest
import io.micrometer.core.instrument.MeterRegistry

@Suppress("ktlint:standard:discouraged-comment-location")
internal class ApplePushClient(
    private val profile: AppleProfile,
    private val meterIdPrefix: MeterIdPrefix?,
    val meterRegistry: MeterRegistry?,
    decoration: ClientDecoration,
    val baseEndpointGroup: EndpointGroup? = null, // for testing
) : AbstractPushClient(
        profile,
        meterIdPrefix,
        meterRegistry,
        decoration,
        when (profile.credentials) {
            is AppleKeyPairCredentials -> (profile.credentials as AppleKeyPairCredentials).certChain
            is AppleTokenCredentials -> emptyList()
        },
        when (profile.credentials) {
            is AppleKeyPairCredentials -> (profile.credentials as AppleKeyPairCredentials).privateKey
            is AppleTokenCredentials -> null
        },
        when (profile.credentials) {
            is AppleKeyPairCredentials -> HttpHeaders.of(APNS_TOPIC, profile.bundleId)
            is AppleTokenCredentials -> {
                val authorization = "Bearer ${(profile.credentials as AppleTokenCredentials).accessToken}"
                HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, authorization, APNS_TOPIC, profile.bundleId)
            }
        },
    ) {
    private var outlierDetectingEndpointGroup: OutlierDetectingEndpointGroup? = null

    override fun endpointGroupMapper(endpointGroup: EndpointGroup): EndpointGroup {
        var endpointGroup0 = baseEndpointGroup ?: endpointGroup
        endpointGroup0 =
            OutlierDetectingEndpointGroup(
                delegate = endpointGroup0,
                endpointGroupOptions = profile.endpointGroupOptions,
                circuitBreakerOptions = profile.circuitBreakerOptions,
                meterIdPrefix,
                meterRegistry,
            )
        outlierDetectingEndpointGroup = endpointGroup0
        return endpointGroup0
    }

    override fun additionalDecorators(): ClientDecoration {
        // endpointGroupMapper should be called before additionalDecorators
        assert(outlierDetectingEndpointGroup != null)
        return ClientDecoration.of(outlierDetectingEndpointGroup!!.asHttpDecorator())
    }

    override fun send(
        req: PushRequest,
        pushOptions: PushOptions?,
        listener: PushResultListener<PushRequest>,
    ) {
        try {
            require(req.provider == PushProvider.APPLE || req.provider == PushProvider.GENERIC) {
                "Unsupported provider: ${req.provider}"
            }

            val push = req.push
            val payload = PushClient.encodePayloadAndVerify(req.provider, ApnsPayload.from(push))
            val headers = ApnsHeaders.from(push)

            send(req, pushOptions, headers, payload, listener)
        } catch (t: Throwable) {
            val pushStatus = pushStatusFromThrowable(t)
            PushClient.invokeHandleResult(
                null,
                listener,
                PushResult(
                    pushStatus,
                    PushResultSource.CLIENT,
                    t.message,
                    t,
                    httpStatus = pushStatus.httpStatus(),
                ),
                req,
                endpointUri(),
            )
        }
    }

    override fun sendRaw(
        req: RawPushRequest,
        pushOptions: PushOptions?,
        listener: PushResultListener<RawPushRequest>,
    ) {
        try {
            require(req.provider == PushProvider.APPLE || req.provider == PushProvider.GENERIC) {
                "Unsupported provider: ${req.provider}"
            }

            val payload = req.rawPush.content
            PushClient.verifyPayloadSize(req.provider, payload)
            PushClient.verifyJsonString(payload)

            val headers = ApnsHeaders.from(req.rawPush.headers)

            send(req, pushOptions, headers, payload, listener)
        } catch (t: Throwable) {
            val pushStatus = pushStatusFromThrowable(t)
            PushClient.invokeHandleResult(
                null,
                listener,
                PushResult(
                    pushStatus,
                    PushResultSource.CLIENT,
                    t.message,
                    t,
                    httpStatus = pushStatus.httpStatus(),
                ),
                req,
                endpointUri(),
            )
        }
    }

    private fun <T : SendRequest> send(
        req: T,
        pushOptions: PushOptions?,
        headers: HttpHeaders,
        payload: String,
        listener: PushResultListener<T>,
    ) {
        val request =
            HttpRequest.of(
                RequestHeaders.builder()
                    .method(HttpMethod.POST)
                    .path("/3/device/${req.deviceToken}")
                    .add(headers)
                    .build(),
                HttpData.ofUtf8(payload),
            )
        val requestOptions =
            if (pushOptions != null) {
                RequestOptions
                    .builder()
                    .exchangeType(ExchangeType.UNARY)
                    .attr(
                        DynamicRetryConfigMapping.PUSH_OPTIONS,
                        pushOptions,
                    )
                    .build()
            } else {
                null
            }

        val (ctx, response) = execute(request, requestOptions)

        response
            .aggregate()
            .handle { res, ex ->
                val result =
                    if (ex != null) {
                        val peeled = Exceptions.peel(ex)
                        PushResult(
                            PushStatus.INTERNAL_ERROR,
                            if (peeled is UnprocessedRequestException) PushResultSource.CLIENT else PushResultSource.PUSH_PROVIDER,
                            peeled.message,
                            peeled,
                            httpStatus = PushStatus.INTERNAL_ERROR.httpStatus(),
                        )
                    } else {
                        try {
                            ApnsResponse.from(res).toPushResult()
                        } catch (e: Throwable) {
                            val wrapped = InvalidHttpResponseException(res, e)
                            PushResult(
                                PushStatus.INVALID_SERVER_RESPONSE,
                                PushResultSource.PUSH_PROVIDER,
                                "${e.message}: response headers=${res.headers()}, response content=${res.contentUtf8()}",
                                wrapped,
                                httpStatus = res.status().code(),
                            )
                        }
                    }

                PushClient.invokeHandleResult(ctx, listener, result, req, endpointUri())
            }
    }
}
