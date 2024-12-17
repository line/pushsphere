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
package com.linecorp.pushsphere.server

import com.linecorp.armeria.client.BlockingWebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.util.SystemInfo
import com.linecorp.pushsphere.junit5.server.PushServerExtension
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.Inet4Address

class PushServerHealthCheckTest {
    @Test
    fun disallowUpdateByDefault() {
        val client = BlockingWebClient.of(server.httpUri())
        client.get("/internal/health").status() shouldBe HttpStatus.OK

        client.post("/internal/health", """{ "healthy": false }""")
            .status() shouldBe HttpStatus.METHOD_NOT_ALLOWED
        client.get("/internal/health").status() shouldBe HttpStatus.OK
    }

    @Test
    fun allowUpdateByLocal() {
        val client = BlockingWebClient.of(serverAllowLocal.httpUri())
        client.get("/internal/health").status() shouldBe HttpStatus.OK

        client.post("/internal/health", """{ "healthy": false }""")
            .status() shouldBe HttpStatus.SERVICE_UNAVAILABLE
        client.get("/internal/health").status() shouldBe HttpStatus.SERVICE_UNAVAILABLE

        client.post("/internal/health", """{ "healthy": true }""")
            .status() shouldBe HttpStatus.OK
        client.get("/internal/health").status() shouldBe HttpStatus.OK

        val nonLoopbackIp: Inet4Address = SystemInfo.defaultNonLoopbackIpV4Address()!!
        val externalClient =
            BlockingWebClient.of("http://${nonLoopbackIp.hostAddress}:${serverAllowLocal.httpPort()}")
        externalClient.get("/internal/health").status() shouldBe HttpStatus.OK
        externalClient.post("/internal/health", """{ "healthy": false }""")
            .status() shouldBe HttpStatus.METHOD_NOT_ALLOWED
        externalClient.get("/internal/health").status() shouldBe HttpStatus.OK
    }

    @Test
    fun allowUpdateByAll() {
        val client = BlockingWebClient.of(serverAllowAll.httpUri())
        client.get("/internal/healthcheck").status() shouldBe HttpStatus.OK

        client.post("/internal/healthcheck", """{ "healthy": false }""")
            .status() shouldBe HttpStatus.SERVICE_UNAVAILABLE
        client.get("/internal/healthcheck").status() shouldBe HttpStatus.SERVICE_UNAVAILABLE

        client.post("/internal/healthcheck", """{ "healthy": true }""")
            .status() shouldBe HttpStatus.OK
        client.get("/internal/healthcheck").status() shouldBe HttpStatus.OK

        val nonLoopbackIp: Inet4Address = SystemInfo.defaultNonLoopbackIpV4Address()!!
        val externalClient =
            BlockingWebClient.of("http://${nonLoopbackIp.hostAddress}:${serverAllowAll.httpPort()}")
        externalClient.get("/internal/healthcheck").status() shouldBe HttpStatus.OK
        externalClient.post("/internal/healthcheck", """{ "healthy": false }""")
            .status() shouldBe HttpStatus.SERVICE_UNAVAILABLE
        externalClient.get("/internal/healthcheck").status() shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = PushServerExtension("classpath:pushsphere-test.conf")

        @JvmField
        @RegisterExtension
        val serverAllowLocal = PushServerExtension("classpath:pushsphere-healthcheck-allow-local.conf")

        @JvmField
        @RegisterExtension
        val serverAllowAll = PushServerExtension("classpath:pushsphere-healthcheck-allow-all.conf")
    }
}
