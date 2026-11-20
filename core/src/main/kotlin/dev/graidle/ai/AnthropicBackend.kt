package dev.graidle.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Calls the Claude Messages API with structured outputs. [apiKeyConfigured] is false when no key came from grAIdle's
 * configuration; the SDK may still find other credentials, so it only adds a hint to error messages.
 */
class AnthropicBackend(
    private val client: AnthropicClient,
    private val apiKeyConfigured: Boolean = true,
) : AiBackend {

    override fun complete(request: AiRequest): AiResponse {
        val params = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .addUserMessage(request.prompt)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(request.effort))
                    .format(JsonOutputFormat.builder().schema(schemaOf(request.outputSchema)).build())
                    .build()
            )
            .apply {
                if (request.refusalFallback) {
                    // If the model declines, the API retries on a fallback model it picks by refusal category.
                    putAdditionalHeader("anthropic-beta", REFUSAL_FALLBACK_BETA)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw AiBackendException("The Claude API rejected the credentials: ${e.message}. $CREDENTIALS_HINT", e)
        } catch (e: AnthropicException) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            val detail = if (cause === e) "" else " (${cause.javaClass.simpleName}: ${cause.message})"
            val hint = if (apiKeyConfigured) "" else " No API key is configured. $CREDENTIALS_HINT"
            throw AiBackendException("Claude API request failed: ${e.message}$detail.$hint", e)
        }

        val stopReason = message.stopReason().map { it.asString() }.orElse("unknown")
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
            inputTokens = message.usage().inputTokens(),
            outputTokens = message.usage().outputTokens(),
        )
    }

    override fun close() {
        client.close()
    }

    companion object {
        private const val REFUSAL_FALLBACK_BETA = "server-side-fallback-2026-07-01"
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
            "Set the ANTHROPIC_API_KEY environment variable, or graidle.apiKey in ~/.gradle/gradle.properties."

        private fun schemaOf(json: String): JsonOutputFormat.Schema {
            val builder = JsonOutputFormat.Schema.builder()
            val tree = mapper.readTree(json)
            tree.properties().forEach { (key, value) ->
                builder.putAdditionalProperty(key, JsonValue.fromJsonNode(value))
            }
            return builder.build()
        }
    }
}
