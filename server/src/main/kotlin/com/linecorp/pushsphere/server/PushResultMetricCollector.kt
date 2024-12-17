package com.linecorp.pushsphere.server

import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.apns.ApnsErrorCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags

internal object PushResultMetricCollector {
    private const val UNKNOWN_TAG_VALUE = "UNKNOWN"
    private const val ERROR_CODE_NO_ERROR = "NO_ERROR"

    fun count(
        pushResult: PushResult,
        meterRegistry: MeterRegistry,
        counterName: String,
        profileSetGroup: String?,
        profileSetName: String?,
        pushProvider: PushProvider?,
    ) {
        meterRegistry.counter(
            counterName,
            Tags.of(
                "profileSetGroup", profileSetGroup ?: UNKNOWN_TAG_VALUE,
                "profileSetName", profileSetName ?: UNKNOWN_TAG_VALUE,
                "provider", pushProvider?.name ?: UNKNOWN_TAG_VALUE,
                "status", pushResult.status.name,
                "resultSource", pushResult.resultSource.name,
                "errorCode", ApnsErrorCode.from(pushResult.applePushResultProps?.reason)?.name ?: ERROR_CODE_NO_ERROR,
            ),
        ).increment()
    }
}
