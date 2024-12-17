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

import com.linecorp.armeria.internal.common.util.ResourceUtil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.io.path.name
import kotlin.io.path.toPath

class MockServerConfigTest {
    @Test
    fun shouldParseConfig() {
        val configFile = ResourceUtil.getUrl("classpath:mock-server-test.conf").toURI().toPath()
        val config = MockServerConfig.load(configFile)
        config.httpPort shouldBe 0
        config.httpsPort shouldBe 0
        config.requestTimeout shouldBe 1000
        val serverTls = config.serverTls!!
        serverTls.keyStore.isAbsolute shouldBe true
        serverTls.keyStore.name shouldBe "tls.p12"
        serverTls.alias shouldBe null
        serverTls.password shouldBe "secret"

        val clientAuth = config.clientAuth!!
        clientAuth.enabled shouldBe true
        val clientCert = clientAuth.certification!!
        clientCert.isAbsolute shouldBe true
        clientCert.name shouldBe "client.crt.pem"
    }
}
