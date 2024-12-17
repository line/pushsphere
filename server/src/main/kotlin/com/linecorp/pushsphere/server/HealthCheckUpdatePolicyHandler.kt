package com.linecorp.pushsphere.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.util.UnmodifiableFuture
import com.linecorp.armeria.server.HttpStatusException
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.healthcheck.HealthCheckUpdateHandler
import com.linecorp.armeria.server.healthcheck.HealthCheckUpdateResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Objects
import java.util.concurrent.CompletionStage

internal class HealthCheckUpdatePolicyHandler(private val updatePolicy: HealthCheckUpdatePolicy) :
    HealthCheckUpdateHandler {
    override fun handle(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): CompletionStage<HealthCheckUpdateResult> {
        return when (updatePolicy) {
            HealthCheckUpdatePolicy.ALLOW_ALL -> DefaultHealthCheckUpdateHandler.handle(ctx, req)
            HealthCheckUpdatePolicy.ALLOW_LOCAL ->
                if (ctx.remoteAddress().address.isLoopbackAddress) {
                    DefaultHealthCheckUpdateHandler.handle(ctx, req)
                } else {
                    throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED)
                }
            HealthCheckUpdatePolicy.DISALLOWED -> throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED)
        }
    }
}

// Forked from https://github.com/line/armeria/blob/7a6ddd7250ab9d6fe6c39d7ac5be7aa22350de33/core/src/main/java/com/linecorp/armeria/server/healthcheck/DefaultHealthCheckUpdateHandler.java#L37
// TODO(ikhoon): Expose the default implementation in the public API via HealthCheckUpdateHandler.of()
private object DefaultHealthCheckUpdateHandler : HealthCheckUpdateHandler {
    override fun handle(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): CompletionStage<HealthCheckUpdateResult> {
        Objects.requireNonNull(req, "req")
        return when (req.method()) {
            HttpMethod.PUT, HttpMethod.POST -> req.aggregate().thenApply(::handlePut)
            HttpMethod.PATCH -> req.aggregate().thenApply(::handlePatch)
            else ->
                UnmodifiableFuture.exceptionallyCompletedFuture(
                    HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED),
                )
        }
    }

    private val mapper = ObjectMapper()

    private fun handlePut(req: AggregatedHttpRequest): HealthCheckUpdateResult {
        val json: JsonNode = toJsonNode(req)
        if (json.nodeType != JsonNodeType.OBJECT) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        }

        val healthy =
            json["healthy"]
                ?: throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        if (healthy.nodeType != JsonNodeType.BOOLEAN) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        }

        return if (healthy.booleanValue()) {
            HealthCheckUpdateResult.HEALTHY
        } else {
            HealthCheckUpdateResult.UNHEALTHY
        }
    }

    private fun handlePatch(req: AggregatedHttpRequest): HealthCheckUpdateResult {
        val json: JsonNode = toJsonNode(req)
        if (json.nodeType != JsonNodeType.ARRAY || json.size() != 1) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        }

        val patchCommand = json[0]
        val op = patchCommand["op"]
        val path = patchCommand["path"]
        val value = patchCommand["value"]
        if (op == null || path == null || value == null ||
            "replace" != op.textValue() || "/healthy" != path.textValue() ||
            !value.isBoolean
        ) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        }

        return if (value.booleanValue()) {
            HealthCheckUpdateResult.HEALTHY
        } else {
            HealthCheckUpdateResult.UNHEALTHY
        }
    }

    private fun toJsonNode(req: AggregatedHttpRequest): JsonNode {
        val contentType = req.contentType()
        if (contentType != null && !contentType.`is`(MediaType.JSON)) {
            throw HttpStatusException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        }

        val charset = contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        try {
            return if (StandardCharsets.UTF_8 == charset) {
                mapper.readTree(req.content().array())
            } else {
                mapper.readTree(req.content(charset))
            }
        } catch (e: IOException) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST)
        }
    }
}
