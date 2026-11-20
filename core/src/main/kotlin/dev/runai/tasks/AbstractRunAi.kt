package dev.runai.tasks

import dev.runai.AiMode
import dev.runai.RunAiService
import dev.runai.ai.AiBackendException
import dev.runai.ai.AiLock
import dev.runai.ai.AiRequest
import dev.runai.ai.AiTaskFailedException
import dev.runai.ai.FileAnswers
import dev.runai.ai.Fingerprint
import dev.runai.ai.PreviousResult
import dev.runai.ai.StructuredRunner
import dev.runai.ai.Transcript
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Base class for tasks that ask a model for a structured answer.
 *
 * The result is committed to the repository, together with a lock file that holds a [Fingerprint] of the inputs
 * that produced it. When the inputs match the lock file, the task only checks the committed result with fixed rules
 * and never calls the model. When they do not match, the task calls the model in [AiMode.LIVE] mode and fails in
 * [AiMode.REPLAY] mode.
 */
@DisableCachingByDefault(because = "The result is committed to the repository, and checking it is cheaper than caching it")
abstract class AbstractRunAi : DefaultTask() {

    /** The exact model ID. Part of the fingerprint. */
    @get:Input
    abstract val model: Property<String>

    /** `low`, `medium`, `high`, `xhigh` or `max`. Part of the fingerprint. */
    @get:Input
    abstract val effort: Property<String>

    @get:Internal
    abstract val maxTokens: Property<Long>

    /** How many times to ask again when an answer fails the checks. */
    @get:Internal
    abstract val maxAttempts: Property<Int>

    /**
     * If the model declines, let the API retry on a fallback model. The transcript records which model answered.
     * Ignored for Haiku models, which have no server-side fallback.
     */
    @get:Internal
    abstract val refusalFallback: Property<Boolean>

    /** The version of the prompts this task type sends. Part of the fingerprint. */
    @get:Input
    abstract val promptVersion: String

    /** Not an input: switching modes must not make the task run again. */
    @get:Internal
    abstract val mode: Property<AiMode>

    @get:ServiceReference(RunAiService.NAME)
    abstract val service: Property<RunAiService>

    /**
     * The result of the task: what the prompt asks for. It is committed to the repository. Every runAi task writes
     * its result here and nothing else, so other tasks can use `outputDirectory` as an input without knowing the
     * task type.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Committed next to [outputDirectory]. Records the fingerprint of the inputs that produced the result. */
    @get:OutputFile
    abstract val lockFile: RegularFileProperty

    /** Every request and answer of the last run that called the model. Not committed and not an output. */
    @get:Internal
    abstract val transcriptFile: RegularFileProperty

    /** Calls the model even when the committed result matches its inputs, for example after a compile error. */
    @get:Internal
    @get:Option(option = "regenerate", description = "Calls the model even if the committed result is up to date. Needs live mode.")
    abstract val regenerate: Property<Boolean>

    init {
        group = "runAi"
        regenerate.convention(false)
        onlyIf("runAi mode is not OFF") { (it as AbstractRunAi).mode.get() != AiMode.OFF }
        outputs.upToDateWhen { !(it as AbstractRunAi).regenerate.get() }
    }

    @TaskAction
    fun execute() {
        val currentFingerprint = Fingerprint()
            .put("model", model.get())
            .put("effort", effort.get())
            .put("promptVersion", promptVersion)
            .also { fingerprint(it) }
            .hash()
        val lock = lockFile.get().asFile
        val outputDir = outputDirectory.get().asFile

        val committed = AiLock.read(lock)
        // What the user reads, and what the model reads about the same situation.
        val (reason, modelReason) = when {
            regenerate.get() -> "--regenerate was given" to null
            committed == null -> "there is no committed result: ${lock.path} does not exist" to null
            committed.fingerprint != currentFingerprint ->
                "the inputs changed since ${lock.path} was written" to "its inputs changed"
            else -> {
                val problems = verify(outputDir)
                if (problems.isEmpty()) {
                    logger.info("runAi: the committed result of {} matches its inputs.", path)
                    return
                }
                val list = problems.joinToString("\n") { "  - $it" }
                "the committed result breaks these rules:\n$list" to "it breaks these rules:\n$list"
            }
        }
        // With --regenerate, the model starts over: the committed result may be what is wrong.
        previous = modelReason?.let { PreviousResult(FileAnswers.read(outputDir), it) }?.takeIf { it.files.isNotEmpty() }

        if (mode.get() != AiMode.LIVE) {
            throw GradleException(
                "runAi cannot use the committed result of '$path': $reason\n" +
                    "Run './gradlew $path -PrunAi.mode=live' to generate it, then commit ${outputDir.path} and ${lock.path}."
            )
        }

        logger.lifecycle("runAi: generating the result of {}, because {}", path, reason.lineSequence().first())
        regenerate(outputDir)
        AiLock(currentFingerprint, model.get(), promptVersion).write(lock, path)
    }

    private fun regenerate(outputDir: File) {
        // Generate into a staging directory, so a failure leaves the committed result as it was.
        val staging = temporaryDir.resolve("result")
        staging.deleteRecursively()
        staging.mkdirs()
        val transcript = Transcript(path)
        try {
            generate(StructuredRunner(service.get()::complete, transcript), staging)
        } catch (e: AiTaskFailedException) {
            throw GradleException(e.message ?: "runAi task failed.", e)
        } catch (e: AiBackendException) {
            throw GradleException(e.message ?: "Model request failed.", e)
        } finally {
            transcript.writeTo(transcriptFile.get().asFile)
        }
        outputDir.deleteRecursively()
        staging.copyRecursively(outputDir)
    }

    private var previous: PreviousResult? = null

    /**
     * While [generate] runs: the committed result that is being replaced, and why. Prompts show it, so the model
     * keeps whatever the changes do not affect. Null when there is no committed result, and with `--regenerate`.
     */
    protected fun previousResult(): PreviousResult? = previous

    /** Adds everything the model sees, other than the model, effort and prompt version, to [fingerprint]. */
    protected abstract fun fingerprint(fingerprint: Fingerprint)

    /**
     * Checks a committed result with fixed rules, for example after a hand edit. Returns the problems found; an
     * empty list means the result can be used.
     */
    protected abstract fun verify(outputDir: File): List<String>

    /** Asks the model through [runner] and writes the result into [outputDir], which is empty. */
    protected abstract fun generate(runner: StructuredRunner, outputDir: File)

    protected fun request(system: String, prompt: String, outputSchema: String): AiRequest = AiRequest(
        model = model.get(),
        effort = effort.get(),
        maxTokens = maxTokens.get(),
        system = system,
        prompt = prompt,
        outputSchema = outputSchema,
        refusalFallback = refusalFallback.get(),
    )
}
