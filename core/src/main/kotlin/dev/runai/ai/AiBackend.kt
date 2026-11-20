package dev.runai.ai

/**
 * A tool the model may call while it works on a request. [inputSchema] is a JSON schema of an object whose
 * properties are all required. [handler] gets the input as JSON and returns the result as text; an exception becomes
 * an error result that the model sees.
 */
class AiTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val handler: (inputJson: String) -> String,
)

/** One tool call of a request, for the transcript. */
data class AiToolCall(val name: String, val input: String, val output: String, val isError: Boolean)

/**
 * One request to the model: a system prompt, one user message, and a JSON schema the final answer must match. With
 * [tools], the model can call them first; the backend runs the calls and continues until the model answers.
 */
data class AiRequest(
    val model: String,
    val effort: String,
    val maxTokens: Long,
    val system: String,
    val prompt: String,
    val outputSchema: String,
    val refusalFallback: Boolean,
    val tools: List<AiTool> = emptyList(),
    /** The most tool calls in one request, so a model that keeps exploring cannot run forever. */
    val maxToolCalls: Int = 60,
)

data class AiResponse(
    /** The JSON text of the final answer. */
    val text: String,
    /** The model that produced the answer. Differs from the requested model after a refusal fallback. */
    val model: String,
    val stopReason: String,
    /** Input and output tokens, summed over all turns of the request. */
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCalls: List<AiToolCall> = emptyList(),
)

/** A model provider. A request is one conversation, so a backend keeps no state between requests. */
interface AiBackend : AutoCloseable {
    fun complete(request: AiRequest): AiResponse

    override fun close() {}
}

class AiBackendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
