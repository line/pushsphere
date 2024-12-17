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
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.circuitbreaker.CircuitState
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.RequestId
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.internal.client.DefaultClientRequestContext
import com.linecorp.pushsphere.common.CircuitBreakerOptions
import com.linecorp.pushsphere.common.EndpointGroupOptions
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class OutlierDetectingEndpointGroupTest {
    @Test
    fun testWhenReady() {
        val delegate = SettableEndpointGroup()
        val endpointGroup = OutlierDetectingEndpointGroup(delegate)
        endpointGroup.whenReady().isDone shouldBe false
        delegate.setEndpoints(listOf(Endpoint.of("foo.com")))
        endpointGroup.whenReady().isDone shouldBe true
        endpointGroup.endpoints().shouldContainExactly(Endpoint.of("foo.com"))
    }

    @Test
    fun testListener() {
        val delegate = SettableEndpointGroup()
        val endpointGroup = OutlierDetectingEndpointGroup(delegate)
        val endpointsCaptor = AtomicReference<List<Endpoint>>()
        endpointGroup.addListener { endpoints -> endpointsCaptor.set(endpoints) }
        delegate.setEndpoints(listOf(Endpoint.of("foo.com")))
        endpointsCaptor.get() shouldBe listOf(Endpoint.of("foo.com"))
    }

    @Test
    fun shouldUpdateValidEndpointsWhenCircuitIsOpen() {
        val delegate = SettableEndpointGroup()
        val endpointGroup = OutlierDetectingEndpointGroup(delegate = delegate)

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        val ctx0 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
        endpointGroup.selectNow(ctx0) shouldBe endpointA

        val ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
        endpointGroup.selectNow(ctx1) shouldBe endpointB

        // Open the circuit breaker for "a.com"
        endpointGroup.endpointContext(endpointA)!!.circuitBreaker.enterState(CircuitState.OPEN)

        val ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
        // "b.com" should be selected instead of "a.com" although round-robin strategy is used because
        // the circuit breaker should be open for "a.com" and closed for "b.com"
        endpointGroup.selectNow(ctx2) shouldBe endpointB

        endpointGroup.endpointContext(endpointA) shouldBe null
    }

    @Test
    fun shouldKeepEndpointsWhenCircuitIsClosed() {
        val delegate = SettableEndpointGroup()
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                endpointGroupOptions = EndpointGroupOptions(maxNumEndpoints = 2),
            )

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        val endpointC = Endpoint.of("c.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        endpointGroup.endpoints() shouldContainExactly listOf(endpointA, endpointB)
        val ctx0 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
        endpointGroup.selectNow(ctx0) shouldBe endpointA

        val ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
        endpointGroup.selectNow(ctx1) shouldBe endpointB

        delegate.setEndpoints(listOf(endpointA, endpointC))
        // Should ignore new changes and keep the original endpoints since all circuit breakers are closed
        endpointGroup.endpoints() shouldContainExactly listOf(endpointA, endpointB)
    }

    @Test
    fun updateEndpointsOnlyWhenMaxAgeExceeds() {
        val endpointGroupOptions =
            EndpointGroupOptions(
                maxNumEndpoints = 2,
                maxEndpointAgeMillis = Duration.ofSeconds(1).toMillis(),
            )
        val delegate = SettableEndpointGroup()
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                endpointGroupOptions = endpointGroupOptions,
            )

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        val endpointC = Endpoint.of("c.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)
        delegate.setEndpoints(listOf(endpointA, endpointC))
        // Should ignore new changes and keep the original endpoints when all circuit breakers are closed
        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)

        // Wait for maxEndpointAge to exceed
        Thread.sleep(4000)
        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointC)
    }

    @Test
    fun updateBadEndpointsWhenCircuitBreakerIsOpen() {
        val delegate = SettableEndpointGroup()
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                endpointGroupOptions = EndpointGroupOptions(maxNumEndpoints = 2),
            )

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        val endpointC = Endpoint.of("c.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)
        delegate.setEndpoints(listOf(endpointA, endpointC))
        // Should ignore new changes and keep the original endpoints when all circuit breakers are closed
        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)

        endpointGroup.endpointContext(endpointA)!!.circuitBreaker.enterState(CircuitState.OPEN)
        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointB, endpointC)
    }

    @Test
    fun shouldUseBadEndpointsWhenEndpointsIsEmpty() =
        runTest {
            val delegate = SettableEndpointGroup()
            val endpointGroup = OutlierDetectingEndpointGroup(delegate = delegate)

            val endpointA = Endpoint.of("a.com")
            val endpointB = Endpoint.of("b.com")
            delegate.setEndpoints(listOf(endpointA, endpointB))

            // Open the circuit breaker for both "a.com" and "b.com"
            endpointGroup.endpointContext(endpointA)!!.circuitBreaker.enterState(CircuitState.OPEN)
            endpointGroup.endpointContext(endpointB)!!.circuitBreaker.enterState(CircuitState.OPEN)

            // Make sure that both "a.com" and "b.com" are bad endpoints
            endpointGroup.endpointContext(endpointA) shouldBe null
            endpointGroup.endpointContext(endpointB) shouldBe null

            val ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
            endpointGroup.selectNow(ctx) shouldBe null

            // Randomly select an endpoint from badEndpoints
            eventually(5.seconds) { endpointGroup.select(ctx, ctx.eventLoop()).join() shouldBe endpointA }
            eventually(5.seconds) { endpointGroup.select(ctx, ctx.eventLoop()).join() shouldBe endpointB }

            // If new endpoints are updated, the bad endpoints should not be used.
            val endpointC = Endpoint.of("c.com")
            delegate.setEndpoints(listOf(endpointC))
            endpointGroup.selectNow(ctx) shouldBe endpointC
        }

    @Test
    fun shouldRemoveBadEndpointsWhenExpired() {
        val delegate = SettableEndpointGroup()
        val circuitOpenWindowMillis: Long = 1000
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                circuitBreakerOptions = CircuitBreakerOptions(circuitOpenWindowMillis = circuitOpenWindowMillis),
            )

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        // Open the circuit breaker for "a.com"
        endpointGroup.endpointContext(endpointA)!!.circuitBreaker.enterState(CircuitState.OPEN)
        endpointGroup.endpoints() shouldContainExactly listOf(endpointB)
        // Wait for the bad endpoint to expire
        Thread.sleep(circuitOpenWindowMillis + 500)
        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)
    }

    @Test
    fun shouldExportEndpointsMetrics() {
        val delegate = SettableEndpointGroup()
        val meterRegistry = SimpleMeterRegistry()
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                endpointGroupOptions = EndpointGroupOptions(maxNumEndpoints = 2),
                meterIdPrefix = MeterIdPrefix("apple.test"),
                meterRegistry = meterRegistry,
            )

        val endpointA = Endpoint.of("a.com")
        val endpointB = Endpoint.of("b.com")
        delegate.setEndpoints(listOf(endpointA, endpointB))

        endpointGroup.endpoints() shouldContainExactlyInAnyOrder listOf(endpointA, endpointB)
        val healthy =
            meterRegistry.find("apple.test.endpoints.count")
                .tag("state", "healthy")
                .gauge()!!
        healthy.value() shouldBe 2.0

        val unhealthy =
            meterRegistry.find("apple.test.endpoints.count")
                .tag("state", "unhealthy")
                .gauge()!!
        unhealthy.value() shouldBe 0

        endpointGroup.endpointContext(endpointA)!!.circuitBreaker.enterState(CircuitState.OPEN)
        healthy.value() shouldBe 1.0
        unhealthy.value() shouldBe 1.0

        endpointGroup.endpointContext(endpointB)!!.circuitBreaker.enterState(CircuitState.OPEN)
        healthy.value() shouldBe 0
        unhealthy.value() shouldBe 2.0
    }

    @Test
    fun shouldReturnNonDuplicateEndpointsForRetry() {
        val endpointGroupOptions =
            EndpointGroupOptions(
                maxNumEndpoints = 10,
                maxEndpointAgeMillis = Duration.ofSeconds(1).toMillis(),
            )
        val delegate = SettableEndpointGroup()
        val endpointGroup =
            OutlierDetectingEndpointGroup(
                delegate = delegate,
                endpointGroupOptions = endpointGroupOptions,
            )

        val candidates = (1..20).map { Endpoint.of("%02d.com".format(it)) }
        delegate.setEndpoints(candidates)
        val endpoints = endpointGroup.endpoints()
        endpoints.shouldContainExactlyInAnyOrder(candidates.subList(0, 10))
        val parent =
            ClientRequestContext
                .builder(HttpRequest.of(HttpMethod.GET, "/"))
                .build() as DefaultClientRequestContext

        val child0 =
            parent.newDerivedContext(
                RequestId.random(),
                HttpRequest.of(HttpMethod.GET, "/"),
                null,
                null,
            ) as DefaultClientRequestContext
        parent.logBuilder().addChild(child0.log())
        child0.log().parent() shouldBe parent.log()
        endpointGroup.selectNow(child0) shouldBe endpoints[0]
        child0.init(endpoints[0])

        // Reset the counter of round-robin selector
        for (i in 1..9) {
            endpointGroup.selectNow(child0) shouldBe endpoints[i]
        }

        val child1 =
            parent.newDerivedContext(
                RequestId.random(),
                HttpRequest.of(HttpMethod.GET, "/"),
                null,
                endpoints[1],
            )
        parent.logBuilder().addChild(child1.log())
        val child2 =
            parent.newDerivedContext(
                RequestId.random(),
                HttpRequest.of(HttpMethod.GET, "/"),
                null,
                endpoints[2],
            )
        parent.logBuilder().addChild(child2.log())

        val child3 =
            parent.newDerivedContext(
                RequestId.random(),
                HttpRequest.of(HttpMethod.GET, "/"),
                null,
                null,
            )
        parent.logBuilder().addChild(child3.log())

        // Skip endpoints[0..2] because they are already selected.
        endpointGroup.selectNow(child3) shouldBe endpoints[3]
    }
}

private class SettableEndpointGroup : DynamicEndpointGroup(EndpointSelectionStrategy.roundRobin()) {
    fun setEndpoints(endpoints: List<Endpoint>) {
        super.setEndpoints(endpoints)
    }
}
