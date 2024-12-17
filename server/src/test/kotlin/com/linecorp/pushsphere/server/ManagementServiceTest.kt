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

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.util.SystemInfo
import com.linecorp.pushsphere.junit5.server.PushServerExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.ConnectException

class ManagementServiceTest {
    @Test
    fun `should configure ManagementService`() {
        val client =
            WebClient.builder("https://127.0.0.1:58443")
                .factory(ClientFactory.insecure())
                .build()
                .blocking()
        client.get("/management/jvm/threaddump").status().code() shouldBe 200

        val nonLoopbackIpV4Address = SystemInfo.defaultNonLoopbackIpV4Address()!!
        val publicClient =
            WebClient.builder("https://${nonLoopbackIpV4Address.hostAddress}:58443")
                .factory(ClientFactory.insecure())
                .build()
                .blocking()
        shouldThrow<UnprocessedRequestException> {
            publicClient.get("/management/jvm/threaddump")
        }.cause should beInstanceOf<ConnectException>()
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = PushServerExtension("classpath:pushsphere-management.conf")
    }
}
