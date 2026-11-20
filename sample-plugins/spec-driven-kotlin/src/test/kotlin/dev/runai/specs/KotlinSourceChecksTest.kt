package dev.runai.specs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KotlinSourceChecksTest {

    private val own = setOf("demo.parser")
    private val dependencies = setOf("demo.ast", "demo.lexer")

    @Test
    fun `accepts files in the module package that import allowed packages`() {
        val files = mapOf(
            "demo/parser/Parser.kt" to """
                package demo.parser

                import demo.ast.Expr
                import demo.lexer.*
                import kotlin.math.max
                import java.util.ArrayDeque as Deque
            """.trimIndent(),
            "demo/parser/internal/Precedence.kt" to "package demo.parser.internal\n",
        )

        assertThat(KotlinSourceChecks.checkFiles(files, own, dependencies)).isEmpty()
    }

    @Test
    fun `reports paths, packages and imports that break the rules`() {
        val files = mapOf(
            "Parser.kt" to "package demo.parser\n",
            "demo/other/Other.kt" to "package demo.other\nimport com.google.gson.Gson\n",
            "demo/parser/README.md" to "# Notes",
            "demo/parser/NoPackage.kt" to "fun x() = 1\n",
        )

        assertThat(KotlinSourceChecks.checkFiles(files, own, dependencies)).containsExactly(
            "'Parser.kt' must be in the directory 'demo/parser', because its package is 'demo.parser'",
            "'demo/other/Other.kt' uses the package 'demo.other'; use a package from the API section: demo.parser",
            "'demo/other/Other.kt': the import of com.google.gson.Gson is not allowed. Use only the Kotlin and Java " +
                "standard libraries and the API of the dependency modules.",
            "'demo/parser/README.md' is not a Kotlin file: its name must end in .kt",
            "'demo/parser/NoPackage.kt' has no package declaration",
        )
    }

    @Test
    fun `forbidden prefixes win over allowed ones`() {
        val files = mapOf("demo/parser/P.kt" to "package demo.parser\nimport kotlin.test.assertEquals\nimport kotlin.test.*\n")

        assertThat(KotlinSourceChecks.checkFiles(files, own, dependencies, forbiddenPrefixes = listOf("kotlin.test.")))
            .hasSize(2)
    }

    @Test
    fun `reports API declarations that the code does not declare`() {
        val api = SpecApi.of("package demo.parser\n\nclass ParseException(val position: Int)\nfun parse(text: String): Int")
        val files = mapOf("demo/parser/Parser.kt" to "package demo.parser\n\nfun parse(text: String): Int = text.length\n")

        assertThat(KotlinSourceChecks.missingDeclarations(files, api))
            .containsExactly("'ParseException' from the API section is not declared", "'position' from the API section is not declared")
    }
}
