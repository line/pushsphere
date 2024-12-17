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
import com.linecorp.armeria.client.logging.LoggingClient
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.Push
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RawPush
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.common.URI
import com.linecorp.pushsphere.internal.testing.TestClientProfiles
import com.linecorp.pushsphere.junit5.server.PushServerExtension
import io.kotest.common.runBlocking
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

// FIXME(trustin): Do not call the real APNs servers.
class PushspherePushClientTest {
    @Test
    fun `should apply decorators to PushspherePushClient`() {
        // Make sure the Kotlin type is compatible with the Java type and vise versa.
        @Suppress("UNUSED_VARIABLE")
        val client =
            PushspherePushClient(
                Profile.forPushsphere(
                    URI.create("https://example.com"),
                    "bearer",
                    "test-token",
                    "test-group",
                    "test-set",
                ),
                null,
                null,
                ClientDecoration
                    .builder()
                    .add(LoggingClient.newDecorator())
                    .add { delegate, ctx, req -> delegate.execute(ctx, req) }
                    .build(),
            )
    }

    @Test
    @Disabled("Can't test this without the real APNs configuration")
    fun shouldReturnOK_send() {
        val client = pushServer.client()
        val deviceToken = TestClientProfiles.load(pushServer.configFile).deviceToken
        runBlocking {
            val pushResult =
                client.send(
                    PushRequest(
                        PushProvider.APPLE,
                        deviceToken,
                        simplePushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.SUCCESS
            pushResult.resultSource shouldBe PushResultSource.PUSH_PROVIDER
        }
    }

    @Test
    @Disabled("Can't test this without the real APNs configuration")
    fun shouldReturnOK_sendRaw() {
        val client = pushServer.client()
        val deviceToken = TestClientProfiles.load(pushServer.configFile).deviceToken
        runBlocking {
            val pushResult =
                client.sendRaw(
                    RawPushRequest(
                        PushProvider.APPLE,
                        deviceToken,
                        simpleRawPush,
                    ),
                )
            pushResult.status shouldBe PushStatus.SUCCESS
            pushResult.resultSource shouldBe PushResultSource.PUSH_PROVIDER
        }
    }

    @Test
    @Disabled("Can't test this without the real APNs configuration")
    fun shouldReturn400ForInvalidDeviceToken_send() {
        val client = pushServer.client()
        runBlocking {
            val pushResult =
                client.send(
                    PushRequest(
                        PushProvider.APPLE,
                        BAD_DEVICE_TOKEN,
                        simplePushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.INVALID_REQUEST
            pushResult.resultSource shouldBe PushResultSource.PUSH_PROVIDER
        }
    }

    @Test
    @Disabled("Can't test this without the real APNs configuration")
    fun shouldReturn400ForInvalidDeviceToken_sendRaw() {
        val client = pushServer.client()
        runBlocking {
            val pushResult =
                client.sendRaw(
                    RawPushRequest(
                        PushProvider.APPLE,
                        BAD_DEVICE_TOKEN,
                        simpleRawPush,
                    ),
                )
            pushResult.status shouldBe PushStatus.INVALID_REQUEST
            pushResult.resultSource shouldBe PushResultSource.PUSH_PROVIDER
        }
    }

    @Test
    fun shouldReturn401ForInvalidAccessToken_send() {
        val client = pushServer.client(badProfile)
        runBlocking {
            val pushResult =
                client.send(
                    PushRequest(
                        PushProvider.APPLE,
                        BAD_DEVICE_TOKEN,
                        simplePushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.AUTH_FAILURE
            pushResult.resultSource shouldBe PushResultSource.SERVER
        }
    }

    @Test
    fun shouldReturn401ForInvalidAccessToken_sendRaw() {
        val client = pushServer.client(badProfile)
        runBlocking {
            val pushResult =
                client.sendRaw(
                    RawPushRequest(
                        PushProvider.APPLE,
                        BAD_DEVICE_TOKEN,
                        simpleRawPush,
                    ),
                )
            pushResult.status shouldBe PushStatus.AUTH_FAILURE
            pushResult.resultSource shouldBe PushResultSource.SERVER
        }
    }

    @Test
    fun shouldReturn400ForTooLargePayload_send() {
        val client = pushServer.client()
        val deviceToken = TestClientProfiles.load(pushServer.configFile).deviceToken
        val tooLargePushForApple = Push.forApple("test title", "b".repeat(4100))

        runBlocking {
            val pushResult =
                client.send(
                    PushRequest(
                        PushProvider.APPLE,
                        deviceToken,
                        tooLargePushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.INVALID_REQUEST
            pushResult.resultSource shouldBe PushResultSource.SERVER
        }
    }

    @Test
    fun shouldReturn400ForTooLargePayload_sendRaw() {
        val client = pushServer.client()
        val deviceToken = TestClientProfiles.load(pushServer.configFile).deviceToken
        val tooLargeRawPushForApple =
            RawPush(
                headers = hashMapOf("apns-push-type" to "alert"),
                content =
                    """
                    {
                        "aps": {
                            "alert": {
                                "title": "title",
                                "body": ${"b".repeat(4100)}
                            }
                        }
                    }
                    """.trimIndent(),
            )

        runBlocking {
            val pushResult =
                client.sendRaw(
                    RawPushRequest(
                        PushProvider.APPLE,
                        deviceToken,
                        tooLargeRawPushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.INVALID_REQUEST
            pushResult.resultSource shouldBe PushResultSource.SERVER
        }
    }

    @Test
    fun shouldReturn400ForInvalidJsonString_sendRaw() {
        val client = pushServer.client()
        val deviceToken = TestClientProfiles.load(pushServer.configFile).deviceToken
        val tooLargeRawPushForApple =
            RawPush(
                headers = hashMapOf("apns-push-type" to "alert"),
                content =
                    """
                    {
                        "aps": {
                            "alert": {
                                "title": "title",
                                "body": "wrong body
                            }
                        }
                    }
                    """.trimIndent(),
            )

        runBlocking {
            val pushResult =
                client.sendRaw(
                    RawPushRequest(
                        PushProvider.APPLE,
                        deviceToken,
                        tooLargeRawPushForApple,
                    ),
                )
            pushResult.status shouldBe PushStatus.INVALID_REQUEST
            pushResult.resultSource shouldBe PushResultSource.SERVER
        }
    }

    companion object {
        private const val BAD_DEVICE_TOKEN = "bad-token"
        private val simplePushForApple = Push.forApple("test title", "body test")

        private val simpleRawPush =
            RawPush(
                headers = hashMapOf("apns-push-type" to "alert"),
                content =
                    """
                    {
                        "aps": {
                            "alert": {
                                "title": "title",
                                "body": "read this"
                            }
                        }
                    }
                    """.trimIndent(),
            )

        private val badProfile =
            Profile.forPushsphere(URI.create("http://127.0.0.1"), "bad-token", "talk", "main", "DK")

        @JvmField
        @RegisterExtension
        val pushServer =
            object : PushServerExtension("classpath:pushsphere-test.conf") {
                override fun clientDecoration(): ClientDecoration = ClientDecoration.of(LoggingClient.newDecorator())
            }
    }
}
