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

import com.linecorp.pushsphere.common.fcm.FcmError
import com.linecorp.pushsphere.common.fcm.FcmErrorDetails
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PushResultTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("serializationTestSource")
    fun `Serialization and deserialization`(
        name: String,
        res: PushResult,
        json: String,
    ) {
        toJson(res) shouldEqualJson json
        fromJson(json) shouldBe res
    }

    @Test
    fun `Should not serialize an exception`() {
        toJson(
            PushResult(
                PushStatus.DEVICE_UNREGISTERED,
                PushResultSource.PUSH_PROVIDER,
                cause = RuntimeException("test"),
            ),
        ) shouldEqualJson """
          {
            "status": "DEVICE_UNREGISTERED",
            "resultSource": "PUSH_PROVIDER"
          }
        """
    }

    @Test
    fun convertPushStatusToHttpStatus() {
        PushStatus.SUCCESS.httpStatus() shouldBe 200 // OK
        PushStatus.INVALID_REQUEST.httpStatus() shouldBe 400 // Bad Request
        PushStatus.DEVICE_UNREGISTERED.httpStatus() shouldBe 410 // Gone
        PushStatus.QUOTA_EXCEEDED.httpStatus() shouldBe 429 // Too Many Requests
        PushStatus.UNAVAILABLE.httpStatus() shouldBe 503 // Service Unavailable
        PushStatus.INTERNAL_ERROR.httpStatus() shouldBe 500 // Internal Server Error
        PushStatus.AUTH_FAILURE.httpStatus() shouldBe 401 // Unauthorized
        PushStatus.PROFILE_MISSING.httpStatus() shouldBe 501 // Not Implemented
        PushStatus.UNKNOWN.httpStatus() shouldBe null
    }

    private fun toJson(res: PushResult): String = Json.encodeToString(PushResult.serializer(), res)

    private fun fromJson(json: String): PushResult = Json.decodeFromString(PushResult.serializer(), json)

    companion object {
        @JvmStatic
        fun serializationTestSource(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "essential fields",
                    PushResult(PushStatus.SUCCESS, PushResultSource.PUSH_PROVIDER),
                    """
                  {
                    "status": "SUCCESS",
                    "resultSource": "PUSH_PROVIDER"
                  }
                """,
                ),
                Arguments.of(
                    "APNS all fields",
                    PushResult(
                        PushStatus.AUTH_FAILURE,
                        PushResultSource.SERVER,
                        "Unauthorized",
                        pushResultProps =
                            ApplePushResultProps(
                                apnsId = "apnsId",
                                apnsUniqueId = "apnsUniqueId",
                                reason = "reason",
                            ),
                    ),
                    """
                  {
                    "status": "AUTH_FAILURE",
                    "resultSource": "SERVER",
                    "reason": "Unauthorized",
                    "pushResultProps": {
                      "type": "${ApplePushResultProps::class.qualifiedName}",
                      "apnsId": "apnsId",
                      "apnsUniqueId": "apnsUniqueId",
                      "reason": "reason"
                    } 
                  }
                """,
                ),
                Arguments.of(
                    "FCM all fields",
                    PushResult(
                        PushStatus.AUTH_FAILURE,
                        PushResultSource.SERVER,
                        "Unauthorized",
                        pushResultProps =
                            FirebasePushResultProps(
                                messageId = "messageId",
                                error =
                                    FcmError(
                                        code = 401,
                                        status = "INVALID_REQUEST",
                                        message = "error",
                                        details =
                                            listOf(
                                                FcmErrorDetails(
                                                    "type",
                                                    mapOf("errorCode" to "INVALID_REQUEST"),
                                                ),
                                            ),
                                    ),
                                retryAfter = "30",
                            ),
                    ),
                    """
                  {
                    "status": "AUTH_FAILURE",
                    "resultSource": "SERVER",
                    "reason": "Unauthorized",
                    "pushResultProps": {
                      "type": "${FirebasePushResultProps::class.qualifiedName}",
                      "messageId": "messageId",
                      "retryAfter": "30",
                        "error": {
                          "code": 401,
                          "status": "INVALID_REQUEST",
                          "message": "error",
                          "details": [
                            {
                                "@type": "type",
                                "errorCode": "INVALID_REQUEST"
                            }
                          ]
                        }
                    } 
                  }
                """,
                ),
            )
    }
}
