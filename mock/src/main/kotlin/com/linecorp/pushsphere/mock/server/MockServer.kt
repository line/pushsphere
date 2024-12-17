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

import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.internal.common.util.KeyStoreUtil
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.logging.AccessLogWriter
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.net.URI
import java.nio.file.Path

class MockServer(config: MockServerConfig) {
    constructor(configFile: Path) : this(MockServerConfig.load(configFile))

    private val server: Server

    init {
        val builder = Server.builder()
        if (config.httpPort >= 0) {
            builder.http(config.httpPort)
        }
        if (config.httpsPort >= 0) {
            builder.https(config.httpsPort)
            val tlsConfig = config.serverTls
            if (tlsConfig == null) {
                builder.tlsSelfSigned()
            } else {
                val keyPair = KeyStoreUtil.load(tlsConfig.keyStore.toFile(), tlsConfig.password, null, tlsConfig.alias)
                builder.tls(keyPair)
            }

            if (config.clientAuth?.enabled == true) {
                builder.tlsCustomizer {
                    it.clientAuth(ClientAuth.REQUIRE)
                    if (config.clientAuth.certification != null) {
                        it.trustManager(config.clientAuth.certification.toFile())
                    } else {
                        it.trustManager(InsecureTrustManagerFactory.INSTANCE)
                    }
                }
            }
        }

        if (config.requestTimeout >= 0) {
            builder.requestTimeoutMillis(config.requestTimeout)
        }

        builder.service(config.service)
        builder.accessLogWriter(AccessLogWriter.common(), true)

        val server = builder.build()
        this.server = server
    }

    fun start() {
        server.start().join()
    }

    fun stop() {
        server.stop().join()
    }

    fun closeOnJvmShutdown(whenClosing: () -> Unit = {}) {
        server.closeOnJvmShutdown(whenClosing)
    }

    fun httpPort(): Int = server.activeLocalPort(SessionProtocol.HTTP)

    fun httpsPort(): Int = server.activeLocalPort(SessionProtocol.HTTPS)

    fun httpUri(): URI = URI.create("http://127.0.0.1:${httpPort()}")

    fun httpsUri(): URI = URI.create("https://127.0.0.1:${httpsPort()}")
}
