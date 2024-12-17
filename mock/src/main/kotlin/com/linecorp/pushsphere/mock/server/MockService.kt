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
package com.linecorp.pushsphere.mock.server

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpServiceWithRoutes
import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.serialization.Serializable

@Serializable
class MockService(private val mappings: List<MockExchange>) : HttpServiceWithRoutes {
    override fun routes(): Set<Route> = mappings.map { it.request.route() }.toSet()

    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val future = CoroutineScope(ctx.eventLoop().asCoroutineDispatcher()).future { serve0(ctx, req) }
        return HttpResponse.of(future)
    }

    private suspend fun serve0(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val agg = req.aggregate().await()
        val mockExchange =
            mappings.find { it.request.matches(ctx, agg) } ?: return HttpResponse.of(HttpStatus.NOT_FOUND)
        if (mockExchange.delayMillis > 0) {
            delay(mockExchange.delayMillis)
        }

        val mockResponse = mockExchange.response
        val headers =
            ResponseHeaders.builder()
                .status(mockResponse.status)
                .apply { mockResponse.headers.forEach { (k, v) -> add(k, v) } }
                .build()
        return HttpResponse.of(headers, HttpData.ofUtf8(mockResponse.body))
    }
}

@Serializable
data class MockExchange(val request: MockRequest, val response: MockResponse, val delayMillis: Long)
