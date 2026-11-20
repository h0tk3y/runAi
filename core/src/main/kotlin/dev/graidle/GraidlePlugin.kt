package dev.graidle

import dev.graidle.tasks.AbstractAiTask
import dev.graidle.tasks.AiTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.File

/**
 * The general-purpose grAIdle plugin. It registers no tasks: the build owner registers [dev.graidle.tasks.AiTask]s,
 * and other plugins register their own [AbstractAiTask] subclasses. It adds the `graidle` extension with the settings
 * those tasks share, and the build service that calls the model.
 */
class GraidlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Transcripts and staging files live in the build directory, so `clean` should exist in every project.
        project.pluginManager.apply(BasePlugin::class.java)
        val extension = project.extensions.create(EXTENSION_NAME, GraidleExtension::class.java)
        val providers = project.providers

        extension.model.convention(DEFAULT_MODEL)
        extension.effort.convention("medium")
        extension.maxTokens.convention(16_000L)
        extension.maxAttempts.convention(3)
        extension.refusalFallback.convention(true)
        extension.maxConcurrentRequests.convention(4)
        // CI builds use the committed results and never call the model. Local builds keep the results up to date.
        extension.mode.convention(runsOnCi(providers).map { AiMode.REPLAY }.orElse(AiMode.LIVE))
        // Keep keys out of the repository: an environment variable, or ~/.gradle/gradle.properties.
        extension.apiKey.convention(
            providers.environmentVariable("ANTHROPIC_API_KEY").orElse(providers.gradleProperty("graidle.apiKey"))
        )

        // One service per build. In a multi-project build, the first project that applies the plugin configures it.
        project.gradle.sharedServices.registerIfAbsent(GraidleService.NAME, GraidleService::class.java) {
            parameters.apiKey.set(extension.apiKey)
            parameters.baseUrl.set(extension.baseUrl)
            parameters.maxConcurrentRequests.set(extension.maxConcurrentRequests)
        }

        val modeOverride = providers.gradleProperty("graidle.mode").map(AiMode::parse)
        project.tasks.withType(AbstractAiTask::class.java).configureEach {
            model.convention(extension.model)
            effort.convention(extension.effort)
            maxTokens.convention(extension.maxTokens)
            maxAttempts.convention(extension.maxAttempts)
            refusalFallback.convention(extension.refusalFallback)
            mode.convention(modeOverride.orElse(extension.mode))
            // Results are committed, so they live in the project directory, not in the build directory.
            outputDirectory.convention(project.layout.projectDirectory.dir("graidle/$name"))
            // `locationOnly`: the lock file's path depends on where the output is, not on what the task writes.
            lockFile.convention(
                project.layout.file(outputDirectory.locationOnly.map { File(it.asFile.parentFile, "${it.asFile.name}.lock") })
            )
            transcriptFile.convention(project.layout.buildDirectory.file("graidle/transcripts/$name.json"))
        }
        project.tasks.withType(AiTask::class.java).configureEach {
            baseDirectory.convention(project.layout.projectDirectory)
        }
    }

    companion object {
        const val EXTENSION_NAME = "graidle"
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /**
         * Environment variables that CI servers set. Most set `CI`; Jenkins, TeamCity, Azure Pipelines and
         * AWS CodeBuild do not. A value of `false` does not count.
         */
        val CI_ENVIRONMENT_VARIABLES = listOf("CI", "JENKINS_URL", "TEAMCITY_VERSION", "TF_BUILD", "CODEBUILD_BUILD_ID")

        fun runsOnCi(providers: ProviderFactory): Provider<Boolean> = CI_ENVIRONMENT_VARIABLES
            .map { name ->
                providers.environmentVariable(name).filter { it.isNotBlank() && !it.equals("false", ignoreCase = true) }
            }
            .reduce { first, second -> first.orElse(second) }
            .map { true }
    }
}
