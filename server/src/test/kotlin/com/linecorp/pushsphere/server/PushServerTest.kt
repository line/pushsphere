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

import com.fasterxml.jackson.databind.JsonNode
import com.linecorp.armeria.client.InvalidHttpResponseException
import com.linecorp.armeria.client.RestClient
import com.linecorp.armeria.client.kotlin.execute
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseEntity
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.ProfileSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI

class PushServerTest {
    private val profile =
        Profile.forPushsphere(URI.create("https://foo.com/"), "bearer", "token", "foo", "main")

    private val sesameAuthorizer =
        object : PushAuthorizer {
            override val name: String = "sesame"

            override fun supportsScheme(scheme: String): Boolean = scheme == "bearer"

            override suspend fun authorize(
                authorization: PushAuthorization,
                profileSetGroupName: String,
                profileSetName: String,
            ): ProfileSetContext? {
                return if (
                    authorization.scheme == "bearer" &&
                    authorization.parameters == "sesame" &&
                    profileSetGroupName == "foo" &&
                    profileSetName == "bar"
                ) {
                    ProfileSetContext(
                        mockk<PushServerProfileSetConfig>(),
                        ProfileSet(
                            profileSetGroupName,
                            profileSetName,
                            profile,
                        ),
                    )
                } else {
                    null
                }
            }
        }

    private val perillaAuthorizer =
        object : PushAuthorizer {
            override val name: String = "perilla"

            override fun supportsScheme(scheme: String): Boolean = scheme == "bearer"

            override suspend fun authorize(
                authorization: PushAuthorization,
                profileSetGroupName: String,
                profileSetName: String,
            ): ProfileSetContext? {
                return if (
                    authorization.scheme == "bearer" &&
                    authorization.parameters == "perilla" &&
                    profileSetGroupName == "foo" &&
                    profileSetName == "bar"
                ) {
                    ProfileSetContext(
                        mockk<PushServerProfileSetConfig>(),
                        ProfileSet(
                            profileSetGroupName,
                            profileSetName,
                            profile,
                        ),
                    )
                } else {
                    null
                }
            }
        }

    @Test
    fun `should return 200 on authentication success`() =
        runTest {
            PushServer(
                authorizers = listOf(sesameAuthorizer),
                gracefulShutdownOptions = GracefulShutdownOptions.EMPTY,
            ).use { server ->
                val result = authorize(server, "bearer sesame")
                result.status() shouldBe HttpStatus.OK
            }
        }

    @Test
    fun `should return 401 on incorrect token`() =
        runTest {
            PushServer(
                authorizers = listOf(sesameAuthorizer),
                gracefulShutdownOptions = GracefulShutdownOptions.EMPTY,
            ).use { server ->
                val cause =
                    shouldThrow<InvalidHttpResponseException> {
                        authorize(server, "bearer perilla")
                    }

                cause.response().status() shouldBe HttpStatus.UNAUTHORIZED
            }
        }

    @Test
    fun `should return 401 on missing token`() =
        runTest {
            PushServer(
                authorizers = listOf(sesameAuthorizer),
                gracefulShutdownOptions = GracefulShutdownOptions.EMPTY,
            ).use { server ->
                val cause =
                    shouldThrow<InvalidHttpResponseException> {
                        authorize(server, null)
                    }

                cause.response().status() shouldBe HttpStatus.UNAUTHORIZED
            }
        }

    @Test
    fun `should allow specifying more than one authorizer`() =
        runTest {
            PushServer(
                authorizers = listOf(sesameAuthorizer, perillaAuthorizer),
                gracefulShutdownOptions = GracefulShutdownOptions.EMPTY,
            ).use { server ->
                authorize(server, "bearer sesame").status() shouldBe HttpStatus.OK
                authorize(server, "bearer perilla").status() shouldBe HttpStatus.OK
                shouldThrow<InvalidHttpResponseException> {
                    authorize(server, "bearer bean")
                }.response().status() shouldBe HttpStatus.UNAUTHORIZED
            }
        }

    private suspend fun authorize(
        server: PushServer,
        authorization: String?,
    ): ResponseEntity<JsonNode> {
        return RestClient
            .of()
            .get("http://127.0.0.1:${server.activeHttpPort()}/api/v1/foo/bar/authorize")
            .apply {
                authorization?.let {
                    header(HttpHeaderNames.AUTHORIZATION, it)
                }
            }
            .execute<JsonNode>()
    }
}
