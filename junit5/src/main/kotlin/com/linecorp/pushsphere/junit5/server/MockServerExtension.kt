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
package com.linecorp.pushsphere.junit5.server

import com.linecorp.armeria.client.BlockingWebClient
import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.linecorp.pushsphere.mock.server.MockServer
import com.linecorp.pushsphere.mock.server.MockServerConfig
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.toPath

open class MockServerExtension(private val config: MockServerConfig) : AbstractAllOrEachExtension() {
    constructor(configFile: Path) : this(MockServerConfig.load(configFile))

    /**
     * Creates a new [MockServer] from the given resource location.
     *
     * @param configFile the resource location to resolve: either a
     *                   "classpath:" pseudo URL, a "file:" URL, or a plain file path
     */
    constructor(configFile: String) : this(ResourceUtil.getUrl(configFile).toURI().toPath())

    private var started: Boolean = false
    private var server: MockServer? = null
    private var blockingClient: BlockingWebClient? = null

    override fun before(context: ExtensionContext) {
        start()
        started = true
    }

    override fun after(context: ExtensionContext) {
        stop()
    }

    fun blockingClient(): BlockingWebClient {
        ensureServerStarted()
        var blockingClient = this.blockingClient
        if (blockingClient != null) {
            return blockingClient
        }
        blockingClient = BlockingWebClient.of(server!!.httpUri())
        this.blockingClient = blockingClient
        return blockingClient
    }

    fun httpPort(): Int {
        return server().httpPort()
    }

    fun httpsPort(): Int {
        return server().httpsPort()
    }

    fun httpUri(): URI {
        return server().httpUri()
    }

    fun httpsUri(): URI {
        return server().httpUri()
    }

    private fun start() {
        val server = MockServer(config)
        this.server = server
        server.start()
    }

    private fun stop() {
        server().stop()
    }

    private fun server(): MockServer {
        ensureServerStarted()
        return server!!
    }

    private fun ensureServerStarted() {
        require(started) { "MockServer is not started yet." }
    }
}
