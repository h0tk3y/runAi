package dev.graidle.specs

import dev.graidle.ai.Fingerprint
import dev.graidle.ai.SystemPrompt
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input

/** Generates the implementation of a module from its specification. The result is a Kotlin source directory. */
abstract class KotlinFromSpecTask : AbstractKotlinFromSpecTask() {

    /** Paths of files the result must contain, for example the file with `main`, so a build can name its class. */
    @get:Input
    abstract val requiredFiles: ListProperty<String>

    override val promptVersion: String
        get() = "system-${SystemPrompt.VERSION}/spec-code-$PROMPT_VERSION"

    override fun taskDescription(): String = buildString {
        append(
            """
            Implement a Kotlin/JVM module from its specification.

            Rules:
            1. Implement the public API in the "API" section exactly: the same packages, names, parameters, types and supertypes. Declare everything else `internal` or `private`.
            2. Implement every rule in the specification.
            3. Use only the Kotlin standard library, the Java standard library, and what the API sections of the dependency modules declare. Do not use other libraries.
            4. Put each file at the path that matches its package: a file in package a.b goes to a/b/Name.kt.
            5. Do not write tests or build scripts.
            """.trimIndent()
        )
        val required = requiredFiles.get()
        if (required.isNotEmpty()) append("\n6. Include these files: ").append(required.joinToString(", ")).append('.')
    }

    override fun fingerprintSettings(fingerprint: Fingerprint) {
        fingerprint.put("requiredFiles", requiredFiles.get().joinToString("\n"))
    }

    override fun checkSources(files: Map<String, String>, own: SpecApi, dependencies: Set<String>): List<String> =
        KotlinSourceChecks.checkFiles(files, own.packages, dependencies, forbiddenPrefixes = listOf("kotlin.test.")) +
            KotlinSourceChecks.missingDeclarations(files, own) +
            requiredFiles.get().filter { it !in files }.map { "'$it' is missing" }

    private companion object {
        /** Change when the prompt text or the checks change. */
        const val PROMPT_VERSION = 4
    }
}
