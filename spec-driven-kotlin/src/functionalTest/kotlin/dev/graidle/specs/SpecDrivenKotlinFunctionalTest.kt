package dev.graidle.specs

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

/**
 * Two Kotlin modules built from specs with the real Kotlin Gradle plugin: `:app` depends on `:greeting`, and sees
 * its specification through the `graidle-spec` variant.
 */
class SpecDrivenKotlinFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val server = FakeClaudeServer()
    private val kotlinVersion = System.getProperty("graidle.test.kotlinVersion")

    private val greetingCode = files(
        "demo/greeting/Greeting.kt" to "package demo.greeting\n\nfun greet(name: String): String = \"Hello, \" + name + \"!\"\n"
    )
    private val greetingTests = files(
        "demo/greeting/GreetingTest.kt" to """
            package demo.greeting

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class GreetingTest {
                @Test
                fun rule1GreetsByName() {
                    assertEquals("Hello, Ada!", greet("Ada"))
                }
            }
        """.trimIndent()
    )
    private val appCode = files(
        "demo/app/App.kt" to """
            package demo.app

            import demo.greeting.greet

            fun welcomeAll(names: List<String>): List<String> = names.map { greet(it) }
        """.trimIndent()
    )
    private val appTests = files(
        "demo/app/AppTest.kt" to """
            package demo.app

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class AppTest {
                @Test
                fun rule1GreetsEveryName() {
                    assertEquals(listOf("Hello, Ada!", "Hello, Bob!"), welcomeAll(listOf("Ada", "Bob")))
                }
            }
        """.trimIndent()
    )

    private val greetingSpec get() = projectDir.resolve("greeting/src/main/spec/greeting.md")

    @BeforeEach
    fun setUp() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "fixture"
            include(":greeting", ":app")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText("""plugins { kotlin("jvm") version "$kotlinVersion" apply false }""")
        module("greeting", dependencies = "")
        module("app", dependencies = "implementation(project(\":greeting\"))")
        greetingSpec.writeText(
            """
            # Greeting

            ## API

            ```kotlin
            package demo.greeting

            fun greet(name: String): String
            ```

            ## Behavior

            1. `greet` returns "Hello, " followed by the name and "!".
            """.trimIndent()
        )
        projectDir.resolve("app/src/main/spec/app.md").writeText(
            """
            # App

            ## API

            ```kotlin
            package demo.app

            fun welcomeAll(names: List<String>): List<String>
            ```

            ## Behavior

            1. `welcomeAll` greets every name with `greet` from the greeting module, in order.
            """.trimIndent()
        )
    }

    private fun module(name: String, dependencies: String) {
        projectDir.resolve("$name/src/main/spec").mkdirs()
        projectDir.resolve("$name/build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("dev.graidle.spec-driven-kotlin")
            }

            dependencies {
                $dependencies
                testImplementation(kotlin("test"))
            }

            tasks.test { useJUnitPlatform() }

            graidle {
                apiKey = "test-key"
                baseUrl = "${server.url}"
            }
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

    private fun answerAll() {
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        server.answerWhen(TESTS, "Module: :greeting", answers = listOf(greetingTests))
        server.answerWhen(CODE, "Module: :app", answers = listOf(appCode))
        server.answerWhen(TESTS, "Module: :app", answers = listOf(appTests))
    }

    private fun requestFor(kind: String, module: String) = requestsFor(kind, module).single()

    private fun requestsFor(kind: String, module: String) =
        server.requests.filter { kind in it.body && "Module: $module" in it.body }.map { it.body }

    @Test
    fun `generates code and tests from the specs, and builds and tests them`() {
        answerAll()

        val build = run("build", "-Pgraidle.mode=live")

        for (module in listOf(":greeting", ":app")) {
            assertThat(build.task("$module:generateKotlinFromSpec")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(build.task("$module:generateTestsFromSpec")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(build.task("$module:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        }
        assertThat(projectDir.resolve("greeting/graidle/main/kotlin/demo/greeting/Greeting.kt")).exists()
        assertThat(projectDir.resolve("greeting/graidle/main/kotlin.lock")).exists()
        assertThat(projectDir.resolve("app/graidle/test/kotlin/demo/app/AppTest.kt")).exists()
        assertThat(server.requests).hasSize(4)

        // :app sees the specification of :greeting through the variant, and never its implementation.
        assertThat(requestFor(CODE, ":app"))
            .contains("""<module path=\":greeting\">""", "fun greet(name: String): String\\n")
            .doesNotContain("\\\"Hello, \\\" + name")
        assertThat(requestFor(TESTS, ":app")).contains("""<module path=\":greeting\">""")
        assertThat(requestFor(CODE, ":greeting")).contains("This module has no dependency modules.")
        // The tests are written from the specification, not from the code.
        assertThat(requestFor(TESTS, ":greeting")).doesNotContain("\\\"Hello, \\\" + name")
    }

    @Test
    fun `a fresh clone builds and tests in replay mode without the model`() {
        answerAll()
        run("build", "-Pgraidle.mode=live")
        listOf("build", ".gradle", "greeting/build", "app/build").forEach { projectDir.resolve(it).deleteRecursively() }

        val build = run("build", "-Pgraidle.mode=replay")

        assertThat(build.task(":app:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(server.requests).hasSize(4)
    }

    @Test
    fun `dependent modules follow the specs of their dependencies, not their code`() {
        answerAll()
        run("generateFromSpec", "-Pgraidle.mode=live")

        // A change in the code of :greeting leaves :app up to date.
        val greetingCodeFile = projectDir.resolve("greeting/graidle/main/kotlin/demo/greeting/Greeting.kt")
        greetingCodeFile.appendText("\n// Reviewed.\n")
        run(":app:generateKotlinFromSpec", "-Pgraidle.mode=replay")

        // A change in the specification of :greeting makes :app out of date, even when the code of :greeting
        // stays the same. :app compiles against :greeting, so :greeting is brought up to date first.
        greetingSpec.appendText("\n2. A blank name gives \"Hello!\".\n")
        server.answerWhen(
            CODE, "Module: :greeting",
            answers = listOf("""{"status":"ok","reason":"","files":[{"path":"demo/greeting/Greeting.kt","content":"","keep":true}]}"""),
        )
        run(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")
        val build = runAndFail(":app:generateKotlinFromSpec", "-Pgraidle.mode=replay")

        assertThat(build.output).contains("grAIdle cannot use the committed result of ':app:generateKotlinFromSpec': the inputs changed since")
    }

    @Test
    fun `asks again when the code imports a library that is not allowed`() {
        val withGson = files(
            "demo/greeting/Greeting.kt" to "package demo.greeting\n\nimport com.google.gson.Gson\n\nfun greet(name: String): String = Gson().toJson(name)\n"
        )
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(withGson, greetingCode))

        run(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("the import of com.google.gson.Gson is not allowed")
    }

    @Test
    fun `asks again when the code does not declare the API`() {
        val wrongName = files("demo/greeting/Greeting.kt" to "package demo.greeting\n\nfun hello(name: String): String = name\n")
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(wrongName, greetingCode))

        run(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")

        assertThat(server.requests).hasSize(2)
        assertThat(server.requests[1].body).contains("'greet' from the API section is not declared")
    }

    @Test
    fun `stops and asks a person to resolve a contradiction between specs`() {
        val appSpec = projectDir.resolve("app/src/main/spec/app.md")
        appSpec.writeText(appSpec.readText().replace("greets every name with `greet` from the greeting module, in order.",
            "returns \"Hi, \" followed by each name, using `greet` from the greeting module."))
        server.answerWhen(
            CODE, "Module: :app",
            answers = listOf(
                """{"status":"contradiction","reason":"","files":[],"contradictions":[{
                    "dependencyModule":":greeting",
                    "dependencyQuote":"`greet` returns \"Hello, \" followed by the name and \"!\".",
                    "ownQuote":"`welcomeAll` returns \"Hi, \" followed by each name, using `greet` from the greeting module.",
                    "explanation":"greet always starts with \"Hello, \", so welcomeAll cannot start with \"Hi, \" by using it."}]}"""
            ),
        )

        // :app compiles against :greeting, so :greeting is generated first.
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        val build = runAndFail(":app:generateKotlinFromSpec", "-Pgraidle.mode=live")

        val greetingSpecUri = projectDir.resolve("greeting/src/main/spec/greeting.md").canonicalFile.toPath().toUri()
        val appSpecUri = projectDir.resolve("app/src/main/spec/app.md").canonicalFile.toPath().toUri()
        assertThat(build.output).contains(
            "The specification of :app contradicts the specification of its dependency :greeting, so grAIdle did not generate anything.",
            "Resolve the points below in the specifications, then run ':app:generateKotlinFromSpec' again.",
            "1. Contradiction",
            // Links to the line and column where each quote starts.
            "   :greeting at $greetingSpecUri:13:4\n",
            "   :app at $appSpecUri:13:4\n",
            "   Why they conflict: greet always starts with",
        )
        // Not retried: a person must decide. Nothing is written.
        assertThat(requestsFor(CODE, ":app")).hasSize(1)
        assertThat(requestFor(CODE, ":app")).contains("If they clearly contradict each other, do not choose one of them")
        assertThat(projectDir.resolve("app/graidle/main/kotlin.lock")).doesNotExist()
        assertThat(projectDir.resolve("app/graidle/main/kotlin").walk().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `stops and asks a question when a spec leaves a decision open`() {
        server.answerWhen(
            TESTS, "Module: :greeting",
            answers = listOf(
                """{"status":"unclear","reason":"","files":[],"contradictions":[],"unclear":[{
                    "module":":greeting",
                    "quote":"`greet` returns \"Hello, \" followed by the name and \"!\".",
                    "question":"What does greet return for a blank name?"}]}"""
            ),
        )

        // The tests compile against the module's own code, so the code is generated first.
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        val build = runAndFail(":greeting:generateTestsFromSpec", "-Pgraidle.mode=live")

        val greetingSpecUri = greetingSpec.canonicalFile.toPath().toUri()
        assertThat(build.output).contains(
            "The specification of :greeting is not clear enough to generate the result without guessing, so grAIdle did not generate anything.",
            "1. Unclear",
            "   :greeting at $greetingSpecUri:13:4\n",
            "   Question: What does greet return for a blank name?",
        )
        assertThat(requestsFor(TESTS, ":greeting")).hasSize(1)
        assertThat(requestFor(TESTS, ":greeting")).contains("If the specifications leave open a decision that changes what the public API does")
        assertThat(projectDir.resolve("greeting/graidle/test/kotlin.lock")).doesNotExist()
    }

    @Test
    fun `asks again when a reported contradiction misquotes a spec`() {
        val misquoted = """{"status":"contradiction","reason":"","files":[],"contradictions":[{
            "dependencyModule":":greeting",
            "dependencyQuote":"greet returns \"Good morning\" for every name",
            "ownQuote":"`welcomeAll` greets every name with `greet` from the greeting module, in order.",
            "explanation":"They disagree."}]}"""
        // :app compiles against :greeting, so :greeting is generated first.
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        server.answerWhen(CODE, "Module: :app", answers = listOf(misquoted, appCode))

        val build = run(":app:generateKotlinFromSpec", "-Pgraidle.mode=live")

        assertThat(build.task(":app:generateKotlinFromSpec")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(requestsFor(CODE, ":app")).hasSize(2)
        assertThat(requestsFor(CODE, ":app")[1]).contains("does not appear in the specification of :greeting. Quote it word for word.")
    }

    @Test
    fun `looks up library and module APIs on the compile classpath with tools`() {
        // A real third-party library from Maven Central, next to the project module.
        projectDir.resolve("app/build.gradle.kts").let {
            it.writeText(it.readText().replace("implementation(project(\":greeting\"))",
                "implementation(project(\":greeting\"))\n    implementation(\"org.apache.commons:commons-lang3:3.22.0\")"))
        }
        val appCodeWithLibrary = files(
            "demo/app/App.kt" to """
                package demo.app

                import demo.greeting.greet
                import org.apache.commons.lang3.StringUtils

                fun welcomeAll(names: List<String>): List<String> = names.map { greet(StringUtils.capitalize(it)) }
            """.trimIndent()
        )
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        server.answerWhen(
            CODE, "Module: :app",
            answers = listOf(
                FakeClaudeServer.toolUse("describe_class", """{"name":"org.apache.commons.lang3.StringUtils"}"""),
                FakeClaudeServer.toolUse("describe_class", """{"name":"demo.greeting.GreetingKt"}"""),
                appCodeWithLibrary,
            ),
        )

        val build = run(":app:generateKotlinFromSpec", "-Pgraidle.mode=live")

        assertThat(build.task(":app:generateKotlinFromSpec")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val appRequests = requestsFor(CODE, ":app")
        assertThat(appRequests).hasSize(3)
        assertThat(appRequests[0]).contains("\"name\":\"describe_class\"", "\"name\":\"search_classpath\"")
        // Each tool result goes back to the model: the library's API, then the module's.
        assertThat(appRequests[1]).contains("\"tool_result\"", "public static capitalize(java.lang.String): java.lang.String")
        assertThat(appRequests[2]).contains("public static final greet(java.lang.String): java.lang.String")
        assertThat(projectDir.resolve("app/graidle/main/kotlin/demo/app/App.kt").readText()).contains("StringUtils.capitalize")
        assertThat(projectDir.resolve("app/build/graidle/transcripts/generateKotlinFromSpec.json").readText())
            .contains("\"toolCalls\"", "describe_class")
    }

    @Test
    fun `regenerating shows the previous code, and a kept file stays as it was`() {
        server.answerWhen(CODE, "Module: :greeting", answers = listOf(greetingCode))
        run(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")
        val codeFile = projectDir.resolve("greeting/graidle/main/kotlin/demo/greeting/Greeting.kt")
        val committed = codeFile.readText()
        greetingSpec.appendText("\nThe greeting is used on the start screen.\n")
        server.answerWhen(
            CODE, "Module: :greeting",
            answers = listOf("""{"status":"ok","reason":"","files":[{"path":"demo/greeting/Greeting.kt","content":"","keep":true}]}"""),
        )

        run(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")

        val requests = requestsFor(CODE, ":greeting")
        assertThat(requests).hasSize(2)
        assertThat(requests[1]).contains("It must be generated again, because its inputs changed", "Previous result:", "fun greet(name: String): String = ")
        assertThat(codeFile.readText()).isEqualTo(committed)
    }

    @Test
    fun `a spec without an API section fails before calling the model`() {
        greetingSpec.writeText("# Greeting\n\nSays hello.\n")

        val build = runAndFail(":greeting:generateKotlinFromSpec", "-Pgraidle.mode=live")

        assertThat(build.output).contains("The specification of :greeting", "has no API section with a package")
        assertThat(server.requests).isEmpty()
    }

    private companion object {
        const val CODE = "Implement a Kotlin/JVM module"
        const val TESTS = "Write tests for a Kotlin/JVM module"

        fun files(vararg files: Pair<String, String>): String =
            """{"status":"ok","reason":"","files":[""" +
                files.joinToString(",") { (path, content) -> """{"path":${json(path)},"content":${json(content)}}""" } +
                "]}"

        fun json(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
