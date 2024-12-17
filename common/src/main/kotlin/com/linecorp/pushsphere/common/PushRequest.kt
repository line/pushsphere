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

import kotlinx.serialization.Serializable

@Serializable
data class PushRequest(
    override val provider: PushProvider,
    override val deviceToken: String,
    val push: Push,
    override val idempotencyKey: String? = null,
    val variables: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        > = emptyMap(),
    val appData: Map<
        String,
        @Serializable(CustomPropertySerializer::class)
        Any?,
        > = emptyMap(),
) : SendRequest {
    init {
        require(provider != PushProvider.GENERIC) {
            "provider: $provider (expected: APPLE, FIREBASE or WEB)"
        }
        require(deviceToken.isNotBlank()) {
            "deviceToken must not be blank"
        }

        if (push.provider != PushProvider.GENERIC) {
            require(provider == push.provider) {
                "push.provider must be the same as provider or GENERIC. (provider: $provider, push.provider: ${push.provider})"
            }
        }
    }
}
