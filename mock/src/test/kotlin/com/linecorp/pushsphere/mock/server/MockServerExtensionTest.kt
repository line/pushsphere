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

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.internal.common.util.KeyStoreUtil
import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.linecorp.pushsphere.junit5.server.MockServerExtension
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.io.path.toPath

class MockServerExtensionTest {
    @Test
    fun shouldHandleRequests() {
        val client = server.blockingClient()
        val path0 = "/internal/health"
        client.get(path0).contentUtf8() shouldBe """{ "healthy": "true" }"""

        val path1 = "/3/device/40604d4b91dd6413496834a46ac50822bfc194e0774cdbfc5cffb8cf8ab03ef3"
        val response = client.post(path1, "random")
        response.headers().contains("apns-id", "1") shouldBe true

        client.get(path1).status() shouldBe HttpStatus.METHOD_NOT_ALLOWED

        // Unknown path
        client.get("/unknown").status() shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun shouldServeMutualTls() {
        val keyStoreFile = ResourceUtil.getUrl("classpath:tls.p12").toURI().toPath().toFile()
        val keyStore = KeyStoreUtil.load(keyStoreFile, "secret", null, null)

        val factory =
            ClientFactory
                .builder()
                .tls(keyStore)
                .build()

        val client =
            WebClient
                .builder(server.httpsUri())
                .factory(factory)
                .build()
                .blocking()

        client.get("/internal/health").contentUtf8() shouldBe """{ "healthy": "true" }"""
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = MockServerExtension("classpath:mock-server-test.conf")
    }
}
