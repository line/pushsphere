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

import com.linecorp.armeria.client.ClientDecoration
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.linecorp.pushsphere.client.PushClient
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.PushsphereProfile
import com.linecorp.pushsphere.internal.testing.TestClientProfiles
import com.linecorp.pushsphere.server.PushServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.toPath

open class PushServerExtension : AbstractAllOrEachExtension {
    /**
     * Creates a new [PushServer] from the given resource location.
     */
    constructor(configFile: Path) : super() {
        this.configFile = configFile
        this.pushServer = PushServer.load(configFile)
    }

    /**
     * Creates a new [PushServer] from the given resource location.
     *
     * @param configFile the resource location to resolve: either a
     *                   "classpath:" pseudo URL, a "file:" URL, or a plain file path
     */
    constructor(configFile: String) : this(ResourceUtil.getUrl(configFile).toURI().toPath())

    val configFile: Path
    private val pushServer: PushServer
    private var defaultClient: PushClient? = null
    private var started: Boolean = false

    protected open fun clientMeterIdPrefix(): MeterIdPrefix? = null

    protected open fun clientDecoration(): ClientDecoration = ClientDecoration.of()

    /**
     * Returns a new [PushClient] with the given [PushsphereProfile].
     * Note that [PushsphereProfile.endpointUri] is ignored and [httpUri] will be used instead.
     */
    fun client(profile: PushsphereProfile): PushClient {
        return PushClient.of(
            profile.copy(endpointUri = httpUri()),
            clientMeterIdPrefix(),
            pushServer.meterRegistry,
            clientDecoration(),
        )
    }

    /**
     * Returns a new [PushClient] with `pushsphere.client.dev` configuration defined in [configFile].
     * ```
     * // in the config file
     * pushsphere {
     *   client {
     *     dev {
     *       accessToken = "token-1234..."
     *       profileSetGroup = "group"
     *       profileSet = "main"
     *       authScheme = "bearer"
     *       deviceToken = "change-me"
     *     }
     *   }
     * }
     * ```
     */
    fun client(): PushClient {
        return client(DEFAULT_CLIENT_PROFILE)
    }

    /**
     * Returns a new [PushClient] with `pushsphere.client.$profile` configuration defined in [configFile].
     * ```
     * // in the config file
     * pushsphere {
     *   client {
     *     $profile {
     *       accessToken = "token-1234..."
     *       profileSetGroup = "group"
     *       profileSet = "main"
     *       authScheme = "bearer"
     *       deviceToken = "change-me"
     *     }
     *   }
     * }
     * ```
     */
    fun client(profile: String): PushClient {
        if (profile == DEFAULT_CLIENT_PROFILE) {
            val defaultClient = this.defaultClient
            if (defaultClient != null) {
                return defaultClient
            }
        }

        val clientProfile = TestClientProfiles.load(configFile, profile)
        val pushsphereProfile =
            Profile.forPushsphere(
                httpUri(),
                clientProfile.authScheme,
                clientProfile.accessToken,
                clientProfile.profileSetGroup,
                clientProfile.profileSet,
            )

        return PushClient.of(
            pushsphereProfile,
            clientMeterIdPrefix(),
            pushServer.meterRegistry,
            clientDecoration(),
        )
    }

    override fun before(context: ExtensionContext) {
        start()
    }

    override fun after(context: ExtensionContext) {
        stop()
    }

    private fun start() {
        runBlocking {
            pushServer.start()
            started = true
        }
    }

    private fun stop() {
        runBlocking {
            pushServer.close()
            started = false
        }
    }

    fun server(): PushServer {
        ensureServerStarted()
        return pushServer
    }

    /**
     * Returns the HTTP port number of the [PushServer].
     *
     *@throws IllegalStateException if the [PushServer] is not started or it did not open an HTTP port
     */
    fun httpPort(): Int {
        ensureServerStarted()
        return server().activeHttpPort()
    }

    /**
     * Returns the HTTPS port number of the [PushServer].
     *
     * @throws IllegalStateException if the [PushServer] is not started or it did not open an HTTPS port
     */
    fun httpsPort(): Int {
        ensureServerStarted()
        return pushServer.activeHttpsPort()
    }

    /**
     * Returns the HTTP [URI] for the [PushServer].
     *
     * @return the absolute [URI] without a path.
     * @throws IllegalStateException if the [PushServer] is not started or
     * it did not open an HTTP port.
     */
    fun httpUri(): URI {
        ensureServerStarted()
        return URI.create("http://127.0.0.1:${httpPort()}")
    }

    /**
     * Returns the HTTPS [URI] for the [PushServer].
     *
     * @return the absolute [URI] without a path.
     * @throws IllegalStateException if the [PushServer] is not started or
     * it did not open an HTTPS port.
     */
    fun httpsUri(): URI {
        ensureServerStarted()
        return URI.create("https://127.0.0.1:${httpsPort()}")
    }

    private fun ensureServerStarted() {
        require(started) { "PushServer is not started yet." }
    }

    companion object {
        private const val DEFAULT_CLIENT_PROFILE = "dev"
    }
}
