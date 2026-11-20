package dev.graidle

import dev.graidle.ai.AiBackend
import dev.graidle.ai.AiRequest
import dev.graidle.ai.AiResponse
import dev.graidle.ai.AnthropicBackend
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared by all grAIdle tasks in a build: one HTTP client, a limit on concurrent requests, and token totals.
 */
abstract class GraidleService : BuildService<GraidleService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val apiKey: Property<String>
        val baseUrl: Property<String>
        val maxConcurrentRequests: Property<Int>
    }

    private val backend: AiBackend by lazy {
        AnthropicBackend.create(parameters.apiKey.orNull, parameters.baseUrl.orNull)
    }
    private val permits: Semaphore by lazy { Semaphore(parameters.maxConcurrentRequests.get().coerceAtLeast(1)) }

    private val requests = AtomicLong()
    private val inputTokens = AtomicLong()
    private val outputTokens = AtomicLong()

    fun complete(request: AiRequest): AiResponse {
        permits.acquire()
        try {
            return backend.complete(request).also {
                requests.incrementAndGet()
                inputTokens.addAndGet(it.inputTokens)
                outputTokens.addAndGet(it.outputTokens)
            }
        } finally {
            permits.release()
        }
    }

    override fun close() {
        if (requests.get() > 0) {
            Logging.getLogger(GraidleService::class.java).lifecycle(
                "grAIdle: ${requests.get()} model request(s), ${inputTokens.get()} input tokens, ${outputTokens.get()} output tokens."
            )
            backend.close()
        }
    }

    companion object {
        const val NAME = "graidle"
    }
}
