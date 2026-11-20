package dev.graidle.ai

/** One request to the model: a system prompt, one user message, and a JSON schema the answer must match. */
data class AiRequest(
    val model: String,
    val effort: String,
    val maxTokens: Long,
    val system: String,
    val prompt: String,
    val outputSchema: String,
    val refusalFallback: Boolean,
)

data class AiResponse(
    /** The JSON text of the answer. */
    val text: String,
    /** The model that produced the answer. Differs from the requested model after a refusal fallback. */
    val model: String,
    val stopReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/** A model provider. Requests are single-turn, so a backend keeps no conversation state. */
interface AiBackend : AutoCloseable {
    fun complete(request: AiRequest): AiResponse

    override fun close() {}
}

class AiBackendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
