package dev.graidle.android

import dev.graidle.testing.FakeClaudeServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.ZipFile

class TranslateStringsFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val server = FakeClaudeServer()

    private val goodAnswer = """
        {"status":"ok","reason":"",
         "strings":[{"name":"greeting","value":"Hallo, %1${'$'}s!"},{"name":"welcome","value":"Willkommen bei <b>Demo</b>"}],
         "plurals":[{"name":"files","items":[{"quantity":"one","value":"%d Datei"},{"quantity":"other","value":"%d Dateien"}]}]}
    """.trimIndent()

    private val sourceStrings get() = projectDir.resolve("src/main/res/values/strings.xml")
    private val result get() = projectDir.resolve("graidle/translations/de/values-de/strings.xml")
    private val lock get() = projectDir.resolve("graidle/translations/de.lock")

    @BeforeEach
    fun setUp() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("dev.graidle.android-string-translations") }

            graidle {
                apiKey = "test-key"
                baseUrl = "${server.url}"
            }

            androidStringTranslations {
                locales("de")
            }
            """.trimIndent()
        )
        sourceStrings.parentFile.mkdirs()
        sourceStrings.writeText(
            """
            <resources>
                <string name="app_name" translatable="false">Demo</string>
                <string name="greeting">Hello, %1${'$'}s!</string>
                <string name="welcome">Welcome to <b>Demo</b></string>
                <plurals name="files">
                    <item quantity="one">%d file</item>
                    <item quantity="other">%d files</item>
                </plurals>
            </resources>
            """.trimIndent()
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--configuration-cache", "--stacktrace")

    /** Keeps what a clone has: sources and the committed `graidle` directory. */
    private fun freshCheckout() {
        projectDir.resolve("build").deleteRecursively()
        projectDir.resolve(".gradle").deleteRecursively()
    }

    private fun generate() {
        server.enqueue(goodAnswer)
        run("translateStrings", "-Pgraidle.mode=live")
    }

    /** The test process environment without the variables that mark a CI build. */
    private fun environmentOutsideCi(): Map<String, String> = System.getenv().filterKeys {
        it !in listOf("CI", "JENKINS_URL", "TEAMCITY_VERSION", "TF_BUILD", "CODEBUILD_BUILD_ID")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CI", "JENKINS_URL", "TEAMCITY_VERSION", "TF_BUILD", "CODEBUILD_BUILD_ID"])
    fun `defaults to replay mode on CI`(variable: String) {
        val build = runner("translateStrings").withEnvironment(environmentOutsideCi() + (variable to "true")).buildAndFail()

        assertThat(build.output).contains("grAIdle cannot use the committed result of ':translateStringsDe': there is no committed result")
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun `defaults to live mode outside CI`() {
        server.enqueue(goodAnswer)

        val build = runner("translateStrings").withEnvironment(environmentOutsideCi() + ("CI" to "false")).build()

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `live mode writes the result and a lock file into the project`() {
        server.enqueue(goodAnswer)

        val build = run("translateStrings", "-Pgraidle.mode=live")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(build.output).contains(
            "grAIdle: generating the result of :translateStringsDe, because there is no committed result",
            "grAIdle: 1 model request(s), 120 input tokens, 40 output tokens.",
        )
        assertThat(result.readText()).contains(
            """<string name="greeting">Hallo, %1${'$'}s!</string>""",
            """<string name="welcome">Willkommen bei <b>Demo</b></string>""",
            """<item quantity="other">%d Dateien</item>""",
        ).doesNotContain("app_name")
        assertThat(lock.readText()).containsPattern("fingerprint=[0-9a-f]{64}").contains("model=claude-haiku-5-5")
        assertThat(projectDir.resolve("build/graidle/transcripts/translateStringsDe.json")).exists()
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `replay mode uses the committed result without calling the model`() {
        generate()
        freshCheckout()

        val first = run("translateStrings", "-Pgraidle.mode=replay")

        assertThat(first.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(first.output).doesNotContain("model request(s)")

        val second = run("translateStrings", "-Pgraidle.mode=replay")

        assertThat(second.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(second.output).contains("Configuration cache entry reused")
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `replay mode fails when the inputs changed`() {
        generate()
        sourceStrings.writeText(sourceStrings.readText().replace("</resources>", "    <string name=\"bye\">Bye</string>\n</resources>"))

        val build = runAndFail("translateStrings", "-Pgraidle.mode=replay")

        assertThat(build.output).contains(
            "grAIdle cannot use the committed result of ':translateStringsDe': the inputs changed since",
            "Run './gradlew :translateStringsDe -Pgraidle.mode=live' to generate it",
        )
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `replay mode fails when there is no committed result`() {
        val build = runAndFail("translateStrings", "-Pgraidle.mode=replay")

        assertThat(build.output).contains("grAIdle cannot use the committed result of ':translateStringsDe': there is no committed result")
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun `comments, formatting and non-translatable strings do not make the result stale`() {
        generate()
        sourceStrings.writeText(
            sourceStrings.readText()
                .replace("<resources>", "<resources>\n    <!-- Reviewed by the UX team -->\n    <string name=\"build_id\" translatable=\"false\">42</string>")
                .replace("    ", "  ")
        )

        val build = run("translateStrings", "-Pgraidle.mode=replay")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `a hand edit that follows the rules is kept`() {
        generate()
        result.writeText(result.readText().replace("Hallo, %1\$s!", "Servus, %1\$s!"))

        val build = run("translateStrings", "-Pgraidle.mode=replay")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.readText()).contains("Servus, %1\$s!")
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun `a hand edit that breaks a rule fails in replay mode and is replaced in live mode`() {
        generate()
        result.writeText(result.readText().replace("Hallo, %1\$s!", "Hallo!"))

        val failed = runAndFail("translateStrings", "-Pgraidle.mode=replay")

        assertThat(failed.output).contains(
            "the committed result breaks these rules:",
            "string 'greeting': format specifiers must be [%1\$s], found []",
        )

        server.enqueue(goodAnswer)
        run("translateStrings", "-Pgraidle.mode=live")

        assertThat(result.readText()).contains("Hallo, %1\$s!")
        assertThat(server.requests).hasSize(2)
    }

    @Test
    fun `a changed input calls the model again in live mode`() {
        generate()
        val oldLock = lock.readText()
        projectDir.resolve("build.gradle.kts").appendText("\nandroidStringTranslations { instructions = \"Use formal German.\" }\n")
        server.enqueue(goodAnswer)

        val build = run("translateStrings", "-Pgraidle.mode=live")

        assertThat(build.output).contains("because the inputs changed since")
        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("Use formal German.")
        assertThat(lock.readText()).isNotEqualTo(oldLock)
        // The previous translations come with the request, so the model keeps those that still fit.
        assertThat(server.requests[0].body).doesNotContain("Previous translations")
        assertThat(server.requests[1].body).contains(
            "The task translated these resources before. They must be translated again, because its inputs changed",
            "Keep each previous translation exactly as it is if it still translates the current source text correctly.",
            "Previous translations:",
            "Hallo, %1${'$'}s!",
        )
    }

    @Test
    fun `sends a structured output request with the default model, Haiku, and no refusal fallback`() {
        generate()

        val request = server.requests.single()
        assertThat(request.body).contains(
            "\"model\":\"claude-haiku-5-5\"",
            "\"output_config\"",
            "\"effort\":\"medium\"",
            "\"json_schema\"",
        ).doesNotContain("\"fallbacks\"")
        assertThat(request.betaHeader).isNull()
    }

    @Test
    fun `asks for a refusal fallback on models that have one`() {
        projectDir.resolve("build.gradle.kts").appendText("\ngraidle { model = \"claude-opus-5-5\" }\n")
        generate()

        val request = server.requests.single()
        assertThat(request.body).contains("\"model\":\"claude-opus-5-5\"", "\"fallbacks\":\"default\"")
        assertThat(request.betaHeader).contains("server-side-fallback-2026-07-01")
    }

    @Test
    fun `asks again when an answer breaks a rule`() {
        server.enqueue(goodAnswer.replace("Hallo, %1\$s!", "Hallo!"), goodAnswer)

        val build = run("translateStrings", "-Pgraidle.mode=live")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("format specifiers must be [%1\$s], found []")
        assertThat(result.readText()).contains("Hallo, %1\$s!")
    }

    @Test
    fun `a failed generation keeps the committed result and lock`() {
        generate()
        val committedResult = result.readText()
        val committedLock = lock.readText()
        sourceStrings.writeText(sourceStrings.readText().replace("Hello", "Hi"))
        server.enqueue("""{"status":"cannot_complete","reason":"the source text is ambiguous","strings":[],"plurals":[]}""")

        val build = runAndFail("translateStrings", "-Pgraidle.mode=live")

        assertThat(build.output).contains("The model could not complete 'de, chunk 1 of 1': the source text is ambiguous")
        assertThat(result.readText()).isEqualTo(committedResult)
        assertThat(lock.readText()).isEqualTo(committedLock)
    }

    @Test
    fun `off mode skips the task`() {
        val build = run("translateStrings", "-Pgraidle.mode=off")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun `other tasks use the output directory of any AI task as an input`() {
        projectDir.resolve("build.gradle.kts").appendText(
            """

            // Generic: no knowledge of the task type, and no explicit dependsOn.
            tasks.register<Zip>("bundleAiResults") {
                from(tasks.withType<dev.graidle.tasks.AbstractAiTask>().map { it.outputDirectory })
                archiveFileName = "ai-results.zip"
                destinationDirectory = layout.buildDirectory.dir("dist")
            }
            """.trimIndent()
        )
        generate()
        freshCheckout()

        val build = run("bundleAiResults", "-Pgraidle.mode=replay")

        assertThat(build.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(build.task(":bundleAiResults")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val entries = ZipFile(projectDir.resolve("build/dist/ai-results.zip")).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }
        // The result only: the lock file sits next to the output directory, and the transcript is in the build directory.
        assertThat(entries).containsExactly("values-de/strings.xml")

        val again = run("bundleAiResults", "-Pgraidle.mode=replay")

        assertThat(again.task(":translateStringsDe")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(again.task(":bundleAiResults")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(server.requests).hasSize(1)
    }
}
