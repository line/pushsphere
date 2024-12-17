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
package com.linecorp.pushsphere.common

import com.linecorp.pushsphere.common.fcm.AndroidConfig
import com.linecorp.pushsphere.common.fcm.AndroidNotification
import com.linecorp.pushsphere.common.fcm.FirebasePushProps
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PushRequestTest {
    @Test
    fun `Should reject GENERIC provider`() {
        shouldThrow<IllegalArgumentException> {
            PushRequest(
                provider = PushProvider.GENERIC,
                deviceToken = "test",
                push = Push.forApple("foo", "bar"),
            )
        }.message shouldContain "expected: APPLE, FIREBASE or WEB"
    }

    @ParameterizedTest
    @CsvSource(value = ["APPLE", "FIREBASE", "WEB"])
    fun `Should accept a GENERIC push`(provider: PushProvider) {
        PushRequest(
            provider = provider,
            deviceToken = "test-token",
            push = Push.forApple("foo", "bar"),
        )
    }

    @Test
    fun `Should reject a push whose provider doesn't match`() {
        shouldThrow<IllegalArgumentException> {
            val android = AndroidConfig(notification = AndroidNotification(clickAction = "https://action.com"))
            PushRequest(
                provider = PushProvider.APPLE,
                deviceToken = "test",
                push =
                    Push.forFirebase(
                        "foo",
                        "bar",
                        null,
                        FirebasePushProps(android = android),
                    ),
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serializationTestSource")
    fun `Serialization and deserialization`(
        name: String,
        req: PushRequest,
        json: String,
    ) {
        toJson(req) shouldEqualJson json
        fromJson(json) shouldBe req
    }

    private fun toJson(req: PushRequest): String = Json.encodeToString(PushRequest.serializer(), req)

    private fun fromJson(json: String): PushRequest = Json.decodeFromString(PushRequest.serializer(), json)

    companion object {
        @JvmStatic
        fun serializationTestSource(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "essential fields",
                    PushRequest(
                        PushProvider.APPLE,
                        "b",
                        Push.forApple("c", "d"),
                    ),
                    """
                  {
                    "provider": "APPLE",
                    "deviceToken": "b",
                    "push": {
                      "title": "c",
                      "body": "d"
                    }
                  }
                """,
                ),
                Arguments.of(
                    "all fields",
                    PushRequest(
                        PushProvider.FIREBASE,
                        "f",
                        Push.forApple("g", "h"),
                        "i",
                        mapOf("j" to "k"),
                        mapOf("l" to "m"),
                    ),
                    """
                  {
                    "provider": "FIREBASE",
                    "deviceToken": "f",
                    "push": {
                      "title": "g",
                      "body": "h"
                    },
                    "idempotencyKey": "i",
                    "variables": {
                      "j": "k"
                    },
                    "appData": {
                      "l": "m"
                    }
                  }
                """,
                ),
            )
    }
}
