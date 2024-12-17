package com.linecorp.pushsphere.server

fun interface ProfileSetContextRepository {
    suspend fun find(
        profileSetGroupName: String,
        profileSetName: String,
    ): ProfileSetContext?

    companion object {
        fun of(vararg profileSetContexts: ProfileSetContext): ProfileSetContextRepository {
            return of(profileSetContexts.asIterable())
        }

        fun of(profileSetContexts: Iterable<ProfileSetContext>): ProfileSetContextRepository {
            val mutableMap = mutableMapOf<String, MutableMap<String, ProfileSetContext>>()
            profileSetContexts.forEach {
                mutableMap.getOrPut(it.profileSet.group) { mutableMapOf() }[it.profileSet.name] = it
            }

            val map = mutableMap.mapValues { it.value.toMap() }
            return ProfileSetContextRepository { profileSetGroupName, profileSetName ->
                map[profileSetGroupName]?.get(profileSetName)
            }
        }
    }
}
