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
package com.linecorp.pushsphere.client.apns

import com.linecorp.pushsphere.common.Push
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ApnsPayloadTest {
    @Test
    fun serializeApnsPayload() {
        val payload =
            ApnsPayload(
                aps =
                    ApnsAps(
                        alert =
                            ApnsAlert.Dict(
                                title = "title",
                                body = "body",
                                launchImage = "https://example.com/image.png",
                            ),
                        badge = 1,
                        sound = AppleSound.String("default"),
                    ),
            )
        val json = Json.encodeToString(payload)
        //language=JSON
        json shouldEqualJson
            """{"aps":
            |{
            |  "alert":{
            |    "title":"title",
            |    "body":"body",
            |    "launch-image":"https://example.com/image.png"
            |  },
            |  "badge":1,
            |  "sound": "default"
            |}}
            """.trimMargin()
    }

    @Test
    fun serializeSound() {
        val soundStringAsSound = soundString as AppleSound
        Json.encodeToString(soundStringAsSound) shouldBe """"default""""

        val soundDictAsSound =
            AppleSound.Dict(
                critical = 1,
                name = "default",
                volume = 0.5f,
            ) as AppleSound
        Json.encodeToString(soundDictAsSound) shouldBe """{"critical":1,"name":"default","volume":0.5}"""
    }

    @Test
    fun `serialize alert type ApnsPayload from Push`() {
        val headers =
            AppleHeaders(
                apnsPushType = ApplePushType.ALERT,
                apnsId = "123e4567-e89b-12d3-a456-4266554400a0",
                apnsExpiration = 10,
            )
        val applePushProps =
            ApplePushProps(
                headers,
                aps,
                customData,
            )
        val push = Push.forApple("title", "body", null, applePushProps)
        val json = Json.encodeToString(ApnsPayload.from(push))

        json shouldEqualJson
            """
            {
                "aps": {
                    "alert": {
                        "title": "title",
                        "subtitle": "subtitle",
                        "body": "body",
                        "loc-key": "name",
                        "loc-args": [
                            "John",
                            "Doe"
                        ]
                    },
                    "badge": 5,
                    "sound": "default",
                    "category": "message",
                    "interruption-level": "active",
                    "content-state": {
                        "driverName": "Kim",
                        "estimatedDeliveryTime": 1659416400
                    }
                },
                "backgroundStringData": "10",
                "backgroundIntData": 20,
                "backgroundNullData": null
            }
            """.trimIndent()
    }

    @Test
    fun `serialize background type ApnsPayload from Push`() {
        val applePushProps =
            ApplePushProps(
                backgroundNotificationHeaders,
                AppleAps(
                    contentAvailable = 1,
                ),
                customData,
            )
        val push = Push.forApple(null, null, null, applePushProps)
        val json = Json.encodeToString(ApnsPayload.from(push))

        json shouldEqualJson
            """
            {
                "aps": {
                    "content-available": 1
                },
                "backgroundStringData": "10",
                "backgroundIntData": 20,
                "backgroundNullData": null
            }
            """.trimIndent()
    }

    @Test
    fun `background notification should set contentAvailable field`() {
        val applePushProps =
            ApplePushProps(
                backgroundNotificationHeaders,
                AppleAps(),
                customData,
            )
        val push = Push.forApple(null, null, null, applePushProps)

        shouldThrow<IllegalArgumentException> { Json.encodeToString(ApnsPayload.from(push)) }
    }

    companion object {
        private val customData =
            mapOf(
                "backgroundStringData" to "10",
                "backgroundIntData" to 20,
                "backgroundNullData" to null,
            )

        private val applePushAlert =
            ApplePushAlert.Dict(
                subtitle = "subtitle",
                locKey = "name",
                locArgs = listOf("John", "Doe"),
            )

        private val soundString = AppleSound.String("default")

        private val aps =
            AppleAps(
                alert = applePushAlert,
                badge = 5,
                sound = soundString,
                category = "message",
                interruptionLevel = AppleInterruptionLevel.ACTIVE,
                contentState =
                    mapOf(
                        "driverName" to "Kim",
                        "estimatedDeliveryTime" to 1659416400,
                    ),
            )

        private val backgroundNotificationHeaders =
            AppleHeaders(
                apnsPushType = ApplePushType.BACKGROUND,
                apnsId = "123e4567-e89b-12d3-a456-4266554400a0",
                apnsPriority = 5,
            )
    }
}
