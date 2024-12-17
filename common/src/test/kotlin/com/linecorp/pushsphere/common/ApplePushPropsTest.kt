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

import com.linecorp.pushsphere.common.apns.AppleAps
import com.linecorp.pushsphere.common.apns.AppleHeaders
import com.linecorp.pushsphere.common.apns.AppleInterruptionLevel
import com.linecorp.pushsphere.common.apns.ApplePushAlert
import com.linecorp.pushsphere.common.apns.ApplePushProps
import com.linecorp.pushsphere.common.apns.ApplePushType
import com.linecorp.pushsphere.common.apns.AppleSound
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ApplePushPropsTest {
    @Test
    fun `encode ApplePushProps`() {
        val payload = Json.encodeToString(applePushProps)

        payload shouldEqualJson encodedApplePushProps
    }

    @Test
    fun `decode ApplePushProps`() {
        val decodedPayload = Json.decodeFromString<ApplePushProps>(encodedApplePushProps)

        decodedPayload shouldBe applePushProps
    }

    @Test
    fun `decode wrong format of ApplePushProps should fail`() {
        val malformedApplePushProps =
            """
            {
                "headers": {
                    "apnsPushType": "alert",
                    "hack": "show me the money"
                },
                "aps": {
                    "badge": 5
                }
            }
            """.trimIndent()

        shouldThrow<SerializationException> { Json.decodeFromString(malformedApplePushProps) }
    }

    companion object {
        private val headers =
            AppleHeaders(
                apnsPushType = ApplePushType.ALERT,
                apnsId = "apns-id",
                apnsExpiration = 2147483648,
            )

        private val customData =
            mapOf(
                "backgroundData" to "10",
                "newValue" to 20,
            )

        private val applePushAlert =
            ApplePushAlert.Dict(
                subtitle = "subtitle",
                locKey = "name",
                locArgs = listOf("John", "Doe"),
            )

        private val sound = AppleSound.String("bingbong")

        private val aps =
            AppleAps(
                alert = applePushAlert,
                badge = 5,
                sound = sound,
                category = "message",
                interruptionLevel = AppleInterruptionLevel.ACTIVE,
                contentState =
                    mapOf(
                        "driverName" to "Kim",
                        "estimatedDeliveryTime" to 1659416400,
                    ),
            )

        private val applePushProps =
            ApplePushProps(
                headers,
                aps,
                customData,
            )

        private val encodedApplePushProps =
            """
            {
                "headers": {
                    "apnsPushType": "alert",
                    "apnsId": "apns-id",
                    "apnsExpiration": 2147483648
                },
                "aps": {
                    "alert": {
                        "subtitle": "subtitle",
                        "locKey": "name",
                        "locArgs": [
                            "John",
                            "Doe"
                        ]
                    },
                    "badge": 5,
                    "sound": "bingbong",
                    "category": "message",
                    "interruptionLevel": "active",
                    "contentState": {
                        "driverName": "Kim",
                        "estimatedDeliveryTime": 1659416400
                    }
                },
                "backgroundData": "10",
                "newValue": 20
            }
            """.trimIndent()
    }
}
