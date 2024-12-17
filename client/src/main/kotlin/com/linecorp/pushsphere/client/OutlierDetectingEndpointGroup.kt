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

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.DecoratingHttpClientFunction
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListenerAdapter
import com.linecorp.armeria.client.circuitbreaker.CircuitState
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.endpoint.EndpointSelector
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.util.AbstractListenable
import com.linecorp.pushsphere.common.CircuitBreakerOptions
import com.linecorp.pushsphere.common.EndpointGroupOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Function
import kotlin.math.max

/**
 * A [EndpointGroup] that provides a way to select an [Endpoint], especially optimized for APNS servers.
 * It periodically refreshes the endpoints and selects an endpoint based on the [CircuitBreaker] state.
 *
 * - Keep-Alive: The DNS record for APNS servers is updated every time when it is queried, so the existing
 *               endpoints are invalid and refreshed frequently. That causes the pooled connections to become
 *               unusable and forces a new connection to be created. Failure to reuse connections will have a
 *               negative impact on performance and the response duration.
 *               This [EndpointGroup] keeps the cached endpoints for [maxEndpointAge]. If an [Endpoint] exceeds
 *               its lifespan, that is automatically removed from the valid [Endpoint]s and a new [Endpoint]
 *               will be added.
 * - Circuit breaker: This [EndpointGroup] uses [CircuitBreaker] to avoid sending requests to bad [Endpoint]s.
 *                    If a request to an [Endpoint] does not receive 2xx-4xx status, the [CircuitBreaker]
 *                    increases the failure count for the [Endpoint]. If the failure count exceeds the threshold
 *                    defined in [CircuitBreakerOptions], the [Endpoint] is removed from the valid [Endpoint]s
 *                    and a new [Endpoint] will be added automatically.
 */
internal class OutlierDetectingEndpointGroup(
    private val delegate: EndpointGroup,
    private val endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
    private val circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
    meterIdPrefix: MeterIdPrefix? = null,
    meterRegistry: MeterRegistry? = null,
) : CircuitBreakerListenerAdapter(), EndpointGroup, EndpointSelector {
    private val endpointsLock: ReentrantLock = ReentrantLock()

    // Guarded by `endpointsLock`.
    @Volatile
    private var endpoints: List<Endpoint> = listOf()

    private val endpointGroupListeners: AppleEndpointGroupListener =
        AppleEndpointGroupListener { delegate.endpoints() }
    private val initialCompletionFuture: CompletableFuture<List<Endpoint>> =
        CompletableFuture<List<Endpoint>>()

    private val endpointContexts: MutableMap<Endpoint, EndpointContext> = ConcurrentHashMap()
    private val selectionStrategy: EndpointSelectionStrategy = CircuitBreakerEndpointSelectionStrategy()
    private val endpointSelector: EndpointSelector =
        CircuitBreakerEndpointSelector(selectionStrategy.newSelector(this))

    private val circuitBreakerNamePrefix: String = circuitBreakerOptions.namePrefix ?: "outlier-detecting"

    private val maxEndpointAgeNanoTime: Long =
        TimeUnit.MILLISECONDS.toNanos(endpointGroupOptions.maxEndpointAgeMillis)
    private val badEndpointExpirationMillis =
        if (circuitBreakerOptions.circuitOpenWindowMillis > 0) {
            circuitBreakerOptions.circuitOpenWindowMillis
        } else {
            10_000 // 10 seconds. The default circuitOpenWindowMillis of CircuitBreaker.
        }

    private val badEndpoints: MutableSet<Endpoint> = Collections.newSetFromMap(ConcurrentHashMap())

    private var closed: Boolean = false

    init {
        delegate.whenReady().thenAccept { _ -> scheduleEndpointUpdateTask() }
        delegate.addListener { _ ->
            endpointsLock.lock()
            try {
                if (endpointContexts.size < endpointGroupOptions.maxNumEndpoints) {
                    // Do not update the endpoints when the delegate updates its endpoints.
                    // Fill the new endpoints only when the number of endpoints is less than the max.
                    // The old endpoints or bad endpoints are refreshed by the scheduled task or
                    // CircuitBreakerListener.
                    refreshEndpoints(false)
                }
            } finally {
                endpointsLock.unlock()
            }
        }

        if (meterIdPrefix != null && meterRegistry != null) {
            val name = meterIdPrefix.name("endpoints.count")
            Gauge.builder(name, this) { obj -> obj.endpoints().size.toDouble() }
                .tags(meterIdPrefix.tags())
                .tag("state", "healthy")
                .description("The number of healthy APNS endpoints")
                .register(meterRegistry)

            Gauge.builder(name, badEndpoints) { obj -> obj.size.toDouble() }
                .tags(meterIdPrefix.tags())
                .tag("state", "unhealthy")
                .description("The number of unhealthy APNS endpoints")
                .register(meterRegistry)
        }
    }

    /**
     * Schedules a task to update the old endpoints periodically.
     */
    private fun scheduleEndpointUpdateTask() {
        if (closed) {
            return
        }

        val nextDurationMillis = refreshEndpoints(true)
        executor.schedule(::scheduleEndpointUpdateTask, nextDurationMillis, TimeUnit.MILLISECONDS)
    }

    private fun newExpirationNanoTime(currentNanoTime: Long): Long {
        return currentNanoTime + maxEndpointAgeNanoTime +
            // Use 20% of max age as jitter to avoid thundering herd problem.
            ThreadLocalRandom.current().nextLong(maxEndpointAgeNanoTime / 5)
    }

    // Visible for testing.
    internal fun endpointContext(endpoint: Endpoint): EndpointContext? {
        return endpointContexts[endpoint]
    }

    private fun setEndpoints(endpoints: List<Endpoint>) {
        this.endpoints = endpoints
        endpointGroupListeners.notifyListeners0(endpoints)
        if (!initialCompletionFuture.isDone) {
            initialCompletionFuture.complete(endpoints)
        }
    }

    private fun newCircuitBreaker(endpoint: Endpoint): CircuitBreaker {
        val name = "$circuitBreakerNamePrefix-${circuitBreakerCounter.incrementAndGet()}:$endpoint"
        return CircuitBreaker.builder(name)
            .apply {
                if (circuitBreakerOptions.failureRateThreshold >= 0.0) {
                    failureRateThreshold(circuitBreakerOptions.failureRateThreshold)
                }
                if (circuitBreakerOptions.minimumRequestThreshold >= 0) {
                    minimumRequestThreshold(circuitBreakerOptions.minimumRequestThreshold)
                }
                if (circuitBreakerOptions.trialRequestIntervalMillis > 0) {
                    trialRequestIntervalMillis(circuitBreakerOptions.trialRequestIntervalMillis)
                }
                if (circuitBreakerOptions.circuitOpenWindowMillis > 0) {
                    circuitOpenWindowMillis(circuitBreakerOptions.circuitOpenWindowMillis)
                }
                if (circuitBreakerOptions.counterSlidingWindowMillis > 0) {
                    counterSlidingWindowMillis(circuitBreakerOptions.counterSlidingWindowMillis)
                }
                if (circuitBreakerOptions.counterUpdateIntervalMillis > 0) {
                    counterUpdateIntervalMillis(circuitBreakerOptions.counterUpdateIntervalMillis)
                }
                listener(this@OutlierDetectingEndpointGroup)
            }
            .build()
    }

    /**
     * Invoked when the [CircuitState] of a [CircuitBreaker] is changed.
     */
    override fun onStateChanged(
        circuitBreakerName: String,
        state: CircuitState,
    ) {
        endpointsLock.lock()
        try {
            val needsUpdate =
                endpointContexts.any { (_, context) ->
                    context.circuitBreaker.name() == circuitBreakerName
                }
            if (needsUpdate) {
                refreshEndpoints(false)
            }
        } finally {
            endpointsLock.unlock()
        }
    }

    /**
     * Removes bad endpoints from the valid endpoints and adds new endpoints.
     */
    private fun refreshEndpoints(isScheduledJob: Boolean): Long {
        endpointsLock.lock()
        try {
            // Remove bad endpoints.
            val newBadEndpoints =
                endpointContexts.filter { (_, context) ->
                    context.circuitBreaker.circuitState() != CircuitState.CLOSED
                }.keys

            if (newBadEndpoints.isNotEmpty()) {
                for (badEndpoint in newBadEndpoints) {
                    badEndpoints.add(badEndpoint)
                    endpointContexts.remove(badEndpoint)
                }

                // Schedule a task to remove the bad endpoint from the badEndpoints.
                executor.schedule({
                    badEndpoints.removeAll(newBadEndpoints)
                    // Bad endpoints are removed. Make the endpoint available if there are not enough endpoints.
                    refreshEndpoints(false)
                }, badEndpointExpirationMillis, TimeUnit.MILLISECONDS)
            }

            // Remove old endpoints.
            val currentNanoTime = System.nanoTime()
            val oldEndpointContexts =
                endpointContexts.filter { (_, context) ->
                    currentNanoTime - context.expirationNanoTime >= 0
                }
            for (oldEndpoint in oldEndpointContexts.keys) {
                endpointContexts.remove(oldEndpoint)
            }

            // Fetch new endpoints.
            val candidates = delegate.endpoints()
            val newEndpoints: List<Endpoint> =
                candidates
                    // Exclude the existing endpoints.
                    .filterNot(endpointContexts::contains)
                    // Exclude the old endpoints.
                    .filterNot(oldEndpointContexts::contains)
                    // Exclude the bad endpoints.
                    .filterNot(badEndpoints::contains)
                    .take(endpointGroupOptions.maxNumEndpoints - endpointContexts.size)
            for (endpoint in newEndpoints) {
                val expirationNanoTime = newExpirationNanoTime(currentNanoTime)
                endpointContexts[endpoint] =
                    EndpointContext(endpoint, newCircuitBreaker(endpoint), expirationNanoTime)
            }

            // Reuse the old endpoints if there are not enough new endpoints.
            val remaining = endpointGroupOptions.maxNumEndpoints - endpointContexts.size
            if (remaining > 0) {
                val duplicateEndpoints =
                    oldEndpointContexts.filter { (endpoint, _) ->
                        candidates.contains(endpoint)
                    }
                for ((endpoint, context) in duplicateEndpoints) {
                    val expirationNanoTime = newExpirationNanoTime(currentNanoTime)
                    // Extend the expiration time for the existing endpoint to keep CircuitBeaker's state.
                    endpointContexts[endpoint] = context.copy(expirationNanoTime = expirationNanoTime)
                }
            }

            setEndpoints(endpointContexts.keys.toList())

            return if (isScheduledJob) {
                // Compute the next update interval.
                val minExpirationNanoTime =
                    endpointContexts.minOfOrNull { (_, context) -> context.expirationNanoTime - currentNanoTime }

                if (minExpirationNanoTime == null) {
                    // No endpoints. Retry after 100 ms to quickly fetch the next endpoints.
                    100
                } else {
                    // Clamp the min interval to 500 ms to avoid too frequent updates.
                    max(TimeUnit.NANOSECONDS.toMillis(minExpirationNanoTime), 500)
                }
            } else {
                0
            }
        } catch (e: Throwable) {
            logger.error(e) { "Unexpected exception while updating endpoints." }
            return 500
        } finally {
            endpointsLock.unlock()
        }
    }

    override fun selectNow(ctx: ClientRequestContext): Endpoint {
        // TODO(ikhoon): Add @Nullable annotation to `EndpointGroup.selectNow()`.
        //               @Nullable defined in the super method is not visible in Kotlin.
        //               https://github.com/line/armeria/issues/5184
        return unsafeCast(endpointSelector.selectNow(ctx))
    }

    private fun <T> unsafeCast(value: T?): T = Function.identity<T?>().apply(value)

    @Deprecated("Deprecated in Java")
    override fun select(
        ctx: ClientRequestContext,
        executor: ScheduledExecutorService,
        timeoutMillis: Long,
    ): CompletableFuture<Endpoint> = select(ctx, executor)

    override fun select(
        ctx: ClientRequestContext,
        executor: ScheduledExecutorService,
    ): CompletableFuture<Endpoint> = endpointSelector.select(ctx, executor)

    /**
     * Reports the result of the request to the [CircuitBreaker].
     * If response status is 2xx-4xx, the [CircuitBreaker] increases the success count.
     * Otherwise, it increases the failure count.
     */
    private fun reportResult(ctx: ClientRequestContext) {
        val endpointContext = endpointContexts[ctx.endpoint()]
        if (endpointContext != null) {
            val circuitBreaker = endpointContext.circuitBreaker
            ctx.log().whenComplete().thenAccept { log ->
                val code = log.responseStatus().code()
                if (code in 200..499) {
                    circuitBreaker.onSuccess()
                } else {
                    circuitBreaker.onFailure()
                }
            }
        }
    }

    /**
     * Returns a [DecoratingHttpClientFunction] that reports the result of the request to the [CircuitBreaker].
     */
    fun asHttpDecorator(): DecoratingHttpClientFunction {
        return DecoratingHttpClientFunction { delegate, ctx, req ->
            reportResult(ctx)
            delegate.execute(ctx, req)
        }
    }

    override fun close() {
        closed = true
        delegate.close()
    }

    override fun closeAsync(): CompletableFuture<*> {
        closed = true
        return delegate.closeAsync()
    }

    override fun endpoints(): List<Endpoint> {
        return endpoints
    }

    override fun selectionStrategy(): EndpointSelectionStrategy {
        return selectionStrategy
    }

    override fun selectionTimeoutMillis(): Long {
        return delegate.selectionTimeoutMillis()
    }

    override fun whenReady(): CompletableFuture<List<Endpoint>> {
        return initialCompletionFuture
    }

    override fun addListener(
        listener: Consumer<in List<Endpoint>>,
        notifyLatestEndpoints: Boolean,
    ) {
        endpointGroupListeners.addListener(listener, notifyLatestEndpoints)
    }

    override fun removeListener(listener: Consumer<*>) {
        endpointGroupListeners.removeListener(listener)
    }

    private inner class AppleEndpointGroupListener(private val latest: () -> List<Endpoint>) :
        AbstractListenable<List<Endpoint>>() {
        override fun latestValue(): List<Endpoint>? {
            val latest0 = latest()
            return latest0.ifEmpty { null }
        }

        fun notifyListeners0(latestValue: List<Endpoint>) {
            super.notifyListeners(latestValue)
        }
    }

    companion object {
        /**
         * Used to schedule a task to update the endpoints and remove bad endpoints.
         */
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

        /**
         * Used to generate a unique name for each [CircuitBreaker].
         */
        private val circuitBreakerCounter: AtomicLong = AtomicLong()

        private val logger = KotlinLogging.logger {}
    }

    private inner class CircuitBreakerEndpointSelectionStrategy : EndpointSelectionStrategy {
        override fun newSelector(endpointGroup: EndpointGroup): EndpointSelector {
            val selector = delegate.selectionStrategy().newSelector(endpointGroup)
            return CircuitBreakerEndpointSelector(selector)
        }
    }

    private inner class CircuitBreakerEndpointSelector(private val selector: EndpointSelector) :
        EndpointSelector {
        override fun selectNow(ctx: ClientRequestContext): Endpoint? {
            var endpoint = selector.selectNow(ctx)
            if (endpoint == null) {
                // No endpoint is available.
                return null
            }
            val parent = ctx.log().parent()
            if (parent == null || parent.children().isEmpty()) {
                // No retry or the first attempt.
                return endpoint
            }

            repeat(3) {
                val duplicated =
                    parent.children().find { child ->
                        val cctx = child.context() as ClientRequestContext
                        cctx.endpoint() == endpoint
                    }
                if (duplicated == null) {
                    return endpoint
                } else {
                    // The endpoint was used in the previous attempt. Try another endpoint.
                    endpoint = selector.selectNow(ctx)
                }
            }

            // All endpoints are used to send the previous attempts. Use the endpoint anyway.
            return endpoint
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun select(
            ctx: ClientRequestContext,
            executor: ScheduledExecutorService,
            timeoutMillis: Long,
        ): CompletableFuture<Endpoint> {
            // Armeria does not use this method.
            return selector.select(ctx, executor, timeoutMillis)
        }

        override fun select(
            ctx: ClientRequestContext,
            executor: ScheduledExecutorService,
        ): CompletableFuture<Endpoint> {
            val numBadEndpoints = badEndpoints.size
            if (endpoints.isEmpty() && numBadEndpoints > 0 && !circuitBreakerOptions.failFastOnAllCircuitOpen) {
                // All endpoints are bad. It would be a network issue instead of an APNS issue. One of bad
                // endpoints is used as a fallback since it is better than failing requests until the next DNS
                // update.
                selectNowFromBadEndpoints(numBadEndpoints)?.let { return it }
            }
            return selector.select(ctx, executor)
        }

        private fun selectNowFromBadEndpoints(numBadEndpoints: Int): CompletableFuture<Endpoint>? {
            var target = ThreadLocalRandom.current().nextInt(numBadEndpoints)
            var badEndpoint: Endpoint? = null
            for (endpoint in badEndpoints) {
                if (target-- == 0) {
                    badEndpoint = endpoint
                    break
                }
            }
            if (badEndpoint != null) {
                return CompletableFuture.completedFuture(badEndpoint)
            }
            // Bad endpoints have been removed by other threads. Use the selector to get a healthy endpoint.
            return null
        }
    }
}

internal data class EndpointContext(
    val endpoint: Endpoint,
    val circuitBreaker: CircuitBreaker,
    val expirationNanoTime: Long,
)
