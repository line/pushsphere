package com.linecorp.pushsphere.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.metric.MoreMeters
import com.linecorp.armeria.common.util.UnmodifiableFuture
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.Authorizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.netty.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

interface PushAuthorizer {
    val name: String

    fun supportsScheme(scheme: String): Boolean

    suspend fun authorize(
        authorization: PushAuthorization,
        profileSetGroupName: String,
        profileSetName: String,
    ): ProfileSetContext?

    companion object {
        const val PROFILE_SET_CONTEXT_KEY = "PROFILE_SET_CONTEXT"
        internal val PROFILE_SET_CONTEXT =
            AttributeKey.valueOf<ProfileSetContext>(
                PushAuthorizer::class.java,
                PROFILE_SET_CONTEXT_KEY,
            )
        internal val AUTH_SCHEME = AttributeKey.valueOf<String>(PushAuthorizer::class.java, "AUTH_SCHEME")

        internal fun PushAuthorizer.asArmeriaAuthorizer(meterRegistry: MeterRegistry): Authorizer<HttpRequest> {
            return ArmeriaAuthorizer(this, meterRegistry)
        }
    }
}

private class ArmeriaAuthorizer(private val parent: PushAuthorizer, meterRegistry: MeterRegistry) : Authorizer<HttpRequest> {
    private val success: Timer
    private val failure: Timer

    init {
        val meterIdPrefix = MeterIdPrefix("pushsphere.authorization.duration", "type", parent.name)
        success = MoreMeters.newTimer(meterRegistry, meterIdPrefix.name(), meterIdPrefix.tags("result", "success"))
        failure = MoreMeters.newTimer(meterRegistry, meterIdPrefix.name(), meterIdPrefix.tags("result", "failure"))
    }

    override fun authorize(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): CompletionStage<Boolean> {
        val authorization =
            PushAuthorization.parse(req.headers().get(HttpHeaderNames.AUTHORIZATION))
                ?: return UnmodifiableFuture.completedFuture(false)
        ctx.setAttr(PushAuthorizer.AUTH_SCHEME, authorization.scheme)

        if (!parent.supportsScheme(authorization.scheme)) {
            return UnmodifiableFuture.completedFuture(false)
        }

        val profileSetGroupName =
            ctx.pathParam("profileSetGroup")
                ?: return UnmodifiableFuture.completedFuture(false)

        val profileSetName =
            ctx.pathParam("profileSet")
                ?: return UnmodifiableFuture.completedFuture(false)

        // Invoke the `PushAuthorizer` with the retrieved authorization parameters.
        val startTimeNanos = System.nanoTime()
        val coroutineScope = CoroutineScope(ctx.eventLoop().asCoroutineDispatcher())
        return coroutineScope.future {
            parent.authorize(authorization, profileSetGroupName, profileSetName)
        }.handle { profileSetContext, cause ->
            val durationNanos = System.nanoTime() - startTimeNanos
            if (cause != null) {
                failure.record(durationNanos, TimeUnit.NANOSECONDS)
                throw cause // Let the caller handle the exception.
            }

            if (profileSetContext == null) {
                failure.record(durationNanos, TimeUnit.NANOSECONDS)
                false
            } else {
                ctx.setAttr(PushAuthorizer.PROFILE_SET_CONTEXT, profileSetContext)
                success.record(durationNanos, TimeUnit.NANOSECONDS)
                true
            }
        }
    }
}
