package com.linecorp.pushsphere.server

import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.ContentTooLargeException
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.HttpStatusException
import com.linecorp.armeria.server.ServerErrorHandler
import com.linecorp.armeria.server.ServiceConfig
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.exception.TooLargePayloadException
import io.github.oshai.kotlinlogging.KotlinLogging

internal class PushServerErrorHandler : ServerErrorHandler {
    override fun onServiceException(
        ctx: ServiceRequestContext,
        cause: Throwable,
    ): HttpResponse {
        if (cause is HttpStatusException && cause.httpStatus() == HttpStatus.METHOD_NOT_ALLOWED) {
            // TODO(ikhoon): Generalize this logic to handle http statuses unmapped to PushStatus
            return HttpResponse.of(cause.httpStatus())
        }

        val pushStatus =
            if (cause is TooLargePayloadException || cause is ContentTooLargeException ||
                cause is HttpStatusException && cause.httpStatus() == HttpStatus.REQUEST_ENTITY_TOO_LARGE
            ) {
                PushStatus.TOO_LARGE_PAYLOAD
            } else if (cause is IllegalArgumentException ||
                cause is HttpStatusException && cause.httpStatus().isClientError
            ) {
                PushStatus.INVALID_REQUEST
            } else {
                PushStatus.INTERNAL_ERROR
            }

        return PushResult(
            status = pushStatus,
            resultSource = PushResultSource.SERVER,
            reason = cause.message ?: cause.toString(),
            cause = cause,
            httpStatus = pushStatus.httpStatus(),
        ).toHttpResponse(logger, ctx)
    }

    override fun renderStatus(
        ctx: ServiceRequestContext?,
        config: ServiceConfig,
        headers: RequestHeaders?,
        status: HttpStatus,
        description: String?,
        cause: Throwable?,
    ): AggregatedHttpResponse {
        return PushResult(
            status = PushStatus.of(status.code()),
            resultSource = PushResultSource.SERVER,
            reason = description,
            cause = cause,
            httpStatus = status.code(),
        ).toAggregatedHttpResponse(logger, ctx, ResponseHeaders.of(status))
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
