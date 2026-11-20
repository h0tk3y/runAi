package dev.graidle.specs

import dev.graidle.ai.SystemPrompt

/**
 * Generates tests for a module from its specification, without seeing the implementation. Because the code and the
 * tests come from the same specification separately, running the tests checks the one against the other.
 */
abstract class KotlinTestsFromSpecTask : AbstractKotlinFromSpecTask() {

    override val promptVersion: String
        get() = "system-${SystemPrompt.VERSION}/spec-tests-$PROMPT_VERSION"

    override fun taskDescription(): String =
        """
        Write tests for a Kotlin/JVM module from its specification. Someone else implements the module separately from the same specification; your tests check that the implementation follows the specification.

        Rules:
        1. Test only the public API in the "API" section. Do not assume any other declarations.
        2. Write at least one test for every rule in the specification, including the error cases. Name each test after the rule it checks.
        3. Use kotlin.test: kotlin.test.Test, assertEquals, assertFailsWith and the other kotlin.test functions. Do not use other test libraries.
        4. You can also use the Kotlin standard library, the Java standard library, and what the API sections of the dependency modules declare.
        5. Put the tests in the package of the module, in files whose names end in Test.kt, at the path that matches the package: a/b/NameTest.kt.
        """.trimIndent()

    override fun checkSources(files: Map<String, String>, own: SpecApi, dependencies: Set<String>): List<String> =
        KotlinSourceChecks.checkFiles(files, own.packages, dependencies) +
            files.filter { (path, content) -> path.endsWith(".kt") && "@Test" !in content }.keys
                .map { "'$it' contains no @Test function" }

    private companion object {
        /** Change when the prompt text or the checks change. */
        const val PROMPT_VERSION = 4
    }
}
