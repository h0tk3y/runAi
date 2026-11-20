package dev.runai.android

import dev.runai.testing.FakeClaudeServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Builds a minimal Android app, so aapt2 compiles the generated resources. Skipped when no Android SDK is found. */
class AndroidFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val server = FakeClaudeServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `translated resources are compiled into the app`() {
        val sdk = androidSdk()
        assumeTrue(sdk != null, "No Android SDK found. Set ANDROID_HOME to run this test.")

        projectDir.resolve("local.properties").writeText("sdk.dir=${sdk!!.absolutePath}\n")
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "android-fixture"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("dev.runai.android-string-translations")
            }

            android {
                namespace = "dev.runai.fixture"
                compileSdk = 36
                defaultConfig { minSdk = 24 }
            }

            runAi {
                apiKey = "test-key"
                baseUrl = "${server.url}"
            }

            androidStringTranslations { locales("fr") }
            """.trimIndent()
        )
        projectDir.resolve("src/main/res/values").mkdirs()
        projectDir.resolve("src/main/AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />"""
        )
        projectDir.resolve("src/main/res/values/strings.xml").writeText(
            """
            <resources>
                <string name="open">Open the app</string>
                <string name="share">Share with %1${'$'}s</string>
            </resources>
            """.trimIndent()
        )
        // The model's apostrophes are not escaped; runAi escapes them so aapt2 accepts the file.
        server.enqueue(
            """
            {"status":"ok","reason":"",
             "strings":[{"name":"open","value":"Ouvrir l'application"},{"name":"share","value":"Partager avec %1${'$'}s"}],
             "plurals":[]}
            """.trimIndent()
        )

        fun build(vararg args: String) = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "processDebugResources", "processReleaseResources", "--configuration-cache", "--stacktrace")
            .build()

        val live = build("-PrunAi.mode=live")

        // One translation task feeds both variants directly: no copy, one model request.
        assertThat(live.task(":translateStringsFr")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(live.task(":processDebugResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(live.task(":processReleaseResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(live.tasks.map { it.path }).noneMatch { "collect" in it.lowercase() }
        assertThat(server.requests).hasSize(1)

        val committed = projectDir.resolve("runAi/translations/fr/values-fr/strings.xml")
        assertThat(committed.readText()).contains("""<string name="open">Ouvrir l\'application</string>""")
        assertThat(mergedFrenchResources()).anyMatch { "/debug/" in it }.anyMatch { "/release/" in it }

        // A fresh clone builds from the committed result, in replay mode, without the model.
        projectDir.resolve("build").deleteRecursively()
        projectDir.resolve(".gradle").deleteRecursively()

        val replay = build("-PrunAi.mode=replay")

        assertThat(replay.task(":translateStringsFr")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(replay.task(":processReleaseResources")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(mergedFrenchResources()).anyMatch { "/debug/" in it }.anyMatch { "/release/" in it }
        assertThat(server.requests).hasSize(1)
    }

    private fun mergedFrenchResources(): List<String> = projectDir.resolve("build/intermediates").walk()
        .filter { it.isFile && "values-fr" in it.name && "merged_res" in it.path }
        .map { it.relativeTo(projectDir).path }
        .toList()

    private fun androidSdk(): File? = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
        System.getProperty("user.home") + "/Android/Sdk",
    ).map(::File).firstOrNull { it.resolve("platforms").isDirectory }
}
