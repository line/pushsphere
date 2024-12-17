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

import com.linecorp.pushsphere.common.apns.AppleHeaders
import com.linecorp.pushsphere.common.apns.ApplePushProps
import com.linecorp.pushsphere.common.apns.ApplePushType
import com.linecorp.pushsphere.common.fcm.AndroidConfig
import com.linecorp.pushsphere.common.fcm.AndroidNotification
import com.linecorp.pushsphere.common.fcm.FirebasePushProps
import com.linecorp.pushsphere.common.web.WebPushProps
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PushTest {
    @Test
    fun `A Push without vendor-specific properties should be GENERIC`() {
        Push.forApple("foo", "bar").provider shouldBe PushProvider.GENERIC
        Push.forFirebase("foo", "bar").provider shouldBe PushProvider.GENERIC
        Push.forWeb("foo", "bar").provider shouldBe PushProvider.GENERIC
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serializationTestSource")
    fun `Serialization and deserialization`(
        name: String,
        push: Push,
        json: String,
    ) {
        toJson(push) shouldEqualJson json
        fromJson(json) shouldBe push
    }

    @Test
    fun `Should reject JSON that contains more than one vendor specific properties`() {
        shouldThrow<IllegalArgumentException> {
            fromJson(
                """
                  {
                    "title": "foo",
                    "body": "bar",
                    "appleProps": {},
                    "firebaseProps": {}
                  }
                """,
            )
        }
        shouldThrow<IllegalArgumentException> {
            fromJson(
                """
                  {
                    "title": "foo",
                    "body": "bar",
                    "appleProps": {},
                    "webProps": {}
                  }
                """,
            )
        }
        shouldThrow<IllegalArgumentException> {
            fromJson(
                """
                  {
                    "title": "foo",
                    "body": "bar",
                    "webProps": {},
                    "firebaseProps": {}
                  }
                """,
            )
        }
    }

    private fun toJson(push: Push) = Json.encodeToString(Push.serializer(), push)

    private fun fromJson(json: String) = Json.decodeFromString(Push.serializer(), json)

    companion object {
        @JvmStatic
        fun serializationTestSource(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "title and body",
                    Push.forApple("foo", "bar"),
                    """
                  {
                    "title": "foo",
                    "body": "bar"
                  }
                """,
                ),
                Arguments.of(
                    "title, body and imageUri",
                    Push.forApple("foo", "bar", URI.create("https://baz.com")),
                    """
                  {
                    "title": "foo",
                    "body": "bar",
                    "imageUri": "https://baz.com"
                  }
                """,
                ),
                Arguments.of(
                    "title, body and appleProps",
                    Push.forApple("foo", "bar", null, ApplePushProps(AppleHeaders(ApplePushType.ALERT))),
                    """
                  {
                    "title": "foo",
                    "body": "bar",
                    "appleProps": {
                        "headers": {
                          "apnsPushType": "alert"
                        }
                    }
                  }
                """,
                ),
                Arguments.of(
                    "title, body and firebaseProps",
                    Push.forFirebase(
                        "foo",
                        "bar",
                        null,
                        FirebasePushProps(android = AndroidConfig(notification = AndroidNotification(clickAction = "https://action.com"))),
                    ),
                    """
                  {
                    "title": "foo",
                    "body": "bar",
                    "firebaseProps": {
                      "android": {
                        "notification": {
                          "click_action": "https://action.com"
                        }                    
                      }
                    }
                  }
                """,
                ),
                Arguments.of(
                    "title, body and webProps",
                    Push.forWeb("foo", "bar", null, WebPushProps(URI.create("https://uri.com"))),
                    """
                {
                  "title": "foo",
                  "body": "bar",
                  "webProps": {
                    "url": "https://uri.com"
                  }
                }
                """,
                ),
            )
    }
}
