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

import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.PushsphereProfile
import com.linecorp.pushsphere.common.URI

object TestProfiles {
    // TODO(ikhoon): Use a properties file or a yaml file?
    val local: TestProfile =
        TestProfile(
            Profile.forPushsphere(
                URI.create("http://127.0.0.1:8080"),
                "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzZXJ2aWNlQ29kZSI6IlBVU0hTUEhFUkUiLCJwZXJtaX" +
                    "NzaW9ucyI6WyJ0YWxrOioiXSwiaXNzIjoiZG9vcmtlZXBlci1zZXJ2ZXI6YmV0YSIsImV4cCI6NDg0MDc2NTEzMywi" +
                    "aWF0IjoxNjg3MTY1MTMzLCJ1c2VySWQiOiJscDEwMTcxIiwib3JnSWQiOjM2fQ.xDzf_9rI-vXgLzEC0DS-iQH9RJV" +
                    "sXM1MwF3xZgcN3n4",
                "talk",
                "main",
                "DK",
            ),
            // TODO(ikhoon) Use a real device token.
            "change-me",
        )
}

data class TestProfile(val profile: PushsphereProfile, val deviceToken: String)
