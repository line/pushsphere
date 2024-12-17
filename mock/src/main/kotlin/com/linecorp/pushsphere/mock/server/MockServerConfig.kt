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

import com.linecorp.pushsphere.internal.common.Path
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.hocon.Hocon

@Serializable
data class MockServerConfig(
    val service: MockService,
    val httpPort: Int = -1,
    val httpsPort: Int = -1,
    val requestTimeout: Long = -1,
    val serverTls: TlsConfig? = null,
    val clientAuth: ClientAuth? = null,
) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun load(configFile: Path): MockServerConfig {
            val configDir = configFile.parent
            val config: Config = ConfigFactory.parseFile(configFile.toFile()).resolve().getConfig("pushsphere.mock")
            var serverConfig = Hocon.decodeFromConfig(serializer(), config)

            val tls = serverConfig.serverTls
            if (tls?.keyStore?.isAbsolute == false) {
                val normalizedServerTls = tls.copy(keyStore = configDir.resolve(tls.keyStore).normalize())
                serverConfig = serverConfig.copy(serverTls = normalizedServerTls)
            }

            val clientAuth = serverConfig.clientAuth
            if (clientAuth?.certification?.isAbsolute == false) {
                val normalizedClientAuth =
                    clientAuth.copy(certification = configDir.resolve(clientAuth.certification).normalize())
                serverConfig = serverConfig.copy(clientAuth = normalizedClientAuth)
            }

            return serverConfig
        }
    }
}

@Serializable
data class TlsConfig(val keyStore: Path, val alias: String? = null, val password: String? = null)

@Serializable
data class ClientAuth(val enabled: Boolean, val certification: Path? = null)
