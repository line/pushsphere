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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ProfileSetTest {
    @Test
    fun `should not allow empty profile list`() {
        shouldThrow<IllegalArgumentException> {
            ProfileSet("group", "name")
        }.message shouldContain "At least one profile"
    }

    @Test
    fun `should not allow more than one profile with same provider`() {
        shouldThrow<IllegalArgumentException> {
            ProfileSet(
                "group",
                "name",
                Profile.forApple(URI.create("https://foo.com"), "token-foo", "bundle-foo"),
                Profile.forApple(URI.create("https://bar.com"), "token-bar", "bundle-bar"),
            )
        }.message shouldContain "multiple profiles"
    }
}
