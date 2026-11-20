package dev.runai.android

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Adds the output directory of each translation task to every Android variant as a generated resource directory.
 * Only loaded when an Android plugin is applied, so the plugin works without AGP on the classpath.
 *
 * One translation task serves all variants. AGP sets a per-variant location on a generated source directory with
 * `convention()`, so the plugin sets the output location with `set()`, and that location wins.
 */
internal object AndroidIntegration {

    fun wire(project: Project, translateTasks: List<TaskProvider<TranslateStringsTask>>) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant: Variant ->
            val res = variant.sources.res ?: return@onVariants
            translateTasks.forEach { task ->
                res.addGeneratedSourceDirectory(task, TranslateStringsTask::outputDirectory)
            }
        }
    }
}
