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

import org.apiguardian.api.API
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.commons.logging.Logger
import org.junit.platform.commons.logging.LoggerFactory
import org.junit.platform.commons.util.AnnotationUtils
import org.junit.platform.commons.util.Preconditions
import java.lang.annotation.Repeatable
import java.lang.reflect.AnnotatedElement

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Repeatable(EnableIfAnyEnvironmentVariables::class)
@ExtendWith(EnableIfAnyEnvExistsCondition::class)
@Retention(AnnotationRetention.RUNTIME)
annotation class EnableIfAnyEnvironmentVariable(vararg val names: String)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@API(status = API.Status.STABLE, since = "5.6")
annotation class EnableIfAnyEnvironmentVariables(vararg val value: EnableIfAnyEnvironmentVariable)

private class EnableIfAnyEnvExistsCondition :
    AbstractRepeatableAnnotationCondition<EnableIfAnyEnvironmentVariable>(EnableIfAnyEnvironmentVariable::class.java) {
    override fun evaluate(annotation: EnableIfAnyEnvironmentVariable): ConditionEvaluationResult {
        val names: Array<out String> = annotation.names
        Preconditions.condition(
            names.isNotEmpty(),
        ) { "The 'name' attribute must not be empty in $annotation" }

        for (name in names) {
            if (System.getenv(name) != null) {
                return ConditionEvaluationResult.enabled("Environment variable $name exists")
            }
        }

        return ConditionEvaluationResult.disabled("No environment variables found for $names")
    }

    override val noDisabledConditionsEncounteredResult: ConditionEvaluationResult
        get() = ENABLED

    companion object {
        private val ENABLED: ConditionEvaluationResult =
            ConditionEvaluationResult.enabled(
                "No @EnabledIfEnvironmentVariable conditions resulting in 'disabled' execution encountered",
            )
    }
}

// Forked from https://github.com/junit-team/junit5/blob/264df92c85285e65e5cb557d17c9b85557ad6fdc/junit-jupiter-api/src/main/java/org/junit/jupiter/api/condition/AbstractRepeatableAnnotationCondition.java#L34

/**
 * Abstract base class for [ExecutionCondition] implementations that support
 * [repeatable][Repeatable] annotations.
 *
 * @param <A> the type of repeatable annotation supported by this `ExecutionCondition`
 * @since 5.6
</A> */
private abstract class AbstractRepeatableAnnotationCondition<A : Annotation?>(private val annotationType: Class<A>) :
    ExecutionCondition {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val optionalElement = context.element
        if (optionalElement.isPresent) {
            val annotatedElement = optionalElement.get()
            // @formatter:off
            return AnnotationUtils.findRepeatableAnnotations(annotatedElement, this.annotationType).stream()
                .map {
                        annotation: A ->
                    val result = evaluate(annotation)
                    logResult(annotation, annotatedElement, result)
                    result
                }
                .filter { obj: ConditionEvaluationResult -> obj.isDisabled }
                .findFirst()
                .orElse(noDisabledConditionsEncounteredResult)
            // @formatter:on
        }
        return noDisabledConditionsEncounteredResult
    }

    protected abstract fun evaluate(annotation: A): ConditionEvaluationResult

    protected abstract val noDisabledConditionsEncounteredResult: ConditionEvaluationResult
        get

    private fun logResult(
        annotation: A,
        annotatedElement: AnnotatedElement,
        result: ConditionEvaluationResult,
    ) {
        logger.trace {
            String.format(
                "Evaluation of %s on [%s] resulted in: %s",
                annotation,
                annotatedElement,
                result,
            )
        }
    }
}
