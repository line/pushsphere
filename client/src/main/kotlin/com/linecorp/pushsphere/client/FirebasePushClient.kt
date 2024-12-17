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
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.InvalidHttpResponseException
import com.linecorp.armeria.client.RequestOptions
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.auth.oauth2.AccessTokenRequest
import com.linecorp.armeria.client.auth.oauth2.OAuth2AuthorizationGrant
import com.linecorp.armeria.client.auth.oauth2.OAuth2Client
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.ExchangeType
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.common.util.Exceptions
import com.linecorp.pushsphere.client.fcm.FcmRequest
import com.linecorp.pushsphere.client.retry.DynamicRetryConfigMapping
import com.linecorp.pushsphere.common.FirebaseProfile
import com.linecorp.pushsphere.common.FirebasePushResultProps
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.common.SelectionStrategy
import com.linecorp.pushsphere.common.SendRequest
import com.linecorp.pushsphere.common.fcm.FcmErrorResponse
import com.linecorp.pushsphere.common.fcm.FcmMessage
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration

@Suppress("ktlint:standard:discouraged-comment-location")
internal class FirebasePushClient(
    val profile: FirebaseProfile,
    private val meterIdPrefix: MeterIdPrefix?,
    val meterRegistry: MeterRegistry?,
    decoration: ClientDecoration,
    selectionStrategy: SelectionStrategy = SelectionStrategy.ROUND_ROBIN,
    val baseEndpointGroup: EndpointGroup? = null, // for test purpose only
) : AbstractPushClient(
        profile,
        meterIdPrefix,
        meterRegistry,
        decoration,
        emptyList(),
        null,
        HttpHeaders.of(),
        selectionStrategy,
    ) {
    private val oAuth2ClientFactory: ClientFactory =
        ClientFactory.builder().apply {
            if (meterRegistry != null) {
                meterRegistry(meterRegistry)
            }
        }.build()

    private var outlierDetectingEndpointGroup: OutlierDetectingEndpointGroup? = null

    override fun send(
        req: PushRequest,
        pushOptions: PushOptions?,
        listener: PushResultListener<PushRequest>,
    ) {
        try {
            require(req.provider == PushProvider.FIREBASE || req.provider == PushProvider.GENERIC) {
                "Unsupported provider: ${req.provider}"
            }

            val fcmRequest = FcmRequest.from(req)
            val payload = Json.encodeToString(fcmRequest)
            // TODO(ikhoon): Add more validation for FCM payload.

            send(req, pushOptions, payload, listener)
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
            require(req.provider == PushProvider.FIREBASE || req.provider == PushProvider.GENERIC) {
                "Unsupported provider: ${req.provider}"
            }

            val payload = req.rawPush.content
            PushClient.verifyJsonString(payload)

            send(req, pushOptions, payload, listener)
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
        payload: String,
        listener: PushResultListener<T>,
    ) {
        val httpRequest =
            HttpRequest.builder()
                .method(HttpMethod.POST)
                .path("/v1/projects/${profile.credentials.serviceAccount.projectId}/messages:send")
                .disablePathParams()
                .content(MediaType.JSON, payload)
                .build()

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

        val (ctx, response) = execute(httpRequest, requestOptions)

        // TODO(ikhoon): Deduplicate this code with ApplePushClient.
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
                        val status = res.status()
                        val content = res.contentUtf8()
                        try {
                            val resultProps =
                                if (status == HttpStatus.OK) {
                                    val message = Json.decodeFromString<FcmMessage>(content)
                                    FirebasePushResultProps(messageId = message.name)
                                } else {
                                    if (res.contentType()?.isJson != true) {
                                        throw InvalidHttpResponseException(
                                            res,
                                            "Invalid content type: ${res.contentType()}",
                                            null,
                                        )
                                    }
                                    val errorResponse = Json.decodeFromString<FcmErrorResponse>(content)
                                    val retryAfter = res.headers().get(HttpHeaderNames.RETRY_AFTER)
                                    FirebasePushResultProps(error = errorResponse.error, retryAfter = retryAfter)
                                }

                            PushResult(
                                status = PushStatus.of(status.code()),
                                resultSource = PushResultSource.PUSH_PROVIDER,
                                reason = resultProps.error?.message,
                                pushResultProps = resultProps,
                                httpStatus = status.code(),
                            )
                        } catch (e: Throwable) {
                            PushResult(
                                PushStatus.INVALID_SERVER_RESPONSE,
                                PushResultSource.PUSH_PROVIDER,
                                "${e.message}: response headers=${res.headers()}, response content=$content",
                                e,
                                httpStatus = status.code(),
                            )
                        }
                    }
                PushClient.invokeHandleResult(ctx, listener, result, req, endpointUri())
            }
    }

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

    /**
     * Returns
     * - OAuth2Client to obtain an access token using JWT before sending a request to the FCM server.
     * - OutlierDetectingEndpointGroup as HTTP decorator to enable by-Endpoint circuit breaker.
     */
    override fun additionalDecorators(): ClientDecoration {
        // endpointGroupMapper should be called before additionalDecorators
        assert(outlierDetectingEndpointGroup != null)

        val grant =
            OAuth2AuthorizationGrant
                .builder(authClient(), "")
                // Do not cache JWT token because it is time-sensitive.
                .accessTokenRequest { AccessTokenRequest.ofJsonWebToken(profile.credentials.createAssertion()) }
                // Referenced from https://github.com/googleapis/google-auth-library-java/blob/9e11763e79127b3691533488482575adef6f73d2/oauth2_http/java/com/google/auth/oauth2/OAuth2Credentials.java#L71
                .refreshBefore(Duration.ofMinutes(3))
                .build()

        return ClientDecoration.builder()
            .add(outlierDetectingEndpointGroup!!.asHttpDecorator())
            .add(OAuth2Client.newDecorator(grant))
            .build()
    }

    private fun authClient(): WebClient {
        val retryRule =
            RetryRule.builder()
                .onStatus(retryableStatus)
                .onUnprocessed()
                .thenBackoff()
        return WebClient.builder(profile.credentials.serviceAccount.tokenUri)
            .apply {
                if (meterIdPrefix != null) {
                    decorator(
                        MetricCollectingClient.newDecorator(
                            MeterIdPrefixFunction.ofDefault(meterIdPrefix.name("oauth"))
                                .withTags(meterIdPrefix.tags()),
                        ),
                    )
                }
            }
            .factory(oAuth2ClientFactory)
            .decorator(RetryingClient.newDecorator(retryRule))
            .build()
    }

    companion object {
        // Forked from https://github.com/googleapis/google-auth-library-java/blob/3a546fb21a747a2d596b24a530391b7aedd1ebc7/oauth2_http/java/com/google/auth/oauth2/OAuth2Utils.java#L97
        val retryableStatus =
            listOf(
                HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.REQUEST_TIMEOUT,
                HttpStatus.TOO_MANY_REQUESTS,
            )
    }
}
