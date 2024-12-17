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
data class NetworkOptions(
    val maxNumEventLoops: Int? = null,
    val responseTimeoutMillis: Long? = null,
    val writeTimeoutMillis: Long? = null,
    val idleTimeoutMillis: Long? = null,
    val connectTimeoutMillis: Long? = null,
    val maxConnectionAgeMillis: Long? = null,
    val tlsNoVerify: Boolean? = false,
) {
    companion object {
        val EMPTY = NetworkOptions()
    }
}
