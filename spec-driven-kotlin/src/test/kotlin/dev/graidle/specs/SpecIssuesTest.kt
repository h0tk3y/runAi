package dev.graidle.specs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class SpecIssuesTest {

    private val greeting = ModuleSpecFiles(
        ":greeting",
        File("/work/project/greeting/src/main/spec"),
        mapOf("greeting.md" to "## Behavior\n\n1. `greet` returns \"Hello, \" followed by the name\n   and \"!\".\n"),
    )
    private val app = ModuleSpecFiles(
        ":app",
        File("/work/project/app/src/main/spec"),
        mapOf("app.md" to "## Behavior\n\n1. `welcomeAll` returns \"Hi, \" followed by each name, using `greet`.\n2. Names are sorted.\n"),
    )

    private val contradiction = SpecContradiction(
        dependencyModule = ":greeting",
        // Without the code marks and the line break of the spec, which the check ignores.
        dependencyQuote = "greet returns \"Hello, \" followed by the name and \"!\"",
        ownQuote = "`welcomeAll` returns \"Hi, \" followed by each name, using `greet`.",
        explanation = "greet cannot produce \"Hi, \" when it always starts with \"Hello, \".",
    )
    private val unclear = SpecUnclearPoint(":app", "Names are sorted.", "Sorted how: by Unicode code point, or by the rules of a locale?")

    @Test
    fun `reads reports, and ignores answers without issues`() {
        val report = """{"status":"unclear","reason":"","files":[],"contradictions":[],
            "unclear":[{"module":":app","quote":"q","question":"why?"}]}"""

        assertThat(SpecIssueReports.read(report)).isEqualTo(SpecIssues(emptyList(), listOf(SpecUnclearPoint(":app", "q", "why?"))))
        assertThat(SpecIssueReports.read("""{"status":"ok","reason":"","files":[],"contradictions":[],"unclear":[]}""")).isNull()
        assertThat(SpecIssueReports.read("""{"status":"ok","reason":"","files":[]}""")).isNull()
        assertThat(SpecIssueReports.read("not json")).isNull()
    }

    @Test
    fun `accepts quotes that appear in the specs they name`() {
        assertThat(SpecIssueReports.check(SpecIssues(listOf(contradiction), listOf(unclear)), app, listOf(greeting))).isEmpty()
    }

    @Test
    fun `rejects invented quotes, unknown modules, short quotes and empty reports`() {
        val invented = contradiction.copy(dependencyQuote = "greet returns \"Good morning\" for every name")
        val unknown = contradiction.copy(dependencyModule = ":format")
        val short = unclear.copy(quote = "sorted")
        val elsewhere = unclear.copy(module = ":other")

        assertThat(SpecIssueReports.check(SpecIssues(listOf(invented, unknown), listOf(short, elsewhere)), app, listOf(greeting))).containsExactly(
            "contradiction 1: the quote \"greet returns \"Good morning\" for every name\" does not appear in the " +
                "specification of :greeting. Quote it word for word.",
            "contradiction 2 names ':format', which is not a dependency module. Use one of: :greeting",
            "unclear point 1: quote a whole statement from the specification of :app, not \"sorted\"",
            "unclear point 2 names ':other'. Use this module or a dependency module: :app, :greeting",
        )
        assertThat(SpecIssueReports.check(SpecIssues(emptyList(), emptyList()), app, listOf(greeting)))
            .containsExactly("\"status\" reports an issue, but \"contradictions\" and \"unclear\" are empty")
    }

    @Test
    fun `the message links every quote to its file, line and column`() {
        val message = SpecIssueReports.message(":app:generateKotlinFromSpec", SpecIssues(listOf(contradiction), listOf(unclear)), app, listOf(greeting))

        assertThat(message).isEqualTo(
            """
            The specification of :app contradicts the specification of its dependency :greeting, and it is not clear enough to generate the result without guessing, so grAIdle did not generate anything.
            Resolve the points below in the specifications, then run ':app:generateKotlinFromSpec' again.

            1. Contradiction
               :greeting at file:///work/project/greeting/src/main/spec/greeting.md:3:4
                 "greet returns "Hello, " followed by the name and "!""
               :app at file:///work/project/app/src/main/spec/app.md:3:4
                 "`welcomeAll` returns "Hi, " followed by each name, using `greet`."
               Why they conflict: greet cannot produce "Hi, " when it always starts with "Hello, ".

            2. Unclear
               :app at file:///work/project/app/src/main/spec/app.md:4:4
                 "Names are sorted."
               Question: Sorted how: by Unicode code point, or by the rules of a locale?

            """.trimIndent()
        )
    }

    @Test
    fun `the first line says what was found`() {
        fun firstLine(issues: SpecIssues) = SpecIssueReports.message(":app:x", issues, app, listOf(greeting)).lineSequence().first()

        assertThat(firstLine(SpecIssues(listOf(contradiction), emptyList())))
            .isEqualTo("The specification of :app contradicts the specification of its dependency :greeting, so grAIdle did not generate anything.")
        assertThat(firstLine(SpecIssues(emptyList(), listOf(unclear))))
            .isEqualTo("The specification of :app is not clear enough to generate the result without guessing, so grAIdle did not generate anything.")
    }
}
