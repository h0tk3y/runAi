package dev.runai.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Calls the Claude Messages API with structured outputs. When the request has tools, it runs the tool loop: it sends
 * the model's tool calls to the handlers, returns the results, and continues until the model gives its final answer.
 * Each assistant turn goes back into the conversation unchanged, thinking blocks included.
 */
class AnthropicBackend(
    private val client: AnthropicClient,
    private val apiKeyConfigured: Boolean = true,
) : AiBackend {

    override fun complete(request: AiRequest): AiResponse {
        val tools = request.tools.map(::toolOf)
        val handlers = request.tools.associateBy { it.name }
        val messages = mutableListOf<MessageParam>(MessageParam.ofUser(request.prompt))
        val calls = mutableListOf<AiToolCall>()
        var inputTokens = 0L
        var outputTokens = 0L

        while (true) {
            val message = send(params(request, tools, messages))
            inputTokens += message.usage().inputTokens()
            outputTokens += message.usage().outputTokens()
            val stopReason = message.stopReason().map { it.asString() }.orElse("unknown")

            if (stopReason == "tool_use") {
                messages += message.toParam()
                val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                    val input = mapper.writeValueAsString(use._input().convert(JsonNode::class.java))
                    val handler = handlers[use.name()]
                    val (output, isError) = when {
                        calls.size >= request.maxToolCalls -> "The limit of ${request.maxToolCalls} tool calls is reached. Answer now." to true
                        handler == null -> "There is no tool named '${use.name()}'." to true
                        else -> try {
                            handler.handler(input) to false
                        } catch (e: Exception) {
                            (e.message ?: e.javaClass.simpleName) to true
                        }
                    }
                    calls += AiToolCall(use.name(), input, output, isError)
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(use.id()).content(output).isError(isError).build()
                    )
                }
                if (calls.size > request.maxToolCalls + 5) {
                    throw AiBackendException("The model made more than ${request.maxToolCalls} tool calls without answering.")
                }
                messages += MessageParam.ofUserOfContentBlockParams(results)
                continue
            }

            when (stopReason) {
                "refusal" -> throw AiBackendException(
                    "The model declined the request" +
                        message.stopDetails().map { d -> " (${d.category().map { it.asString() }.orElse("no category")})" }.orElse("") +
                        "."
                )
                "max_tokens" -> throw AiBackendException(
                    "The answer was cut off at maxTokens=${request.maxTokens}. Increase maxTokens or make the input smaller."
                )
            }
            val text = message.content().flatMap { block -> block.text().map { listOf(it.text()) }.orElse(emptyList()) }
                .joinToString("")
            return AiResponse(
                text = text,
                model = message.model().asString(),
                stopReason = stopReason,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                toolCalls = calls,
            )
        }
    }

    private fun params(request: AiRequest, tools: List<Tool>, messages: List<MessageParam>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .messages(messages)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(request.effort))
                    .format(JsonOutputFormat.builder().schema(schemaOf(request.outputSchema)).build())
                    .build()
            )
            .apply {
                tools.forEach { addTool(it) }
                // A tool loop sends the same prefix on every turn: cache it.
                if (tools.isNotEmpty()) cacheControl(CacheControlEphemeral.builder().build())
                if (request.refusalFallback && hasServerSideFallback(request.model)) {
                    // If the model declines, the API retries on a fallback model it picks by refusal category.
                    putAdditionalHeader("anthropic-beta", REFUSAL_FALLBACK_BETA)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

    private fun send(params: MessageCreateParams): Message = try {
        client.messages().create(params)
    } catch (e: UnauthorizedException) {
        throw AiBackendException("The Claude API rejected the credentials: ${e.message}. $CREDENTIALS_HINT", e)
    } catch (e: AnthropicException) {
        val cause = generateSequence<Throwable>(e) { it.cause }.last()
        val detail = if (cause === e) "" else " (${cause.javaClass.simpleName}: ${cause.message})"
        val hint = if (apiKeyConfigured) "" else " No API key is configured. $CREDENTIALS_HINT"
        throw AiBackendException("Claude API request failed: ${e.message}$detail.$hint", e)
    }

    override fun close() {
        client.close()
    }

    companion object {
        private const val REFUSAL_FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /**
         * Haiku models have no server-side refusal fallback: with `fallbacks: "default"` a declined request stays
         * declined, and a list of fallback models is rejected. So the parameter is not sent to them.
         */
        fun hasServerSideFallback(model: String): Boolean = !model.startsWith("claude-haiku-")
        private val mapper = ObjectMapper()

        fun create(apiKey: String?, baseUrl: String?): AnthropicBackend {
            val client = try {
                AnthropicOkHttpClient.builder()
                    .fromEnv()
                    .apply {
                        if (!apiKey.isNullOrBlank()) apiKey(apiKey)
                        if (!baseUrl.isNullOrBlank()) baseUrl(baseUrl)
                    }
                    .build()
            } catch (e: Exception) {
                throw AiBackendException("Could not create the Claude API client: ${e.message}. $CREDENTIALS_HINT", e)
            }
            return AnthropicBackend(client, apiKeyConfigured = !apiKey.isNullOrBlank())
        }

        const val CREDENTIALS_HINT =
            "Set the ANTHROPIC_API_KEY environment variable, or runAi.apiKey in ~/.gradle/gradle.properties."

        private fun schemaOf(json: String): JsonOutputFormat.Schema {
            val builder = JsonOutputFormat.Schema.builder()
            mapper.readTree(json).properties().forEach { (key, value) ->
                builder.putAdditionalProperty(key, JsonValue.fromJsonNode(value))
            }
            return builder.build()
        }

        private fun toolOf(tool: AiTool): Tool {
            val schema = mapper.readTree(tool.inputSchema)
            val properties = Tool.InputSchema.Properties.builder()
            schema.path("properties").properties().forEach { (name, value) ->
                properties.putAdditionalProperty(name, JsonValue.fromJsonNode(value))
            }
            return Tool.builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(schema.path("required").map { it.asText() })
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build()
                )
                .strict(true)
                .build()
        }
    }
}
