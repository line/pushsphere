package com.linecorp.pushsphere.server

internal class StaticPushAuthorizer(
    private val authorizationMap: Map<PushAuthorization, ProfileSetContextRepository>,
) : PushAuthorizer {
    override val name: String = "static"

    override fun supportsScheme(scheme: String): Boolean = scheme == "bearer"

    override suspend fun authorize(
        authorization: PushAuthorization,
        profileSetGroupName: String,
        profileSetName: String,
    ): ProfileSetContext? {
        assert(supportsScheme(authorization.scheme)) { "Unexpected scheme: ${authorization.scheme} (expected: bearer)" }
        return authorizationMap[authorization]?.find(profileSetGroupName, profileSetName)
    }
}
