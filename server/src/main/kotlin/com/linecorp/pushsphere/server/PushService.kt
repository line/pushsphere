package com.linecorp.pushsphere.server

import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseEntity
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Attribute
import com.linecorp.armeria.server.annotation.ConsumesJson
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Post
import com.linecorp.armeria.server.annotation.ProducesJson
import com.linecorp.armeria.server.annotation.RequestConverter
import com.linecorp.armeria.server.annotation.RequestConverterFunction
import com.linecorp.pushsphere.client.PushClient
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.ProfileSet
import com.linecorp.pushsphere.common.PushOptions
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushRequest
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RawPushRequest
import com.linecorp.pushsphere.internal.common.PushsphereHeaderNames
import com.linecorp.pushsphere.internal.common.RemoteRetryOptionsSerDes
import com.linecorp.pushsphere.server.PushAuthorizer.Companion.PROFILE_SET_CONTEXT_KEY
import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.AttributeKey
import kotlinx.serialization.json.Json
import java.lang.reflect.ParameterizedType
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal class PushService(private val meterRegistry: MeterRegistry) {
    private val clients = ConcurrentHashMap<Profile, PushClient>()

    @Get("/api/v1/{profileSetGroup}/{profileSet}/authorize")
    @ProducesJson
    fun authorize(): ResponseEntity<PushResult> {
        return PushResult(
            PushStatus.SUCCESS,
            PushResultSource.SERVER,
            httpStatus = PushStatus.SUCCESS.httpStatus(),
        ).toResponseEntity()
    }

    @Post("/api/v1/{profileSetGroup}/{profileSet}/send")
    @ConsumesJson
    @ProducesJson
    @RequestConverter(PushRequestConverter::class)
    @RequestConverter(PushOptionsConverter::class)
    suspend fun send(
        req: PushRequest,
        pushOptions: PushOptions?,
        @Attribute(prefix = PushAuthorizer::class, value = PROFILE_SET_CONTEXT_KEY)
        profileSetContext: ProfileSetContext,
    ): ResponseEntity<PushResult> {
        val profileSet = profileSetContext.profileSet
        val profile =
            profileSet.findProfile(req.provider)
                ?: return PushResult.ofProfileMissing(req.provider, profileSet.fullName).toResponseEntity()
        val profileConfig =
            profileSetContext.config.findProfileConfig(req.provider)
                ?: return PushResult.ofProfileMissing(req.provider, profileSet.fullName).toResponseEntity()

        val result =
            when (profile.provider) {
                PushProvider.APPLE, PushProvider.FIREBASE -> {
                    maybeCreateClient(profileSet, profile).send(
                        req,
                        if (profileConfig.useRequestOptionsHeader) {
                            pushOptions
                        } else {
                            null
                        },
                    )
                }

                else -> {
                    PushResult(
                        PushStatus.INTERNAL_ERROR,
                        PushResultSource.SERVER,
                        reason = "Push provider ${req.provider} is not supported.",
                        httpStatus = PushStatus.INTERNAL_ERROR.httpStatus(),
                    )
                }
            }

        return result.toResponseEntity()
    }

    @Post("/api/v1/{profileSetGroup}/{profileSet}/send/raw")
    @ConsumesJson
    @ProducesJson
    @RequestConverter(RawPushRequestConverter::class)
    @RequestConverter(PushOptionsConverter::class)
    suspend fun sendRaw(
        req: RawPushRequest,
        pushOptions: PushOptions?,
        @Attribute(prefix = PushAuthorizer::class, value = PROFILE_SET_CONTEXT_KEY)
        profileSetContext: ProfileSetContext,
    ): ResponseEntity<PushResult> {
        val profileSet = profileSetContext.profileSet
        val profile =
            profileSet.findProfile(req.provider)
                ?: return PushResult.ofProfileMissing(req.provider, profileSet.fullName).toResponseEntity()
        val profileConfig =
            profileSetContext.config.findProfileConfig(req.provider)
                ?: return PushResult.ofProfileMissing(req.provider, profileSet.fullName).toResponseEntity()

        val result =
            when (profile.provider) {
                PushProvider.APPLE, PushProvider.FIREBASE -> {
                    maybeCreateClient(profileSet, profile).sendRaw(
                        req,
                        if (profileConfig.useRequestOptionsHeader) {
                            pushOptions
                        } else {
                            null
                        },
                    )
                }

                else -> {
                    PushResult(
                        PushStatus.INTERNAL_ERROR,
                        PushResultSource.SERVER,
                        reason = "Push provider ${req.provider} is not supported.",
                        httpStatus = PushStatus.INTERNAL_ERROR.httpStatus(),
                    )
                }
            }

        return result.toResponseEntity()
    }

    private fun maybeCreateClient(
        profileSet: ProfileSet,
        profile: Profile,
    ): PushClient =
        clients.computeIfAbsent(profile) {
            when (profile.provider) {
                PushProvider.APPLE, PushProvider.FIREBASE ->
                    PushClient.of(
                        profile,
                        MeterIdPrefix(
                            "pushsphere.client",
                            "provider",
                            profile.provider.name.lowercase(),
                            "name",
                            profileSet.fullName,
                        ),
                        meterRegistry,
                    )

                else -> TODO("not implemented")
            }
        }

    companion object {
        val PUSH_PROVIDER = AttributeKey.valueOf<PushProvider>(PushService::class.java, "PUSH_PROVIDER")
        val DEVICE_TOKEN = AttributeKey.valueOf<String>(PushService::class.java, "DEVICE_TOKEN")
        val IDEMPOTENCY_KEY = AttributeKey.valueOf<String>(PushService::class.java, "IDEMPOTENCY_KEY")

        fun injectCtxLoggingAttr(
            ctx: ServiceRequestContext,
            req: PushRequest,
        ) {
            ctx.setAttr(PUSH_PROVIDER, req.provider)
            ctx.setAttr(DEVICE_TOKEN, req.deviceToken)
            ctx.setAttr(IDEMPOTENCY_KEY, req.idempotencyKey)
        }

        fun injectCtxLoggingAttr(
            ctx: ServiceRequestContext,
            req: RawPushRequest,
        ) {
            ctx.setAttr(PUSH_PROVIDER, req.provider)
            ctx.setAttr(DEVICE_TOKEN, req.deviceToken)
            ctx.setAttr(IDEMPOTENCY_KEY, req.idempotencyKey)
        }
    }
}

private fun PushResult.toResponseEntity(): ResponseEntity<PushResult> {
    val httpStatusCode = status.httpStatus()
    val httpStatus =
        if (httpStatusCode != null) {
            HttpStatus.valueOf(httpStatusCode)
        } else {
            HttpStatus.INTERNAL_SERVER_ERROR
        }
    val pushResultProps = this.pushResultProps
    val responseHeaders =
        ResponseHeaders
            .builder(httpStatus)
            .apply {
                pushResultProps?.retryAfter?.let {
                    this.add(HttpHeaderNames.RETRY_AFTER, it)
                }
            }
            .build()

    val responseContent =
        if (this.resultSource == PushResultSource.CLIENT) {
            this.copy(resultSource = PushResultSource.SERVER)
        } else {
            this
        }

    return ResponseEntity.of(responseHeaders, responseContent)
}

private class PushRequestConverter : RequestConverterFunction {
    override fun convertRequest(
        ctx: ServiceRequestContext,
        request: AggregatedHttpRequest,
        expectedResultType: Class<*>,
        expectedParameterizedResultType: ParameterizedType?,
    ): PushRequest {
        if (PushRequest::class.java.isAssignableFrom(expectedResultType)) {
            val charset = request.contentType()?.charset() ?: StandardCharsets.UTF_8
            val req = Json.decodeFromString(PushRequest.serializer(), request.content(charset))
            PushService.injectCtxLoggingAttr(ctx, req)
            return req
        }

        return RequestConverterFunction.fallthrough()
    }
}

private class RawPushRequestConverter : RequestConverterFunction {
    override fun convertRequest(
        ctx: ServiceRequestContext,
        request: AggregatedHttpRequest,
        expectedResultType: Class<*>,
        expectedParameterizedResultType: ParameterizedType?,
    ): RawPushRequest {
        if (RawPushRequest::class.java.isAssignableFrom(expectedResultType)) {
            val charset = request.contentType()?.charset() ?: StandardCharsets.UTF_8

            val req = Json.decodeFromString(RawPushRequest.serializer(), request.content(charset))
            PushService.injectCtxLoggingAttr(ctx, req)
            return req
        }

        return RequestConverterFunction.fallthrough()
    }
}

private class PushOptionsConverter : RequestConverterFunction {
    override fun convertRequest(
        ctx: ServiceRequestContext,
        request: AggregatedHttpRequest,
        expectedResultType: Class<*>,
        expectedParameterizedResultType: ParameterizedType?,
    ): PushOptions? {
        if (PushOptions::class.java.isAssignableFrom(expectedResultType)) {
            val headers = ctx.request().headers()
            val remoteRetryOptions =
                RemoteRetryOptionsSerDes.deserialize(
                    headers.get(PushsphereHeaderNames.REMOTE_RETRY_MAX_ATTEMPTS),
                    headers.get(PushsphereHeaderNames.REMOTE_RETRY_BACKOFF),
                    headers.get(PushsphereHeaderNames.REMOTE_RETRY_TIMEOUT_PER_ATTEMPT),
                    headers.get(PushsphereHeaderNames.REMOTE_RETRY_POLICIES),
                    headers.getAll(PushsphereHeaderNames.REMOTE_HTTP_STATUS_OPTIONS),
                    headers.get(PushsphereHeaderNames.REMOTE_RETRY_AFTER_STRATEGY),
                )
            val remoteResponseTimeoutMillis =
                ctx.request().headers().get(PushsphereHeaderNames.REMOTE_RESPONSE_TIMEOUT)?.toLong()
            return if (remoteRetryOptions != null || remoteResponseTimeoutMillis != null) {
                PushOptions(
                    localRetryOptions = remoteRetryOptions,
                    localTotalTimeoutMillis = remoteResponseTimeoutMillis,
                )
            } else {
                null
            }
        }

        return RequestConverterFunction.fallthrough()
    }
}

private fun PushResult.Companion.ofProfileMissing(
    provider: PushProvider,
    profileSetFullName: String,
): PushResult =
    PushResult(
        PushStatus.PROFILE_MISSING,
        PushResultSource.SERVER,
        reason = "Push provider $provider is missing for the profile set $profileSetFullName",
        httpStatus = PushStatus.PROFILE_MISSING.httpStatus(),
    )
