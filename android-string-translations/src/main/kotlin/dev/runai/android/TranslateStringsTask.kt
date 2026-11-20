package dev.runai.android

import dev.runai.ai.Fingerprint
import dev.runai.ai.StructuredRunner
import dev.runai.ai.SystemPrompt
import dev.runai.tasks.AbstractRunAi
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File

/**
 * Translates the `<string>` and `<plurals>` resources of one `strings.xml` into one locale.
 *
 * Writes `values-<targetLocale>/strings.xml` under [outputDirectory], so the directory can be used as an Android
 * resource directory as it is.
 */
abstract class TranslateStringsTask : AbstractRunAi() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceStrings: RegularFileProperty

    /** BCP 47 tag, for example `en`. */
    @get:Input
    abstract val sourceLanguage: Property<String>

    /** Android resource qualifier, for example `de`, `pt-rBR` or `b+sr+Latn`. */
    @get:Input
    abstract val targetLocale: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val glossary: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val instructions: Property<String>

    @get:Input
    @get:Optional
    abstract val localeInstructions: Property<String>

    /** How many resources go into one request. */
    @get:Input
    abstract val chunkSize: Property<Int>

    override val promptVersion: String
        get() = "system-${SystemPrompt.VERSION}/translate-${TranslationPrompt.VERSION}"

    override fun fingerprint(fingerprint: Fingerprint) {
        // The parsed resources, not the file: comments, formatting and non-translatable strings do not count.
        fingerprint
            .put("sourceStrings", prompt().canonicalInput(sourceEntries()))
            .put("sourceLanguage", sourceLanguage.get())
            .put("targetLocale", targetLocale.get())
            .putText("glossary", glossary.orNull?.asFile)
            .put("instructions", instructions.orNull)
            .put("localeInstructions", localeInstructions.orNull)
            .put("chunkSize", chunkSize.get().toString())
    }

    override fun verify(outputDir: File): List<String> =
        TranslationCheck.verify(sourceEntries(), translationFile(outputDir))

    override fun generate(runner: StructuredRunner, outputDir: File) {
        val locale = targetLocale.get()
        val prompt = prompt()
        val source = StringResourcesXml.parse(sourceStrings.get().asFile)
        val chunks = source.entries.chunked(chunkSize.get().coerceAtLeast(1))
        val previous = previousResult()
        val previousTranslations = previous?.files?.get("values-$locale/strings.xml")
            ?.let { runCatching { StringResourcesXml.parse(it).entries.associateBy { entry -> entry.name } }.getOrNull() }
            .orEmpty()

        val translated = mutableListOf<StringResource>()
        chunks.forEachIndexed { index, chunk ->
            translated += runner.run(
                label = "$locale, chunk ${index + 1} of ${chunks.size}",
                request = request(SystemPrompt.TEXT, prompt.render(chunk, previousTranslations, previous?.reason), TranslationPrompt.SCHEMA),
                maxAttempts = maxAttempts.get(),
            ) { answer -> prompt.check(chunk, answer) }
        }

        StringResourcesXml.write(StringResourceFile(source.namespaces, translated), translationFile(outputDir))
        logger.info("runAi: translated {} resource(s) to '{}'.", translated.size, locale)
    }

    private fun prompt() = TranslationPrompt(
        sourceLanguage = sourceLanguage.get(),
        targetQualifier = targetLocale.get(),
        glossary = glossary.orNull?.asFile?.readText(),
        instructions = listOfNotNull(instructions.orNull, localeInstructions.orNull)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { null },
    )

    private fun sourceEntries(): List<StringResource> = StringResourcesXml.parse(sourceStrings.get().asFile).entries

    private fun translationFile(outputDir: File) = File(outputDir, "values-${targetLocale.get()}/strings.xml")
}
