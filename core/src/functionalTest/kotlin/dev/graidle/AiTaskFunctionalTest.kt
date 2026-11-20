package dev.graidle

import dev.graidle.testing.FakeClaudeServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The build-owner path: an [dev.graidle.tasks.AiTask] configured in a build script, with no plugin code. */
class AiTaskFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val server = FakeClaudeServer()

    private fun answer(vararg files: Pair<String, String>): String {
        val list = files.joinToString(",") { (path, content) ->
            """{"path":"$path","content":"${content.replace("\n", "\\n")}"}"""
        }
        return """{"status":"ok","reason":"","files":[$list]}"""
    }

    private val goodAnswer = answer("summary.md" to "# Summary\n\nTwo documents about the release process.\n")
    private val result get() = projectDir.resolve("graidle/summarizeDocs/summary.md")
    private val buildFile get() = projectDir.resolve("build.gradle.kts")

    @BeforeEach
    fun setUp() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        buildFile.writeText(
            """
            import dev.graidle.tasks.AiTask

            plugins { id("dev.graidle") }

            graidle {
                apiKey = "test-key"
                baseUrl = "${server.url}"
            }

            val summarizeDocs = tasks.register<AiTask>("summarizeDocs") {
                instructions = file("ai/summarize.md")
                inputFiles.from("docs")
                outputFiles = listOf("summary.md")
            }

            tasks.register<Sync>("site") {
                from(summarizeDocs.flatMap { it.outputDirectory })
                into(layout.buildDirectory.dir("site"))
            }
            """.trimIndent()
        )
        projectDir.resolve("ai").mkdirs()
        projectDir.resolve("ai/summarize.md").writeText("Summarize the documents in one short Markdown page with a heading.")
        projectDir.resolve("docs").mkdirs()
        projectDir.resolve("docs/release.md").writeText("Releases happen every second Tuesday.")
        projectDir.resolve("docs/hotfix.md").writeText("Hotfixes skip the release train.")
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String, configurationCache: Boolean = true) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, if (configurationCache) "--configuration-cache" else "--no-configuration-cache", "--stacktrace")

    @Test
    fun `live mode writes the declared files and a lock file`() {
        server.enqueue(goodAnswer)

        val build = run("summarizeDocs", "-Pgraidle.mode=live")

        assertThat(build.task(":summarizeDocs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.readText()).startsWith("# Summary")
        assertThat(projectDir.resolve("graidle/summarizeDocs.lock")).exists()
        val body = server.requests.single().body
        assertThat(body).contains(
            "Summarize the documents in one short Markdown page with a heading.",
            // Sorted by path, so the prompt does not depend on file system order.
            """<file path=\"docs/hotfix.md\">\nHotfixes skip the release train.\n</file>\n\n<file path=\"docs/release.md\">""",
            """"enum":["summary.md"]""",
        )
    }

    @Test
    fun `other tasks use the output directory, and replay mode uses the committed result`() {
        server.enqueue(goodAnswer)
        run("summarizeDocs", "-Pgraidle.mode=live")
        projectDir.resolve("build").deleteRecursively()
        projectDir.resolve(".gradle").deleteRecursively()

        val build = run("site", "-Pgraidle.mode=replay")

        assertThat(build.task(":summarizeDocs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/site/summary.md").readText()).startsWith("# Summary")
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `a changed input file makes the result out of date`() {
        server.enqueue(goodAnswer)
        run("summarizeDocs", "-Pgraidle.mode=live")
        projectDir.resolve("docs/hotfix.md").writeText("Hotfixes need two approvals.")

        val build = runAndFail("summarizeDocs", "-Pgraidle.mode=replay")

        assertThat(build.output).contains("grAIdle cannot use the committed result of ':summarizeDocs': the inputs changed since")
    }

    @Test
    fun `changing the model makes the committed result out of date`() {
        assertChangeMakesResultStale("graidle { model = \"claude-haiku-5-5\" }", "\"model\":\"claude-haiku-5-5\"")
        assertThat(projectDir.resolve("graidle/summarizeDocs.lock").readText()).contains("model=claude-haiku-5-5")
    }

    @Test
    fun `changing the model of one task makes its committed result out of date`() {
        assertChangeMakesResultStale("summarizeDocs { model = \"claude-sonnet-5-5\" }", "\"model\":\"claude-sonnet-5-5\"")
    }

    @Test
    fun `changing the effort makes the committed result out of date`() {
        assertChangeMakesResultStale("graidle { effort = \"high\" }", "\"effort\":\"high\"")
    }

    /**
     * Generates a result, applies [change] to the build script, and checks that Gradle runs the task again, that
     * replay mode rejects the committed result, and that live mode sends a new request that contains [expectedInRequest].
     */
    private fun assertChangeMakesResultStale(change: String, expectedInRequest: String) {
        server.enqueue(goodAnswer)
        run("summarizeDocs", "-Pgraidle.mode=live")
        val upToDate = run("summarizeDocs", "-Pgraidle.mode=replay")
        assertThat(upToDate.task(":summarizeDocs")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        buildFile.appendText("\n$change\n")
        val replay = runAndFail("summarizeDocs", "-Pgraidle.mode=replay")

        assertThat(replay.output).contains("grAIdle cannot use the committed result of ':summarizeDocs': the inputs changed since")

        server.enqueue(goodAnswer)
        val live = run("summarizeDocs", "-Pgraidle.mode=live")

        assertThat(live.task(":summarizeDocs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[0].body).doesNotContain(expectedInRequest)
        assertThat(server.requests[1].body).contains(expectedInRequest)
    }

    @Test
    fun `--regenerate calls the model again in live mode and fails in replay mode`() {
        server.enqueue(goodAnswer, answer("summary.md" to "# Summary\n\nRegenerated.\n"))
        run("summarizeDocs", "-Pgraidle.mode=live")

        val live = run("summarizeDocs", "--regenerate", "-Pgraidle.mode=live")

        assertThat(live.task(":summarizeDocs")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(live.output).contains("grAIdle: generating the result of :summarizeDocs, because --regenerate was given")
        assertThat(result.readText()).contains("Regenerated.")

        val replay = runAndFail("summarizeDocs", "--regenerate", "-Pgraidle.mode=replay")

        assertThat(replay.output).contains("grAIdle cannot use the committed result of ':summarizeDocs': --regenerate was given")
        assertThat(server.requests).hasSize(2)
    }

    @Test
    fun `asks again when the answer has the wrong files`() {
        server.enqueue(answer("notes.md" to "# Notes"), goodAnswer)

        run("summarizeDocs", "-Pgraidle.mode=live")

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("'notes.md' is not a declared output file", "'summary.md' is missing")
        assertThat(projectDir.resolve("graidle/summarizeDocs").list()).containsExactly("summary.md")
    }

    @Test
    fun `custom checks run on answers and on committed results`() {
        buildFile.appendText(
            """

            class StartsWithHeading : dev.graidle.tasks.AiResultCheck {
                override fun check(outputDir: File): List<String> =
                    if (outputDir.resolve("summary.md").readText().startsWith("# ")) emptyList()
                    else listOf("summary.md must start with a '# ' heading")
            }

            summarizeDocs { checks.add(StartsWithHeading()) }
            """.trimIndent()
        )
        server.enqueue(answer("summary.md" to "Two documents."), goodAnswer)

        // TestKit's injected plugin classpath cannot load a build script class when the configuration cache is
        // reused (NoClassDefFoundError). With the plugin from an included build or a repository, reuse works.
        runner("summarizeDocs", "-Pgraidle.mode=live", configurationCache = false).build()

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("summary.md must start with a '# ' heading")

        result.writeText("No heading any more.")
        val build = runner("summarizeDocs", "-Pgraidle.mode=replay", configurationCache = false).buildAndFail()

        assertThat(build.output).contains("the committed result breaks these rules:", "summary.md must start with a '# ' heading")
    }

    @Test
    fun `rejects an output path outside the output directory`() {
        buildFile.appendText("\nsummarizeDocs { outputFiles = listOf(\"../escape.md\") }\n")

        val build = runAndFail("summarizeDocs", "-Pgraidle.mode=live")

        assertThat(build.output).contains("':summarizeDocs' has an invalid output file path '../escape.md'")
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun `refuses to send more input than maxInputBytes`() {
        buildFile.appendText("\nsummarizeDocs { maxInputBytes = 10L }\n")

        val build = runAndFail("summarizeDocs", "-Pgraidle.mode=live")

        assertThat(build.output).contains("The input files of ':summarizeDocs' are larger than maxInputBytes (10 bytes)")
        assertThat(server.requests).isEmpty()
    }
}
