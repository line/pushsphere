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
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerPort
import com.linecorp.pushsphere.client.OutlierDetectingEndpointGroupIntegrationTest.Companion.logger
import com.linecorp.pushsphere.common.CircuitBreakerOptions
import com.linecorp.pushsphere.common.EndpointGroupOptions
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.Push
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.SelectionStrategy
import com.linecorp.pushsphere.common.URI
import com.linecorp.pushsphere.common.credentials.GoogleServiceAccountCredentials
import com.linecorp.pushsphere.common.credentials.ServiceAccount
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.ints.beGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class

OutlierDetectingEndpointGroupIntegrationTest {
    companion object {
        val logger = KotlinLogging.logger {}
    }

    private class IntegrationTestArgumentsProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
            return Stream.of(
                Arguments.of(PushProvider.APPLE, 20, 10),
                Arguments.of(PushProvider.APPLE, 4, 10),
                Arguments.of(PushProvider.FIREBASE, 20, 10),
                Arguments.of(PushProvider.FIREBASE, 4, 10),
            )
        }
    }

    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    @ParameterizedTest
    @ArgumentsSource(IntegrationTestArgumentsProvider::class)
    fun `should open and close circuit breakers properly`(
        provider: PushProvider,
        numEndpoints: Int,
        maxNumEndpoints: Int,
    ) = runTest(timeout = 1.minutes) {
        numEndpoints shouldBeGreaterThan 2

        val waitTolerance = if (System.getenv("CI") != null) 5.seconds else 1.seconds
        // Prepare servers
        val badEndpoints = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        val requestCounter = ConcurrentHashMap<Int, Int>()
        val servers =
            (1..numEndpoints).map { i ->
                val server =
                    Server.builder()
                        .http(0)
                        .service(Route.ofCatchAll()) { _, _ ->
                            requestCounter.compute(i) { _, v -> v?.plus(1) ?: 1 }
                            val status =
                                if (badEndpoints.contains(i)) {
                                    HttpStatus.INTERNAL_SERVER_ERROR
                                } else {
                                    HttpStatus.OK
                                }
                            HttpResponse.builder()
                                .status(status)
                                .apply {
                                    when (provider) {
                                        PushProvider.APPLE -> {
                                            header("apns-id", "1")
                                        }

                                        PushProvider.FIREBASE -> {
                                            val content =
                                                if (badEndpoints.contains(i)) {
                                                    """
{
    "error": {
        "code": 500,
        "message": "We did not expect a spanish inquisition",
        "status": "SPANISH_INQUISITION",
        "details": [
            {
                "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                "errorCode": "SPANISH_INQUISITION"
            }
        ]
    }
}
"""
                                                } else {
                                                    """
    {
        "name": "mockMessageId"
    }
"""
                                                }
                                            header("Content-Type", "application/json")
                                            content(content)
                                        }

                                        else -> throw IllegalArgumentException("Invalid provider")
                                    }
                                }
                                .build()
                        }
                        .service("/token") { _, _ ->
                            HttpResponse.builder()
                                .status(HttpStatus.OK)
                                .apply {
                                    if (provider == PushProvider.FIREBASE) {
                                        content(
                                            MediaType.JSON,
                                            OutlierDetectingEndpointGroupIntegrationTest::class.java.getResource(
                                                "/pushsphere-test-oauth-response.json",
                                            )!!.readText(),
                                        )
                                    }
                                }
                                .build()
                        }
                        .build()
                server.start().join()
                server
            }

        // Prepare a client
        val baseEndpointGroup =
            RollingUpdatableEndpointGroup(
                servers.map { it.activePort()!! },
                maxNumEndpoints,
            )

        val circuitBreakerOptions =
            CircuitBreakerOptions(
                // Make circuit breaker open immediately
                minimumRequestThreshold = 1,
                counterUpdateIntervalMillis = 1,
                circuitOpenWindowMillis = 15.seconds.inWholeMilliseconds,
            )
        val endpointGroupOptions =
            EndpointGroupOptions(
                maxNumEndpoints = maxNumEndpoints,
                maxEndpointAgeMillis = 5.seconds.inWholeMilliseconds,
                selectionStrategy = SelectionStrategy.ROUND_ROBIN,
            )
        val pushClient =
            when (provider) {
                PushProvider.APPLE -> {
                    val profile =
                        Profile.forApple(
                            endpointUri = URI.create("http://localhost"),
                            accessToken = "access-token",
                            bundleId = "bundle-id",
                            endpointGroupOptions = endpointGroupOptions,
                            circuitBreakerOptions = circuitBreakerOptions,
                        )
                    ApplePushClient(
                        profile = profile,
                        meterIdPrefix = null,
                        meterRegistry = null,
                        decoration = ClientDecoration.of(),
                        baseEndpointGroup = baseEndpointGroup,
                    )
                }

                PushProvider.FIREBASE -> {
                    val serviceAccountFile: InputStream =
                        OutlierDetectingEndpointGroupIntegrationTest::class.java.getResourceAsStream(
                            "/pushsphere-test-service-account.json",
                        )!!
                    val serviceAccount: ServiceAccount =
                        Json.decodeFromString<ServiceAccount>(String(serviceAccountFile.readBytes()))
                            .copy(tokenUri = "http://localhost:${servers.first().activeLocalPort()}/token")
                    val credentials = GoogleServiceAccountCredentials(serviceAccount)
                    val profile =
                        Profile.forFirebase(
                            endpointUri = URI.create("http://localhost"),
                            credentials = credentials,
                            endpointGroupOptions = endpointGroupOptions,
                            circuitBreakerOptions = circuitBreakerOptions,
                        )
                    FirebasePushClient(
                        profile = profile,
                        meterIdPrefix = null,
                        meterRegistry = null,
                        decoration = ClientDecoration.of(),
                        baseEndpointGroup = baseEndpointGroup,
                    )
                }

                else -> throw IllegalArgumentException("Invalid provider")
            }

        baseEndpointGroup.update(0)

        // Send requests to healthy servers
        val req =
            when (provider) {
                PushProvider.APPLE -> {
                    PushRequest(PushProvider.APPLE, "token", Push.forApple("title", "body"))
                }

                PushProvider.FIREBASE -> {
                    PushRequest(PushProvider.FIREBASE, "JohnCenaToken", Push.forFirebase("title", "body"))
                }

                else -> throw IllegalArgumentException("Invalid provider")
            }
        repeat(maxNumEndpoints) {
            val result = pushClient.send(req)
            result.status shouldBe PushStatus.SUCCESS
        }

        val targetRange =
            if (numEndpoints < maxNumEndpoints) {
                numEndpoints
            } else {
                maxNumEndpoints
            }

        (1..targetRange).forEach {
            requestCounter[it] shouldNotBe null
        }

        // All requests are evenly distributed to target servers
        (requestCounter.values.maxOrNull()!! - requestCounter.values.minOrNull()!!) shouldBeInRange (0..1)

        requestCounter.clear()

        // Make server 1 and 2 bad
        badEndpoints.add(1)
        badEndpoints.add(2)

        val requestCount = 5 * maxNumEndpoints

        // Wait until circuit breakers for 1 and 2 are open
        repeat(requestCount) {
            pushClient.send(req)
        }
        requestCounter.clear()

        repeat(requestCount) {
            pushClient.send(req)
        }
        // Make sure circuit breakers for 1 and 2 are open and other healthy endpoints handle all requests
        requestCounter[1] shouldBe null
        requestCounter[2] shouldBe null

        val leastRequestCount =
            if (numEndpoints < maxNumEndpoints) {
                requestCount / (numEndpoints - 2)
            } else {
                5 // == requestCount / maxNumEndpoints
            }

        for (i in 3..targetRange) {
            requestCounter[i] shouldBe beGreaterThanOrEqualTo(leastRequestCount)
        }
        (requestCounter.values.maxOrNull()!! - requestCounter.values.minOrNull()!!) shouldBeInRange (0..1)
        requestCounter.values.sum() shouldBe requestCount

        requestCounter.clear()

        // Update new endpoints and take new endpoints into the count, if possible
        baseEndpointGroup.update(1)
        repeat(requestCount) {
            pushClient.send(req)
        }

        requestCounter[1] shouldBe null
        requestCounter[2] shouldBe null

        val newRange =
            if (numEndpoints > maxNumEndpoints) {
                maxNumEndpoints + min(numEndpoints - maxNumEndpoints, 2)
            } else {
                numEndpoints
            }

        for (i in 3..newRange) {
            requestCounter[i] shouldBe beGreaterThanOrEqualTo(leastRequestCount)
        }
        (requestCounter.values.maxOrNull()!! - requestCounter.values.minOrNull()!!) shouldBeInRange (0..1)

        requestCounter.clear()

        baseEndpointGroup.update(0)
        // Wait until the old endpoints are removed
        Thread.sleep(endpointGroupOptions.maxEndpointAgeMillis + waitTolerance.inWholeMilliseconds)

        repeat(requestCount) {
            pushClient.send(req)
        }
        // The circuit breakers for 1 and 2 should be open for the circuit open millis.
        requestCounter[1] shouldBe null
        requestCounter[2] shouldBe null
        for (i in 3..targetRange) {
            requestCounter[i] shouldBe beGreaterThanOrEqualTo(leastRequestCount)
        }
        (requestCounter.values.maxOrNull()!! - requestCounter.values.minOrNull()!!) shouldBeInRange (0..1)
        requestCounter.values.sum() shouldBe requestCount

        requestCounter.clear()

        badEndpoints.clear()
        // Wait until the bad endpoints are removed and the circuit breakers are closed
        Thread.sleep(circuitBreakerOptions.circuitOpenWindowMillis + waitTolerance.inWholeMilliseconds)
        repeat(requestCount) {
            pushClient.send(req)
        }

        // Bad endpoints become healthy and all requests are evenly distributed to all servers
        (1..targetRange).forEach {
            requestCounter[it] shouldNotBe null
        }
        (requestCounter.values.maxOrNull()!! - requestCounter.values.minOrNull()!!) shouldBeInRange (0..1)

        servers.forEach(Server::closeAsync)
    }
}

private class RollingUpdatableEndpointGroup(
    private val ports: List<ServerPort>,
    private val maxNumEndpoints: Int,
) : DynamicEndpointGroup(EndpointSelectionStrategy.roundRobin()) {
    fun update(index: Int) {
        val endpoints =
            ports.drop(index * maxNumEndpoints)
                .take(maxNumEndpoints)
                .mapIndexed { n, port ->
                    val hostIndex = (index * maxNumEndpoints + n + 1).toString().padStart(2, '0')
                    val hostname = "foo$hostIndex.com"
                    Endpoint.of(port.localAddress()).withHost(hostname)
                }
        logger.info { "Update endpoints: $endpoints" }
        setEndpoints(endpoints)
    }
}
