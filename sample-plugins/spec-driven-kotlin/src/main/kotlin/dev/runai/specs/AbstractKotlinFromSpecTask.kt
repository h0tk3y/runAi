package dev.runai.specs

import dev.runai.ai.FileAnswers
import dev.runai.ai.Fingerprint
import dev.runai.ai.StructuredRunner
import dev.runai.ai.SystemPrompt
import dev.runai.ai.Validation
import dev.runai.tasks.AbstractRunAi
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File

/**
 * Generates Kotlin files from the specification of this module and the specifications of the modules it depends on.
 * The model sees the specifications only: never the code of this module or of its dependencies.
 *
 * The model must not guess. When the specifications contradict each other or leave a decision open, it reports that
 * instead, and the task fails and asks a person to resolve it. See [SpecIssueReports].
 */
abstract class AbstractKotlinFromSpecTask : AbstractRunAi() {

    /** The Gradle path of this module, for example `:parser`. The model sees it. */
    @get:Input
    abstract val modulePath: Property<String>

    /** The Markdown files of this module's specification. */
    @get:InputDirectory
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specDirectory: DirectoryProperty

    /**
     * The specification directories of the dependencies, in the same order as [dependencyModules]. The plugin
     * resolves them from the `runai-spec` variants of the modules on the compile classpath.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencySpecDirectories: ConfigurableFileCollection

    /** The Gradle paths of the dependencies, in the same order as [dependencySpecDirectories]. */
    @get:Input
    abstract val dependencyModules: ListProperty<String>

    /** Optional instructions for this module, for example conventions of the project. */
    @get:Input
    @get:Optional
    abstract val instructions: Property<String>

    /**
     * The compile classpath: the libraries and dependency modules this code may use. The model explores their public
     * API with [ClasspathTools]. Only the API counts for the fingerprint, so a change in a dependency's method bodies
     * leaves the result up to date.
     */
    @get:CompileClasspath
    abstract val classpath: ConfigurableFileCollection

    private var index: ClasspathIndex? = null

    /** The public API of [classpath], read once per task run. */
    protected fun classpathIndex(): ClasspathIndex = index ?: ClasspathIndex.of(classpath.files.toList()).also { index = it }

    /** What the model must do, and the rules for this kind of file. Shown before the specifications. */
    protected abstract fun taskDescription(): String

    /** Problems of a set of generated files, on top of the shared checks. */
    protected abstract fun checkSources(files: Map<String, String>, own: SpecApi, dependencies: Set<String>): List<String>

    /** Settings of the subclass that change what the model is asked. */
    protected open fun fingerprintSettings(fingerprint: Fingerprint) {}

    final override fun fingerprint(fingerprint: Fingerprint) {
        ownApi()
        fingerprint.put("module", modulePath.get())
        fingerprint.put("instructions", instructions.orNull)
        ownSpec().files.forEach { (path, text) -> fingerprint.put("spec:$path", text) }
        dependencySpecs().forEach { dependency ->
            dependency.files.forEach { (path, text) -> fingerprint.put("dependency:${dependency.module}:$path", text) }
        }
        classpathIndex().entries.forEachIndexed { i, entry -> fingerprint.put("classpath:$i", entry.apiHash) }
        fingerprintSettings(fingerprint)
    }

    final override fun verify(outputDir: File): List<String> = check(FileAnswers.read(outputDir))

    final override fun generate(runner: StructuredRunner, outputDir: File) {
        val own = ownSpec()
        val dependencies = dependencySpecs()
        val schema = FileAnswers.schema(
            extraStatuses = SpecIssueReports.STATUSES,
            extraProperties = SpecIssueReports.SCHEMAS,
            withKeep = previousResult() != null,
        )
        val files = runner.run(
            label = path,
            request = request(SystemPrompt.TEXT, renderPrompt(own, dependencies), schema)
                .copy(tools = ClasspathTools.tools(classpathIndex())),
            maxAttempts = maxAttempts.get(),
        ) { answer ->
            val issues = SpecIssueReports.read(answer)
            if (issues != null) {
                val problems = SpecIssueReports.check(issues, own, dependencies)
                if (problems.isEmpty()) {
                    Validation.NeedsDecision(SpecIssueReports.message(path, issues, own, dependencies))
                } else {
                    Validation.Invalid(problems)
                }
            } else {
                when (val parsed = FileAnswers.parse(answer, previousResult()?.files.orEmpty())) {
                    is Validation.Valid -> check(parsed.value).let { if (it.isEmpty()) parsed else Validation.Invalid(it) }
                    else -> parsed
                }
            }
        }
        FileAnswers.write(files, outputDir)
    }

    private fun check(files: Map<String, String>): List<String> {
        // The code may import what the classpath has, and what dependency specs declare but is not built yet.
        val dependencyPackages = dependencySpecs().flatMap { SpecApi.parse(it.files.values.joinToString("\n")).packages }.toSet()
        return checkSources(files, ownApi(), dependencyPackages + classpathIndex().packages.keys)
    }

    private fun renderPrompt(own: ModuleSpecFiles, dependencies: List<ModuleSpecFiles>): String = buildString {
        append(taskDescription().trim()).append("\n\n")
        append("Module: ").append(own.module).append("\n\n")
        append("Specification of this module:\n\n")
        appendFiles(own.files)
        if (dependencies.isEmpty()) {
            append("This module has no dependency modules.\n\n")
        } else {
            append("Specifications of the dependency modules. Use only what their API sections declare:\n\n")
            dependencies.forEach { dependency ->
                append("<module path=\"").append(dependency.module).append("\">\n")
                appendFiles(dependency.files)
                append("</module>\n\n")
            }
            append(SpecIssueReports.CONTRADICTION_RULE).append("\n\n")
        }
        append(SpecIssueReports.UNCLEAR_RULE).append("\n\n")
        append(ClasspathTools.PROMPT).append("\n\n")
        instructions.orNull?.takeIf { it.isNotBlank() }?.let { append("Project instructions:\n").append(it.trim()).append("\n\n") }
        previousResult()?.let { append(it.renderForFiles()) }
        append("Answer with Kotlin source files. ").append(FileAnswers.CONTENT_INSTRUCTION).append('\n')
    }

    private fun StringBuilder.appendFiles(files: Map<String, String>) {
        files.forEach { (path, text) ->
            append("<file path=\"").append(path).append("\">\n").append(text)
            if (!text.endsWith("\n")) append('\n')
            append("</file>\n\n")
        }
    }

    protected fun ownSpec(): ModuleSpecFiles {
        val dir = specDirectory.get().asFile
        return ModuleSpecFiles(modulePath.get(), dir, FileAnswers.read(dir).filterKeys { it.endsWith(".md") })
    }

    protected fun ownApi(): SpecApi {
        val api = SpecApi.parse(ownSpec().files.values.joinToString("\n"))
        if (api.isEmpty || api.packages.isEmpty()) {
            throw GradleException(
                "The specification of ${modulePath.get()} in ${specDirectory.get().asFile} has no API section with a " +
                    "package. Add a '## API' heading followed by a ```kotlin block that declares the package and the " +
                    "public API of the module."
            )
        }
        return api
    }

    /** The specification of each dependency, sorted by module path. */
    protected fun dependencySpecs(): List<ModuleSpecFiles> =
        dependencyModules.get().zip(dependencySpecDirectories.files.toList())
            .map { (module, dir) -> ModuleSpecFiles(module, dir, FileAnswers.read(dir).filterKeys { it.endsWith(".md") }) }
            .sortedBy { it.module }
}
