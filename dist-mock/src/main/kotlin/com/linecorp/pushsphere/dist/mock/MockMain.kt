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
package com.linecorp.pushsphere.dist.mock

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.linecorp.pushsphere.dist.LogbackConfigurer
import com.linecorp.pushsphere.mock.server.MockServer
import com.linecorp.pushsphere.mock.server.MockServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

open class MockMain : CliktCommand() {
    private val configDir by option(
        "-c",
        "--config-dir",
        help = "Configuration directory that contains mock-server.conf and optional logback.xml",
    ).default(".${File.separator}conf")

    final override fun run() {
        // Ensure the configuration directory exists.
        val configDir: Path
        try {
            configDir = Paths.get(this.configDir).toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (cause: Throwable) {
            System.err.println("Failed to read the configuration directory: ${this.configDir}")
            exitProcess(1)
        }

        // Configure the logging subsystem.
        if (!LogbackConfigurer.configure(configDir)) {
            System.err.println("Failed to configure logging subsystem.")
            exitProcess(1)
        }
        // Ensure the configuration file exists and is readable.
        val configFile = configDir.resolve("mock-server.conf")
        if (!Files.isReadable(configFile)) {
            System.err.println("Failed to read the configuration file: $configFile")
            exitProcess(1)
        }

        // Configure the server and run the post-configure hook.
        val logger = KotlinLogging.logger {}
        logger.info("Configuring the mock server from {} ..", configFile)
        val server =
            try {
                val config = MockServerConfig.load(configFile)
                MockServer(config)
            } catch (cause: Throwable) {
                logger.error("Failed to configure the mock server:", cause)
                exitProcess(1)
            }
        logger.info("Configured the mock server.")

        // XXX(ikhoon): Should we need metrics for mock servers?

        // Now start the server.
        try {
            logger.info("Starting the mock server ..")
            server.closeOnJvmShutdown {
                logger.info("Shutting down the mock server ..")
            }
            server.start()
            logger.info("Started the mock server.")
        } catch (cause: Throwable) {
            logger.error("Failed to start the mock server:", cause)
            exitProcess(1)
        }
    }
}

fun main(args: Array<String>) = MockMain().main(args)
