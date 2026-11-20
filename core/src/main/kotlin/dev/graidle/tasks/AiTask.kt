package dev.graidle.tasks

import dev.graidle.ai.FileAnswers
import dev.graidle.ai.Fingerprint
import dev.graidle.ai.StructuredRunner
import dev.graidle.ai.SystemPrompt
import dev.graidle.ai.Validation
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A task that the build owner configures in a build script: instructions, input files, and the files the result
 * must contain.
 *
 * ```
 * val notes = tasks.register<AiTask>("releaseNotes") {
 *     instructions = file("ai/release-notes.md")
 *     inputFiles.from("CHANGES.txt")
 *     outputFiles = listOf("release-notes.md")
 * }
 * ```
 *
 * The model answers with the content of each declared file. The task writes only the declared files into
 * [outputDirectory]. Built-in checks require exactly the declared files, none of them empty. [checks] adds more.
 */
abstract class AiTask : AbstractAiTask() {

    /** A text file that says what the model should do. Part of the fingerprint. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val instructions: RegularFileProperty

    /**
     * The files the model sees. Directories are expanded. The model sees each file with its path relative to
     * [baseDirectory]. The paths and contents are part of the fingerprint.
     */
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    /** The base for the input file paths that the model sees. Defaults to the project directory. */
    @get:Internal
    abstract val baseDirectory: DirectoryProperty

    /**
     * Paths, relative to [outputDirectory], of the files the result must contain: no more and no less. Use `/` as
     * the separator.
     */
    @get:Input
    abstract val outputFiles: ListProperty<String>

    /** More rules for the result. Their problems go back to the model on the next attempt. */
    @get:Nested
    abstract val checks: ListProperty<AiResultCheck>

    /** The most bytes of input files to send, so a wide file pattern does not send a whole repository. */
    @get:Internal
    abstract val maxInputBytes: Property<Long>

    init {
        maxInputBytes.convention(500_000L)
    }

    override val promptVersion: String
        get() = "system-${SystemPrompt.VERSION}/files-$PROMPT_VERSION"

    override fun fingerprint(fingerprint: Fingerprint) {
        fingerprint.putText("instructions", instructions.get().asFile)
        fingerprint.put("outputFiles", declaredOutputs().joinToString("\n"))
        readInputs().forEach { (path, text) -> fingerprint.put("input:$path", text) }
    }

    override fun verify(outputDir: File): List<String> {
        val declared = declaredOutputs()
        val present = FileAnswers.read(outputDir)
        val problems = mutableListOf<String>()
        declared.filter { it !in present }.forEach { problems += "'$it' is missing" }
        present.keys.filter { it !in declared }.forEach { problems += "'$it' is not a declared output file" }
        declared.filter { present[it]?.isBlank() == true }.forEach { problems += "'$it' is empty" }
        return problems + runChecks(outputDir)
    }

    override fun generate(runner: StructuredRunner, outputDir: File) {
        val declared = declaredOutputs()
        val files = runner.run(
            label = path,
            request = request(SystemPrompt.TEXT, renderPrompt(declared), FileAnswers.schema(declared)),
            maxAttempts = maxAttempts.get(),
        ) { answer -> checkAnswer(answer, declared) }
        FileAnswers.write(files, outputDir)
    }

    private fun renderPrompt(declared: List<String>): String = buildString {
        append(instructions.get().asFile.readText().trim()).append("\n\n")
        val inputs = readInputs()
        if (inputs.isEmpty()) {
            append("There are no input files.\n\n")
        } else {
            append("Input files:\n\n")
            inputs.forEach { (path, text) ->
                append("<file path=\"").append(path).append("\">\n").append(text)
                if (!text.endsWith("\n")) append('\n')
                append("</file>\n\n")
            }
        }
        append("Answer with these files and no others: ").append(declared.joinToString(", ")).append(".\n")
        append(FileAnswers.CONTENT_INSTRUCTION).append('\n')
    }

    private fun checkAnswer(answer: String, declared: List<String>): Validation<Map<String, String>> {
        val files = when (val parsed = FileAnswers.parse(answer)) {
            is Validation.Valid -> parsed.value
            else -> return parsed
        }
        val problems = mutableListOf<String>()
        files.keys.filter { it !in declared }.forEach { problems += "'$it' is not a declared output file" }
        declared.filter { it !in files }.forEach { problems += "'$it' is missing" }
        if (problems.isNotEmpty()) return Validation.Invalid(problems)

        // Checks read files, so they run on a scratch copy of the answer.
        val scratch = temporaryDir.resolve("check")
        scratch.deleteRecursively()
        FileAnswers.write(files, scratch)
        val checkProblems = runChecks(scratch)
        return if (checkProblems.isEmpty()) Validation.Valid(files) else Validation.Invalid(checkProblems)
    }

    private fun runChecks(outputDir: File): List<String> = checks.get().flatMap { it.check(outputDir) }

    private fun declaredOutputs(): List<String> {
        val declared = outputFiles.get()
        if (declared.isEmpty()) throw GradleException("'$path' declares no output files. Set outputFiles.")
        declared.firstOrNull { !FileAnswers.isSafeRelativePath(it) }?.let {
            throw GradleException("'$path' has an invalid output file path '$it'. Use a relative path with '/' separators.")
        }
        if (declared.toSet().size != declared.size) throw GradleException("'$path' declares an output file more than once.")
        return declared
    }

    /** Input files as (relative path, text), sorted by path, so the prompt does not depend on file system order. */
    private fun readInputs(): List<Pair<String, String>> {
        val base = baseDirectory.get().asFile
        var total = 0L
        return inputFiles.asFileTree.files
            .map { file -> (file.relativeToOrNull(base)?.invariantSeparatorsPath ?: file.name) to file }
            .sortedBy { it.first }
            .map { (relativePath, file) ->
                val bytes = file.readBytes()
                total += bytes.size
                if (total > maxInputBytes.get()) {
                    throw GradleException(
                        "The input files of '$path' are larger than maxInputBytes (${maxInputBytes.get()} bytes). " +
                            "Narrow inputFiles, or raise maxInputBytes."
                    )
                }
                relativePath to decodeText(bytes, relativePath)
            }
    }

    private fun decodeText(bytes: ByteArray, relativePath: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw GradleException("The input file '$relativePath' of '$path' is not UTF-8 text.", e)
    }

    private companion object {
        /** Change when the prompt text, the schema or the built-in checks change. */
        const val PROMPT_VERSION = 1
    }
}
