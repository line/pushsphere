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
import com.linecorp.armeria.client.RequestOptions
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.common.ExchangeType
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.util.Exceptions
import com.linecorp.pushsphere.client.retry.DynamicRetryConfigMapping
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.PushsphereProfile
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.common.SendRequest
import com.linecorp.pushsphere.internal.common.PushsphereHeaderNames
import com.linecorp.pushsphere.internal.common.RemoteRetryOptionsSerDes
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class PushspherePushClient(
    pushsphereProfile: PushsphereProfile,
    meterIdPrefix: MeterIdPrefix?,
    meterRegistry: MeterRegistry?,
    decoration: ClientDecoration,
    shouldRemoveConnectionPoolMonitoring: Boolean = false,
) : AbstractPushClient(
        pushsphereProfile,
        meterIdPrefix,
        meterRegistry,
        decoration,
        emptyList(),
        null,
        HttpHeaders.of(
            HttpHeaderNames.AUTHORIZATION,
            "${pushsphereProfile.authScheme} ${pushsphereProfile.accessToken}",
        ),
        shouldRemoveConnectionPoolMonitoring = shouldRemoveConnectionPoolMonitoring,
    ) {
    private val sendRequestHeaders: RequestHeaders
    private val sendRawRequestHeaders: RequestHeaders

    init {
        val profileSetGroup = pushsphereProfile.profileSetGroup
        val profileSet = pushsphereProfile.profileSet

        sendRequestHeaders =
            RequestHeaders.builder()
                .method(HttpMethod.POST)
                .path("/api/v1/$profileSetGroup/$profileSet/send")
                .contentType(MediaType.JSON)
                .build()

        sendRawRequestHeaders =
            RequestHeaders.builder()
                .method(HttpMethod.POST)
                .path("/api/v1/$profileSetGroup/$profileSet/send/raw")
                .contentType(MediaType.JSON)
                .build()
    }

    override fun send(
        req: PushRequest,
        pushOptions: PushOptions?,
        listener: PushResultListener<PushRequest>,
    ) {
        executeRequest(req, pushOptions, listener, sendRequestHeaders)
    }

    override fun sendRaw(
        req: RawPushRequest,
        pushOptions: PushOptions?,
        listener: PushResultListener<RawPushRequest>,
    ) {
        executeRequest(req, pushOptions, listener, sendRawRequestHeaders)
    }

    private inline fun <reified T : SendRequest> executeRequest(
        req: T,
        pushOptions: PushOptions? = null,
        listener: PushResultListener<T>,
        headers: RequestHeaders,
    ) {
        try {
            val headersBuilder = headers.toBuilder()
            RemoteRetryOptionsSerDes.serialize(pushOptions?.remoteRetryOptions, headersBuilder::add)
            if (pushOptions?.remoteTotalTimeoutMillis != null && pushOptions.remoteTotalTimeoutMillis!! > 0L) {
                headersBuilder.add(
                    PushsphereHeaderNames.REMOTE_RESPONSE_TIMEOUT,
                    pushOptions.remoteTotalTimeoutMillis.toString(),
                )
            }
            val requestHeaders = headersBuilder.build()
            val request =
                HttpRequest.of(
                    requestHeaders,
                    HttpData.ofUtf8(Json.encodeToString(req)),
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
                                if (peeled is UnprocessedRequestException) PushResultSource.CLIENT else PushResultSource.SERVER,
                                peeled.message,
                                peeled,
                                httpStatus = res.status().code(),
                            )
                        } else {
                            try {
                                internalJson.decodeFromString(res.contentUtf8())
                            } catch (e: Throwable) {
                                PushResult(
                                    PushStatus.INVALID_SERVER_RESPONSE,
                                    PushResultSource.SERVER,
                                    e.message,
                                    e,
                                    httpStatus = res.status().code(),
                                )
                            }
                        }

                    PushClient.invokeHandleResult(ctx, listener, result, req, endpointUri())
                }
        } catch (t: Throwable) {
            PushClient.invokeHandleResult(
                null,
                listener,
                PushResult(
                    PushStatus.INTERNAL_ERROR,
                    PushResultSource.CLIENT,
                    t.message,
                    t,
                    httpStatus = PushStatus.INTERNAL_ERROR.httpStatus(),
                ),
                req,
                endpointUri(),
            )
        }
    }

    companion object {
        private val internalJson = Json { ignoreUnknownKeys = true }
    }
}
