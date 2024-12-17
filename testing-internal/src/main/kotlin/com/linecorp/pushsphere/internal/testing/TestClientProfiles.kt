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
package com.linecorp.pushsphere.internal.testing

import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.typesafe.config.ConfigFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import java.nio.file.Path
import kotlin.io.path.toPath

object TestClientProfiles {
    fun load(
        configFile: String,
        profile: String,
    ): TestClientProfile {
        return load(ResourceUtil.getUrl(configFile).toURI().toPath(), profile)
    }

    /**
     * Loads the `dev` profile from the given resource location.
     */
    fun load(configFile: Path): TestClientProfile {
        return load(configFile, "dev")
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun load(
        configFile: Path,
        profile: String,
    ): TestClientProfile {
        val clientConfig =
            ConfigFactory
                .parseFile(configFile.toFile()).resolve()
                .getConfig("pushsphere.client.$profile")
        return Hocon.decodeFromConfig<TestClientProfile>(clientConfig)
    }
}

@Serializable
data class TestClientProfile(
    val accessToken: String,
    val profileSetGroup: String,
    val profileSet: String,
    val authScheme: String,
    val deviceToken: String,
)
