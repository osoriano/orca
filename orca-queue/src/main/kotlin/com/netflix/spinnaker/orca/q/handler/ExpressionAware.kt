/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.expressions.ExpressionEvaluationSummary
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.exceptions.ExceptionHandler
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.pipeline.ExpressionAwareStageDefinitionBuilder
import com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.expressions.PipelineExpressionEvaluator.ERROR
import com.netflix.spinnaker.orca.pipeline.expressions.PipelineExpressionEvaluator.SUMMARY
import com.netflix.spinnaker.orca.pipeline.expressions.PipelineExpressionEvaluator.SpelEvaluatorVersion
import com.netflix.spinnaker.orca.pipeline.model.StageContext
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Implemented by handlers that support expression evaluation.
 */
interface ExpressionAware {

  val contextParameterProcessor: ContextParameterProcessor

  companion object {
    val mapper: ObjectMapper = OrcaObjectMapper.getInstance()
  }

  val stageDefinitionBuilderFactory: StageDefinitionBuilderFactory
  val log: Logger
    get() = LoggerFactory.getLogger(javaClass)

  fun StageExecution.withMergedContext(): StageExecution {
    val evalSummary = ExpressionEvaluationSummary()
    val processed = processEntries(this, evalSummary)
    val execution = execution
    val stage = this
    this.context = object : MutableMap<String, Any?> by processed {
      override fun get(key: String): Any? {
        if (key == "trigger") {
          return execution.trigger
        }

        if (key == "execution") {
          return execution
        }

        val result = processed[key]

        if (result is String && ContextParameterProcessor.containsExpression(result)) {
          val augmentedContext = contextParameterProcessor.buildExecutionContext(stage)
          return contextParameterProcessor.process(mapOf(key to result), augmentedContext, true)[key]
        }

        return result
      }
    }

    // Clean up errors: since expressions are evaluated multiple times, it's possible that when
    // they were evaluated before the execution started not all data was available and the evaluation failed for
    // some property. If that evaluation subsequently succeeds, make sure to remove past error messages from the
    // context. Otherwise, it's very confusing in the UI because the value is clearly correctly evaluated but
    // the error is still shown
    if (hasFailedExpressions()) {
      try {
        val failedExpressions = this.context[SUMMARY] as MutableMap<String, *>

        val keysToRemove: List<String> = failedExpressions.keys.filter { expressionKey ->
          (evalSummary.wasAttempted(expressionKey) && !evalSummary.hasFailed(expressionKey))
        }.toList()

        keysToRemove.forEach { expressionKey ->
          failedExpressions.remove(expressionKey)
        }
      } catch (e: Exception) {
        // Best effort clean up, if if fails just log the error and leave the context be
        log.error("Failed to remove stale expression errors", e)
      }
    }

    return this
  }

  fun StageExecution.includeExpressionEvaluationSummary() {
    when {
      hasFailedExpressions() ->
        try {
          val expressionEvaluationSummary = this.context[SUMMARY] as Map<*, *>
          val evaluationErrors: List<String> = expressionEvaluationSummary.values.flatMap { (it as List<*>).map { (it as Map<*, *>)["description"] as String } }
          this.context["exception"] = mergedExceptionErrors(this.context["exception"] as Map<*, *>?, evaluationErrors)
        } catch (e: Exception) {
          log.error("failed to include expression evaluation error in context", e)
        }
    }
  }

  fun StageExecution.hasFailedExpressions(): Boolean =
    (SUMMARY in this.context) &&
      ((this.context[SUMMARY] as Map<*, *>).size > 0)

  fun StageExecution.shouldFailOnFailedExpressionEvaluation(): Boolean {
    return this.hasFailedExpressions() && this.context.containsKey("failOnFailedExpressions") &&
      this.context["failOnFailedExpressions"] as Boolean
  }

  private fun mergedExceptionErrors(exception: Map<*, *>?, errors: List<String>): Map<*, *> =
    if (exception == null) {
      mapOf("details" to ExceptionHandler.responseDetails(ERROR, errors))
    } else {
      val details = exception["details"] as MutableMap<*, *>?
        ?: mutableMapOf("details" to mutableMapOf("errors" to mutableListOf<String>()))
      val mergedErrors: List<*> = (details["errors"] as List<*>? ?: mutableListOf<String>()) + errors
      mapOf("details" to mapOf("errors" to mergedErrors))
    }

  private fun processEntries(stage: StageExecution, summary: ExpressionEvaluationSummary): StageContext {
    var shouldContinueProcessing = true

    val spelVersion = contextParameterProcessor.getEffectiveSpelVersionToUse(stage.execution.spelEvaluator)
    if (SpelEvaluatorVersion.V4 == spelVersion) {
      // Let the stage process its expressions first if it wants (e.g. see EvaluateVariables stage)
      val stageBuilder = stageDefinitionBuilderFactory.builderFor(stage)
      if (stageBuilder is ExpressionAwareStageDefinitionBuilder) {
        shouldContinueProcessing = stageBuilder.processExpressions(stage, contextParameterProcessor, summary)
      }
    }

    if (shouldContinueProcessing) {
      return StageContext(
        stage,
        contextParameterProcessor.process(
          stage.context,
          contextParameterProcessor.buildExecutionContext(stage),
          true,
          summary
        )
      )
    }

    return StageContext(stage, stage.context)
  }

  private operator fun StageContext.plus(map: Map<String, Any?>): StageContext =
    StageContext(this).apply { putAll(map) }
}
