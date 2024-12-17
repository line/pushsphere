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

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import ch.qos.logback.core.status.ErrorStatus
import ch.qos.logback.core.status.StatusUtil
import ch.qos.logback.core.util.StatusPrinter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

internal object LogbackConfigurer {
    private val logger = KotlinLogging.logger {}

    fun configure(configDir: Path): Boolean {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.reset()

        val configurator = JoranConfigurator()
        configurator.context = loggerContext

        // Configure using the `logback.xml` in the configuration directory if exists.
        // Use the fallback configuration otherwise.
        try {
            val logbackConf = configDir.resolve("logback.xml")
            if (Files.exists(logbackConf)) {
                println("Configuring logging subsystem using $logbackConf ..")
                configurator.doConfigure(logbackConf.toFile())
            } else {
                println("Configuring logging subsystem ..")
                configurator.doConfigure(javaClass.classLoader.getResourceAsStream("logback-fallback.xml"))
            }
        } catch (je: JoranException) {
            // StatusPrinter will handle this.
        }

        // Print the Logback status if there are any errors or warnings.
        val statusUtil = StatusUtil(loggerContext)
        val highestLevel = statusUtil.getHighestLevel(ErrorStatus.INFO.toLong())
        if (highestLevel >= ErrorStatus.WARN) {
            StatusPrinter.print(loggerContext.statusManager, ErrorStatus.INFO.toLong())
            if (highestLevel >= ErrorStatus.ERROR) {
                return false
            }
        }

        logger.info("Configured logging system.")
        return true
    }
}
