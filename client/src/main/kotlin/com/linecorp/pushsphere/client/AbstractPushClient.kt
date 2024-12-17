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
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.client.ConnectionPoolListener
import com.linecorp.armeria.client.DnsCache
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.RequestOptions
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.client.retry.Backoff
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.ExchangeType
import com.linecorp.armeria.common.Flags
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.TimeoutException
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.common.outlier.OutlierDetection
import com.linecorp.armeria.common.outlier.OutlierRule
import com.linecorp.armeria.internal.common.util.IpAddrUtil
import com.linecorp.pushsphere.client.retry.DynamicRetryConfigMapping
import com.linecorp.pushsphere.common.ConnectionOutlierDetectionOptions
import com.linecorp.pushsphere.common.OutlierDetectionPolicy
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.PushsphereProfile
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryRateLimitOptions
import com.linecorp.pushsphere.common.SelectionStrategy
import com.linecorp.pushsphere.common.exception.TooLargePayloadException
import com.linecorp.pushsphere.internal.common.SlidingWindowCounter
import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.AttributeMap
import java.net.InetSocketAddress
import java.net.URI
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.function.Function

internal abstract class AbstractPushClient(
    profile: Profile,
    meterIdPrefix: MeterIdPrefix?,
    meterRegistry: MeterRegistry?,
    decoration: ClientDecoration,
    certChain: Iterable<X509Certificate>,
    privateKey: PrivateKey?,
    defaultHeaders: HttpHeaders,
    selectionStrategy: SelectionStrategy = SelectionStrategy.ROUND_ROBIN,
    shouldRemoveConnectionPoolMonitoring: Boolean = false,
) : PushClient {
    private val clientFactory: ClientFactory
    private val requestCounter: SlidingWindowCounter?
    private val retryCounter: SlidingWindowCounter?

    @Volatile
    private var isInitialized: Boolean = false

    init {
        val certChainList = certChain.toList()
        require(
            certChainList.isNotEmpty() && privateKey != null ||
                certChainList.isEmpty() && privateKey == null,
        ) {
            "certChain and privateKey must be specified together."
        }

        clientFactory =
            ClientFactory
                .builder()
                .apply {
                    // Configure mTLS.
                    if (privateKey != null) {
                        tls(privateKey, certChainList)
                    }

                    // Apply the profile's network options.
                    val netOpts = profile.networkOptions
                    netOpts.maxNumEventLoops?.let {
                        maxNumEventLoopsPerEndpoint(it)
                    }

                    netOpts.idleTimeoutMillis?.let {
                        idleTimeoutMillis(it)
                    }
                    netOpts.maxConnectionAgeMillis?.let {
                        maxConnectionAgeMillis(it)
                    }
                    netOpts.connectTimeoutMillis?.let {
                        connectTimeoutMillis(it)
                    }
                    if (netOpts.tlsNoVerify == true) {
                        tlsNoVerify()
                    }

                    // Apply Connection outlier detection options.
                    val outlierDetectionOps = profile.connectionOutlierDetectionOptions
                    if (outlierDetectionOps != ConnectionOutlierDetectionOptions.EMPTY) {
                        val rule =
                            outlierDetectionOps
                                .outlierDetectionPolicies
                                .fold(OutlierRule.builder()) { builder, policy ->
                                    when (policy) {
                                        OutlierDetectionPolicy.SERVER_ERROR -> builder.onServerError()
                                        OutlierDetectionPolicy.TIMEOUT -> builder.onException(TimeoutException::class.java)
                                        OutlierDetectionPolicy.ON_EXCEPTION -> builder.onException()
                                        OutlierDetectionPolicy.ON_UNPROCESSED ->
                                            builder.onException(UnprocessedRequestException::class.java)
                                    }
                                }
                                .build()
                        val outlierDetection =
                            OutlierDetection
                                .builder(rule)
                                .apply {
                                    outlierDetectionOps.minimumRequestThreshold?.let {
                                        minimumRequestThreshold(it)
                                    }
                                    outlierDetectionOps.counterSlidingWindowMillis?.let {
                                        counterSlidingWindowMillis(it)
                                    }
                                    outlierDetectionOps.counterUpdateIntervalMillis?.let {
                                        counterUpdateIntervalMillis(it)
                                    }
                                    outlierDetectionOps.failureRateThreshold?.let {
                                        failureRateThreshold(it)
                                    }
                                }
                                .build()
                        connectionOutlierDetection(outlierDetection)
                    }

                    if (meterRegistry != null) {
                        meterRegistry(meterRegistry)
                    }

                    // Log the connection events.
                    if (meterIdPrefix != null) {
                        // Record metrics as well if meterIdPrefix was specified.
                        connectionPoolListener(
                            ConnectionPoolListenerImpl(
                                meterIdPrefix.append("connections"),
                                shouldRemoveConnectionPoolMonitoring,
                            ),
                        )
                    } else {
                        connectionPoolListener(ConnectionPoolListener.logging())
                    }
                }
                .build()

        val windowSizeNanos = profile.retryRateLimitOptions.windowSizeNanos
        if (windowSizeNanos > 0) {
            requestCounter = SlidingWindowCounter(windowSizeNanos)
            retryCounter = SlidingWindowCounter(windowSizeNanos)
        } else {
            requestCounter = null
            retryCounter = null
        }
    }

    private val webClient: WebClient by lazy {
        isInitialized = true
        val sessionProtocol = SessionProtocol.of(profile.endpointUri.scheme)
        val basePath =
            if (profile.endpointUri.rawPath.isNullOrEmpty()) {
                "/"
            } else {
                profile.endpointUri.rawPath
            }

        var endpointGroup: EndpointGroup? = null
        if (profile is PushsphereProfile && profile.endpointGroup != null) {
            endpointGroup = profile.endpointGroup
        }
        if (endpointGroup == null) {
            val targetHost = profile.endpointUri.host
            val targetPort = profile.endpointUri.port
            endpointGroup =
                if (IpAddrUtil.normalize(targetHost) != null) {
                    // The endpoint is an IP address.
                    if (targetPort != -1) {
                        Endpoint.of(targetHost, targetPort)
                    } else {
                        Endpoint.of(targetHost)
                    }
                } else {
                    DnsAddressEndpointGroup
                        .builder(targetHost)
                        .port(
                            if (targetPort != -1) {
                                targetPort
                            } else {
                                sessionProtocol.defaultPort()
                            },
                        )
                        .dnsCache(dnsCache)
                        .apply {
                            val strategy =
                                when (selectionStrategy) {
                                    SelectionStrategy.ROUND_ROBIN -> EndpointSelectionStrategy.roundRobin()
                                    SelectionStrategy.WEIGHTED_ROUND_ROBIN -> EndpointSelectionStrategy.weightedRoundRobin()
                                    SelectionStrategy.RAMPING_UP -> EndpointSelectionStrategy.rampingUp()
                                }
                            selectionStrategy(strategy)
                        }
                        .allowEmptyEndpoints(false)
                        // Quick retry for DNS network failures.
                        .backoff(Backoff.fixed(500))
                        .build()
                }
        }
        endpointGroup = endpointGroupMapper(endpointGroup)

        WebClient.builder(sessionProtocol, endpointGroup, basePath)
            .factory(clientFactory)
            .addHeaders(defaultHeaders)
            .apply {
                addHeader(HttpHeaderNames.AUTHORITY, profile.endpointUri.authority)
                // Apply the profile's network options.
                val netOpts = profile.networkOptions
                netOpts.responseTimeoutMillis?.let {
                    responseTimeoutMillis(it)
                }
                netOpts.writeTimeoutMillis?.let {
                    writeTimeoutMillis(it)
                }

                // Apply the user-supplied decorators.
                decoration.decorators().forEach { decorator(it) }
                // Apply the client-supplied decorators.
                additionalDecorators().decorators().forEach { decorator(it) }

                // Apply the retry counter.
                retryCounter?.let {
                    decorator { delegate, ctx, req ->
                        ctx.log().parent()?.children()?.size!!.let { if (it > 1) retryCounter.count() }
                        delegate.execute(ctx, req)
                    }
                }

                // Apply the retry options.
                decorator(
                    getRetryingClient(
                        profile.retryOptions,
                        profile.retryRateLimitOptions,
                        meterIdPrefix,
                        meterRegistry,
                    ),
                )

                // Record metrics if meterIdPrefix was specified.
                if (meterIdPrefix != null) {
                    decorator(
                        MetricCollectingClient.newDecorator(
                            MeterIdPrefixFunction.ofDefault(meterIdPrefix.name())
                                .withTags(meterIdPrefix.tags()),
                        ),
                    )
                }
            }
            .build()
    }

    internal fun endpointUri(): URI = webClient.uri()

    protected open fun endpointGroupMapper(endpointGroup: EndpointGroup): EndpointGroup = endpointGroup

    /**
     * Returns additional [ClientDecoration] to be applied to the [WebClient].
     */
    protected open fun additionalDecorators(): ClientDecoration {
        return ClientDecoration.of()
    }

    internal fun execute(
        req: HttpRequest,
        requestOptions: RequestOptions?,
    ): Pair<ClientRequestContext, HttpResponse> {
        Clients.newContextCaptor().use {
            requestCounter?.count()
            val res = webClient.execute(req, requestOptions ?: unaryRequestOptions)
            val ctx = it.get()
            return Pair(ctx, res)
        }
    }

    internal fun getRequestCounter(): SlidingWindowCounter? {
        return requestCounter
    }

    internal fun getRetryCounter(): SlidingWindowCounter? {
        return retryCounter
    }

    private fun getRetryingClient(
        retryOptions: RetryOptions,
        retryRateLimitOptions: RetryRateLimitOptions,
        meterIdPrefix: MeterIdPrefix?,
        meterRegistry: MeterRegistry?,
    ): Function<in HttpClient, out HttpClient> {
        return RetryingClient
            .builderWithMapping(
                DynamicRetryConfigMapping(
                    defaultRetryOptions = retryOptions,
                    retryRateLimitOptions = retryRateLimitOptions,
                    requestCounter = requestCounter,
                    retryCounter = retryCounter,
                    meterIdPrefix = meterIdPrefix,
                    meterRegistry = meterRegistry,
                ),
            )
            .newDecorator()
    }

    protected fun meterRegistry(): MeterRegistry {
        return clientFactory.meterRegistry()
    }

    final override fun close() {
        // TODO(trustin): This will close all the connections immediately.
        //                Should we wait until all requests are processed?
        if (isInitialized) {
            webClient.endpointGroup().close()
        }
        clientFactory.close()
    }

    companion object {
        // Use the shared DNS cache with a very conservative TTL range.
        private val dnsCache: DnsCache =
            DnsCache.builder()
                .ttl(1, 30)
                .negativeTtl(60)
                .build()

        internal fun pushStatusFromThrowable(t: Throwable): PushStatus =
            when (t) {
                is IllegalArgumentException, is TooLargePayloadException -> PushStatus.INVALID_REQUEST
                else -> PushStatus.INTERNAL_ERROR
            }

        private val unaryRequestOptions = RequestOptions.builder().exchangeType(ExchangeType.UNARY).build()
    }
}

internal class ConnectionPoolListenerImpl(
    meterIdPrefix: MeterIdPrefix,
    shouldRemoveConnectionPoolMonitoring: Boolean,
) : ConnectionPoolListener {
    private val loggingListener = ConnectionPoolListener.logging()
    private val metricCollectingListener =
        if (shouldRemoveConnectionPoolMonitoring) {
            null
        } else {
            ConnectionPoolListener.metricCollecting(Flags.meterRegistry(), meterIdPrefix)
        }

    override fun connectionOpen(
        protocol: SessionProtocol,
        remoteAddr: InetSocketAddress,
        localAddr: InetSocketAddress,
        attrs: AttributeMap,
    ) {
        try {
            loggingListener.connectionOpen(protocol, remoteAddr, localAddr, attrs)
        } finally {
            metricCollectingListener?.connectionOpen(protocol, remoteAddr, localAddr, attrs)
        }
    }

    override fun connectionClosed(
        protocol: SessionProtocol,
        remoteAddr: InetSocketAddress,
        localAddr: InetSocketAddress,
        attrs: AttributeMap,
    ) {
        try {
            loggingListener.connectionClosed(protocol, remoteAddr, localAddr, attrs)
        } finally {
            metricCollectingListener?.connectionClosed(protocol, remoteAddr, localAddr, attrs)
        }
    }
}
