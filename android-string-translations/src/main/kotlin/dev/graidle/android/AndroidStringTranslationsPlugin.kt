package dev.graidle.android

import dev.graidle.GraidlePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Translates Android string resources with grAIdle. Built on the core plugin like any other plugin can be: it applies
 * `dev.graidle`, and registers one [TranslateStringsTask] per locale.
 */
class AndroidStringTranslationsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply(GraidlePlugin::class.java)
        val extension = project.extensions.create(EXTENSION_NAME, AndroidStringTranslationsExtension::class.java)
        extension.sourceStrings.convention(project.layout.projectDirectory.file("src/main/res/values/strings.xml"))
        extension.sourceLanguage.convention("en")
        extension.chunkSize.convention(100)

        val translateTasks = mutableListOf<TaskProvider<TranslateStringsTask>>()
        val translateAll = project.tasks.register("translateStrings") {
            group = "grAIdle"
            description = "Translates Android string resources to all configured locales."
        }

        extension.locales.all {
            val locale = this
            val task = project.tasks.register("translateStrings${taskSuffix(locale.name)}", TranslateStringsTask::class.java) {
                description = "Translates Android string resources to '${locale.name}'."
                sourceStrings.convention(extension.sourceStrings)
                sourceLanguage.convention(extension.sourceLanguage)
                glossary.convention(extension.glossary)
                instructions.convention(extension.instructions)
                localeInstructions.convention(locale.instructions)
                targetLocale.convention(locale.name)
                chunkSize.convention(extension.chunkSize)
                // `set`, not `convention`: AGP sets a per-variant convention on generated source directories, and
                // one translation serves all variants. A later `set` in the build script still overrides this.
                outputDirectory.set(project.layout.projectDirectory.dir("graidle/translations/${locale.name}"))
            }
            translateTasks += task
            translateAll.configure { dependsOn(task) }
        }

        val wired = AtomicBoolean(false)
        listOf("com.android.application", "com.android.library").forEach { id ->
            project.pluginManager.withPlugin(id) {
                if (wired.compareAndSet(false, true)) AndroidIntegration.wire(project, translateTasks)
            }
        }
    }

    companion object {
        const val EXTENSION_NAME = "androidStringTranslations"

        /** `pt-rBR` becomes `PtRbr`, `b+sr+Latn` becomes `BSrLatn`. */
        fun taskSuffix(qualifier: String): String = qualifier
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
