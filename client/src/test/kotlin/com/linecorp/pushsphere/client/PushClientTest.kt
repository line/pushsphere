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
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.pushsphere.common.Push
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.exception.TooLargePayloadException
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.stream.Stream

class PushClientTest {
    @ParameterizedTest(name = "provider: {0}")
    @MethodSource("successPayloadsProvider")
    fun `verifyPayloadSize should not throw exception when payload length is less than or equal to limit`(
        provider: PushProvider,
        payloads: Array<String>,
    ) {
        payloads.forAll { assertDoesNotThrow { PushClient.verifyPayloadSize(provider, it) } }
    }

    @ParameterizedTest(name = "provider: {0}")
    @MethodSource("failPayloadsProvider")
    fun `verifyPayloadSize should throw exception when payload length is larger than limit`(
        provider: PushProvider,
        payloads: Array<String>,
    ) {
        payloads.forAll {
            assertThrows<TooLargePayloadException> { PushClient.verifyPayloadSize(provider, it) }
        }
    }

    @Test
    fun `verifyJsonString should not throw exception when payload is json format`() {
        assertDoesNotThrow {
            PushClient.verifyJsonString(
                """
                {
                    "outer": "value",
                    "nested": {
                        "inner": 10
                    }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `verifyJsonString should throw exception when payload is not json format`() {
        assertThrows<IllegalArgumentException> {
            PushClient.verifyJsonString(
                """
                {
                    "outer": "value",
                    malformed
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `invokeHandlerResult should push the given Armeria context`() {
        val ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"))
        val req = PushRequest(PushProvider.APPLE, "deviceToken", Push.forApple("title", "body"))
        val callerThread = Thread.currentThread()
        var calleeThread: Thread? = null

        ClientRequestContext.currentOrNull() shouldBe null

        PushClient.invokeHandleResult(
            ctx,
            { _, _, _ ->
                calleeThread = Thread.currentThread()
                ClientRequestContext.current() shouldBe ctx
            },
            PushResult(PushStatus.SUCCESS, PushResultSource.PUSH_PROVIDER),
            req,
            URI.create("https://example.com/"),
        )

        calleeThread shouldBe callerThread
        ClientRequestContext.currentOrNull() shouldBe null
    }

    companion object {
        // 8 bytes
        private const val PAYLOAD_BASE = "t\\/?\u0056\u3131"

        private val PAYLOAD_SIZE_25 =
            """
            {"message":"hello world"}
            """.trimIndent()

        private val PAYLOAD_SIZE_3999 =
            """
            {"message":"a${PAYLOAD_BASE.repeat(498)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_4000 =
            """
            {"message":"aa${PAYLOAD_BASE.repeat(498)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_4001 =
            """
            {"message":"aaa${PAYLOAD_BASE.repeat(498)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_4095 =
            """
            {"message":"a${PAYLOAD_BASE.repeat(510)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_4096 =
            """
            {"message":"aa${PAYLOAD_BASE.repeat(510)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_4097 =
            """
            {"message":"aaa${PAYLOAD_BASE.repeat(510)}"}
            """.trimIndent()

        private val PAYLOAD_SIZE_5000 =
            """
            {"message":"aa${PAYLOAD_BASE.repeat(623)}"}
            """.trimIndent()

        @JvmStatic
        fun successPayloadsProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    PushProvider.APPLE,
                    arrayOf(
                        PAYLOAD_SIZE_25,
                        PAYLOAD_SIZE_3999,
                        PAYLOAD_SIZE_4000,
                        PAYLOAD_SIZE_4001,
                        PAYLOAD_SIZE_4095,
                        PAYLOAD_SIZE_4096,
                    ),
                ),
                Arguments.of(
                    PushProvider.FIREBASE,
                    arrayOf(
                        PAYLOAD_SIZE_25,
                        PAYLOAD_SIZE_3999,
                        PAYLOAD_SIZE_4000,
                        PAYLOAD_SIZE_4096,
                    ),
                ),
                Arguments.of(
                    PushProvider.WEB,
                    arrayOf(
                        PAYLOAD_SIZE_25,
                        PAYLOAD_SIZE_3999,
                        PAYLOAD_SIZE_4000,
                    ),
                ),
                Arguments.of(
                    PushProvider.GENERIC,
                    arrayOf(
                        PAYLOAD_SIZE_25,
                        PAYLOAD_SIZE_3999,
                        PAYLOAD_SIZE_4000,
                        PAYLOAD_SIZE_4001,
                        PAYLOAD_SIZE_4095,
                        PAYLOAD_SIZE_4096,
                        PAYLOAD_SIZE_4097,
                        PAYLOAD_SIZE_5000,
                    ),
                ),
            )
        }

        @JvmStatic
        fun failPayloadsProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    PushProvider.APPLE,
                    arrayOf(
                        PAYLOAD_SIZE_4097,
                        PAYLOAD_SIZE_5000,
                    ),
                ),
                Arguments.of(
                    PushProvider.FIREBASE,
                    arrayOf(
                        PAYLOAD_SIZE_4097,
                        PAYLOAD_SIZE_5000,
                    ),
                ),
                Arguments.of(
                    PushProvider.WEB,
                    arrayOf(
                        PAYLOAD_SIZE_4001,
                        PAYLOAD_SIZE_4095,
                        PAYLOAD_SIZE_4096,
                        PAYLOAD_SIZE_4097,
                        PAYLOAD_SIZE_5000,
                    ),
                ),
                Arguments.of(
                    PushProvider.GENERIC,
                    emptyArray<String>(),
                ),
            )
        }
    }
}
