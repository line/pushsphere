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
package com.linecorp.pushsphere.dist

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.linecorp.armeria.client.ClientFactory
import com.linecorp.pushsphere.server.PushServer
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ForkJoinPool
import kotlin.system.exitProcess

open class Main : CliktCommand() {
    private val configDir by option(
        "-c",
        "--config-dir",
        help = "Configuration directory that contains pushsphere.conf and optional logback.xml",
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

        // Ensure the configuration file exists and is readable.
        val configFile = configDir.resolve("pushsphere.conf")
        if (!Files.isReadable(configFile)) {
            System.err.println("Failed to read the configuration file: $configFile")
            exitProcess(1)
        }

        // Run the pre-configure hook.
        try {
            beforeConfigure(configFile)
        } catch (cause: Throwable) {
            System.err.println("Failed to run the pre-configure hook:")
            cause.printStackTrace()
            exitProcess(1)
        }

        // Configure the logging subsystem.
        if (!LogbackConfigurer.configure(configDir)) {
            System.err.println("Failed to configure logging subsystem.")
            exitProcess(1)
        }

        // Configure the server and run the post-configure hook.
        val logger = KotlinLogging.logger {}
        logger.info { "Configuring Pushsphere server from $configFile .." }
        val server =
            try {
                val server = PushServer.load(configFile)
                afterConfigure(server)
                configureMetrics(server.meterRegistry)
                server
            } catch (cause: Throwable) {
                logger.error(cause) { "Failed to configure Pushsphere server:" }
                exitProcess(1)
            }
        logger.info { "Configured Pushsphere server." }

        // We don't use the client factory's shutdown hook but close the client factory manually.
        // The shutdown hook could close the default ClientFactory while the server is receiving requests in
        // the graceful shutdown time.
        ClientFactory.disableShutdownHook()
        server.onServerStopped { ClientFactory.closeDefault() }

        // Now start the server.
        runBlocking {
            try {
                logger.info { "Starting Pushsphere server .." }
                server.closeOnJvmShutdown {
                    logger.info { "Shutting down Pushsphere server .." }
                }
                server.start()
                logger.info { "Started Pushsphere server." }
            } catch (cause: Throwable) {
                logger.error(cause) { "Failed to start Pushsphere server:" }
                exitProcess(1)
            }
        }
    }

    private fun configureMetrics(registry: MeterRegistry) {
        // Forked form https://github.com/line/centraldogma/blob/c1e7967192f47ad384f774319c29d4a150851e6f/server/src/main/java/com/linecorp/centraldogma/server/CentralDogma.java#L825-L825
        // Bind system metrics.
        FileDescriptorMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        LogbackMetrics().bindTo(registry)

        // Bind global thread pool metrics.
        ExecutorServiceMetrics.monitor(registry, ForkJoinPool.commonPool(), "commonPool")
    }

    protected open fun beforeConfigure(configFile: Path) {}

    protected open fun afterConfigure(server: PushServer) {}
}

fun main(args: Array<String>) = Main().main(args)
