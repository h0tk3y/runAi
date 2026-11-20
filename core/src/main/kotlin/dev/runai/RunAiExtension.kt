package dev.runai

import org.gradle.api.provider.Property

/**
 * Settings shared by all runAi tasks in a project. Every [dev.runai.tasks.AbstractRunAi] takes these as defaults
 * and can override them.
 */
abstract class RunAiExtension {

    /** The exact model ID. */
    abstract val model: Property<String>

    /** `low`, `medium`, `high`, `xhigh` or `max`. */
    abstract val effort: Property<String>

    abstract val maxTokens: Property<Long>

    /** How many times a task may ask again when an answer fails the checks. */
    abstract val maxAttempts: Property<Int>

    /** Let the API retry a declined request on a fallback model. Ignored for Haiku models, which have none. */
    abstract val refusalFallback: Property<Boolean>

    /**
     * Whether tasks may call the model. Defaults to REPLAY on CI (`CI`, `JENKINS_URL`, `TEAMCITY_VERSION`, `TF_BUILD`
     * or `CODEBUILD_BUILD_ID` is set) and LIVE elsewhere. `-PrunAi.mode=live|replay|off` overrides it.
     */
    abstract val mode: Property<AiMode>

    /** The most model requests in flight at the same time, across the whole build. */
    abstract val maxConcurrentRequests: Property<Int>

    /**
     * Defaults to the `ANTHROPIC_API_KEY` environment variable, then the `runAi.apiKey` Gradle property. When both
     * are unset, the SDK looks for other credentials in the environment.
     */
    abstract val apiKey: Property<String>

    /** Overrides the API URL, for example for a proxy or a test server. */
    abstract val baseUrl: Property<String>
}
