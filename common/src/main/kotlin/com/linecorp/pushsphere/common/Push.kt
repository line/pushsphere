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

import com.linecorp.pushsphere.common.apns.ApplePushProps
import com.linecorp.pushsphere.common.fcm.FirebasePushProps
import com.linecorp.pushsphere.common.web.WebPushProps
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Push internal constructor(
    val title: String? = null,
    val body: String? = null,
    val imageUri: URI? = null,
    val appleProps: ApplePushProps? = null,
    val firebaseProps: FirebasePushProps? = null,
    val webProps: WebPushProps? = null,
) {
    @Transient
    val provider: PushProvider = detectProvider(appleProps, firebaseProps, webProps)

    @Suppress("KotlinConstantConditions")
    private fun detectProvider(
        appleProps: ApplePushProps?,
        firebaseProps: FirebasePushProps?,
        webProps: WebPushProps?,
    ): PushProvider =
        when {
            appleProps != null && firebaseProps == null && webProps == null -> PushProvider.APPLE
            firebaseProps != null && appleProps == null && webProps == null -> PushProvider.FIREBASE
            webProps != null && appleProps == null && firebaseProps == null -> PushProvider.WEB
            appleProps == null && firebaseProps == null && webProps == null -> PushProvider.GENERIC
            else -> throw IllegalArgumentException(
                "Only one of Apple-, Firebase-, or Web-specific properties can be specified for a push notification.",
            )
        }

    fun toApple(props: ApplePushProps? = null): Push {
        return Push(title, body, imageUri, props, null, null)
    }

    fun toFirebase(props: FirebasePushProps? = null): Push {
        return Push(title, body, imageUri, null, props, null)
    }

    fun toWeb(props: WebPushProps? = null): Push {
        return Push(title, body, imageUri, null, null, props)
    }

    companion object {
        fun forApple(
            title: String?,
            body: String?,
            imageUri: URI? = null,
            props: ApplePushProps? = null,
        ): Push {
            return Push(title, body, imageUri, props, null, null)
        }

        fun forFirebase(
            title: String,
            body: String,
            imageUri: URI? = null,
            props: FirebasePushProps? = null,
        ): Push {
            return Push(title, body, imageUri, null, props, null)
        }

        fun forWeb(
            title: String,
            body: String,
            imageUri: URI? = null,
            props: WebPushProps? = null,
        ): Push {
            return Push(title, body, imageUri, null, null, props)
        }
    }
}
