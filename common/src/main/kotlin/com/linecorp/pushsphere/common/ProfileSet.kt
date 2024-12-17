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
package com.linecorp.pushsphere.common

import java.util.function.Function

data class ProfileSet(
    val group: String,
    val name: String,
    val profiles: List<Profile>,
) {
    private val profileMap: Map<PushProvider, Profile> = profiles.associateBy { it.provider }

    constructor(group: String, name: String, vararg profiles: Profile) : this(
        group = group,
        name = name,
        profiles = profiles.toList(),
    )

    init {
        require(profiles.isNotEmpty()) { "At least one profile is required." }
        require(profiles.size == profileMap.size) { "Cannot specify multiple profiles for the same provider." }
    }

    val fullName: String
        get() = "$group/$name"

    fun findProfile(provider: PushProvider): Profile? = profileMap[provider]
}

class ProfileSetLogStringifier : Function<ProfileSet, String> {
    override fun apply(p: ProfileSet) = p.fullName
}
