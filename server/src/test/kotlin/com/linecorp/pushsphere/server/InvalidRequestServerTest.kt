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
package com.linecorp.pushsphere.server

import com.linecorp.armeria.client.BlockingWebClient
import com.linecorp.armeria.common.Flags
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.internal.testing.TestClientProfiles
import com.linecorp.pushsphere.junit5.server.PushServerExtension
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InvalidRequestServerTest {
    private val profile = TestClientProfiles.load(server.configFile)

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathProvider")
    fun shouldReturnUnknownPushStatusOnLargePayload(path: String) {
        val client = BlockingWebClient.of(server.httpUri())
        val response =
            client.prepare()
                .post(path)
                .content(MediaType.JSON, "aa".repeat((Flags.defaultMaxRequestLength() + 1).toInt()))
                .header("Authorization", "${profile.authScheme} ${profile.accessToken}")
                .execute()
        val pushResult = Json.decodeFromString<PushResult>(response.contentUtf8())
        pushResult.status shouldBe PushStatus.TOO_LARGE_PAYLOAD
        response.status() shouldBe HttpStatus.REQUEST_ENTITY_TOO_LARGE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathProvider")
    fun shouldReturnInvalidRequestOnBadInput(path: String) {
        val client = BlockingWebClient.of(server.httpUri())
        val response =
            client.prepare()
                .post(path)
                .content(MediaType.JSON, "aa")
                .header("Authorization", "${profile.authScheme} ${profile.accessToken}")
                .execute()
        val pushResult = Json.decodeFromString<PushResult>(response.contentUtf8())
        pushResult.status shouldBe PushStatus.INVALID_REQUEST
        response.status() shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `should return InvalidRequest on empty deviceToken on sendraw`() {
        val client = BlockingWebClient.of(server.httpUri())

        // RawPushRequest.deviceToken is empty
        val response =
            client.prepare()
                .post("/api/v1/talk/main/send/raw")
                .content(
                    MediaType.JSON,
                    """
                    {
                        "provider":"APPLE",
                        "rawPush":{
                            "deviceToken":"", 
                            "content": "{
                                \"aps\": {
                                    \"alert\": {
                                        \"title\": \"a\",
                                        \"body\": \"b\"
                                    }
                                }
                            }"
                        }
                    }
                    """.trimIndent(),
                )
                .header("Authorization", "${profile.authScheme} ${profile.accessToken}")
                .execute()
        val pushResult = Json.decodeFromString<PushResult>(response.contentUtf8())
        pushResult.status shouldBe PushStatus.INVALID_REQUEST
        response.status() shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `should return InvalidRequest on empty deviceToken on send`() {
        val client = BlockingWebClient.of(server.httpUri())

        // RawPushRequest.deviceToken is empty
        val response =
            client.prepare()
                .post("/api/v1/talk/main/send")
                .content(
                    MediaType.JSON,
                    """
                    {
                        "provider": "APPLE",
                        "deviceToken": "",
                        "push": {
                          "title": "c",
                          "body": "d"
                        }
                    }
                    """.trimIndent(),
                )
                .header("Authorization", "${profile.authScheme} ${profile.accessToken}")
                .execute()
        val pushResult = Json.decodeFromString<PushResult>(response.contentUtf8())
        pushResult.status shouldBe PushStatus.INVALID_REQUEST
        response.status() shouldBe HttpStatus.BAD_REQUEST
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = PushServerExtension("classpath:pushsphere-test.conf")

        @JvmStatic
        fun pathProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("/api/v1/talk/main/send"),
                Arguments.of("/api/v1/talk/main/send/raw"),
            )
        }
    }
}
