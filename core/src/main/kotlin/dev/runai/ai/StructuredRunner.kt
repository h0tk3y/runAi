package dev.runai.ai

/** The result of checking one answer from the model. */
sealed interface Validation<out T> {
    data class Valid<T>(val value: T) : Validation<T>

    /** The answer has fixable problems. They are sent back to the model on the next attempt. */
    data class Invalid(val problems: List<String>) : Validation<Nothing>

    /** The model reported that it cannot do the task. This is not retried. */
    data class CannotComplete(val reason: String) : Validation<Nothing>

    /**
     * The inputs need a decision by a person, for example because they contradict each other. This is not retried,
     * and the task fails with [message] as it is.
     */
    data class NeedsDecision(val message: String) : Validation<Nothing>
}

class AiTaskFailedException(message: String) : RuntimeException(message)

/**
 * Asks for a structured answer and checks it. When the check finds problems, it asks again with the problems and
 * the previous answer added to the prompt. Every attempt is a new single-turn request.
 */
class StructuredRunner(
    private val complete: (AiRequest) -> AiResponse,
    private val transcript: Transcript,
) {
    fun <T> run(
        label: String,
        request: AiRequest,
        maxAttempts: Int,
        validate: (String) -> Validation<T>,
    ): T {
        var prompt = request.prompt
        var lastProblems = emptyList<String>()
        for (attempt in 1..maxAttempts) {
            val attemptRequest = request.copy(prompt = prompt)
            val started = System.nanoTime()
            val response = complete(attemptRequest)
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val result = validate(response.text)
            transcript.add(
                Transcript.Entry(
                    label = label,
                    attempt = attempt,
                    requestedModel = request.model,
                    servedModel = response.model,
                    stopReason = response.stopReason,
                    inputTokens = response.inputTokens,
                    outputTokens = response.outputTokens,
                    durationMs = durationMs,
                    problems = when (result) {
                        is Validation.Invalid -> result.problems
                        is Validation.CannotComplete -> listOf("cannot_complete: ${result.reason}")
                        is Validation.NeedsDecision -> listOf("needs a decision: ${result.message}")
                        is Validation.Valid -> emptyList()
                    },
                    prompt = prompt,
                    response = response.text,
                    toolCalls = response.toolCalls,
                )
            )
            when (result) {
                is Validation.Valid -> return result.value
                is Validation.CannotComplete -> throw AiTaskFailedException(
                    "The model could not complete '$label': ${result.reason}"
                )
                is Validation.NeedsDecision -> throw AiTaskFailedException(result.message)
                is Validation.Invalid -> {
                    lastProblems = result.problems
                    prompt = retryPrompt(request.prompt, response.text, result.problems)
                }
            }
        }
        throw AiTaskFailedException(
            "The answer for '$label' still had problems after $maxAttempts attempt(s):\n" +
                lastProblems.joinToString("\n") { "  - $it" }
        )
    }

    private fun retryPrompt(original: String, previousAnswer: String, problems: List<String>): String = buildString {
        append(original)
        append("\n\n")
        append("A previous answer to this task had problems. Fix them and return the complete answer again.\n\n")
        append("Problems:\n")
        problems.forEach { append("- ").append(it).append('\n') }
        append("\nPrevious answer:\n")
        append(previousAnswer)
        append('\n')
    }
}
