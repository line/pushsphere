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
package com.linecorp.pushsphere.server

import com.linecorp.armeria.internal.common.util.ResourceUtil
import com.linecorp.pushsphere.common.FirebaseProfile
import com.linecorp.pushsphere.common.credentials.GoogleServiceAccountCredentials
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.io.path.toPath

class FirebaseProfileConfigTest {
    @Test
    fun `should parse and read FCM configuration`() {
        val configFile = ResourceUtil.getUrl("classpath:pushsphere-test.conf").toURI().toPath()
        val config = PushServerConfig.load(configFile)
        val fcmConfig = config.profileSets[0].profiles[1] as PushServerFirebaseProfileConfig
        fcmConfig.endpointUri shouldBe URI.create("https://fcm.googleapis.com")
        fcmConfig.credentials.toString() shouldBe "./pushsphere-test-service-account.json"
        val configDir = configFile.parent
        val firebaseProfile = fcmConfig.toProfile(configDir) as FirebaseProfile
        firebaseProfile.credentials.serviceAccount shouldBe
            GoogleServiceAccountCredentials(configDir.resolve("pushsphere-test-service-account.json").toFile())
                .serviceAccount
        firebaseProfile.credentials.serviceAccount.projectId shouldBe "pushsphere-test"
    }
}
