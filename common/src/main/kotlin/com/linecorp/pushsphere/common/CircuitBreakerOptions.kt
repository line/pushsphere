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

/**
 * Stores configurations of circuit breaker.
 */
@Serializable
data class CircuitBreakerOptions(
    val namePrefix: String? = null,
    val failureRateThreshold: Double = -1.0,
    val minimumRequestThreshold: Long = -1,
    val trialRequestIntervalMillis: Long = 0,
    val circuitOpenWindowMillis: Long = 0,
    val counterSlidingWindowMillis: Long = 0,
    val counterUpdateIntervalMillis: Long = 0,
    val failFastOnAllCircuitOpen: Boolean = false,
) {
    companion object {
        val EMPTY = CircuitBreakerOptions()
    }
}
