package dev.runai.specs

import dev.runai.RunAiPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.SourceSetContainer

/**
 * Spec-driven Kotlin, built on the runAi core plugin:
 *
 * - `generateKotlinFromSpec` writes the implementation of the module from `src/main/spec` into
 *   `runAi/main/kotlin`, which becomes a source directory of the `main` Kotlin source set.
 * - `generateTestsFromSpec` writes tests from the same specification into `runAi/test/kotlin`, a source directory
 *   of `test`. It does not see the implementation, so the tests check the implementation against the specification.
 * - The `specElements` configuration publishes the specification as a `runai-spec` variant. Both tasks read the
 *   specifications of the modules on the compile classpath from that variant: the model sees what the dependencies
 *   declare, never how they implement it.
 */
class SpecDrivenKotlinPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply(RunAiPlugin::class.java)
        val extension = project.extensions.create(EXTENSION_NAME, SpecDrivenKotlinExtension::class.java)
        extension.specDirectory.convention(project.layout.projectDirectory.dir("src/main/spec"))

        project.configurations.consumable(SPEC_ELEMENTS) {
            description = "The specification of this module, for the runAi tasks of dependent modules."
            attributes { specAttributes(project.objects) }
            outgoing.artifact(extension.specDirectory) { type = ArtifactTypeDefinition.DIRECTORY_TYPE }
        }

        project.tasks.withType(AbstractKotlinFromSpecTask::class.java).configureEach {
            modulePath.convention(project.path)
            specDirectory.convention(extension.specDirectory)
            instructions.convention(extension.instructions)
        }
        val generateCode = project.tasks.register("generateKotlinFromSpec", KotlinFromSpecTask::class.java) {
            description = "Generates the Kotlin implementation of this module from its specification."
            requiredFiles.convention(extension.requiredFiles)
            outputDirectory.convention(project.layout.projectDirectory.dir("runAi/main/kotlin"))
        }
        val generateTests = project.tasks.register("generateTestsFromSpec", KotlinTestsFromSpecTask::class.java) {
            description = "Generates Kotlin tests for this module from its specification."
            outputDirectory.convention(project.layout.projectDirectory.dir("runAi/test/kotlin"))
        }
        project.tasks.register("generateFromSpec") {
            group = "runAi"
            description = "Generates the code and the tests of this module from its specification."
            dependsOn(generateCode, generateTests)
        }

        project.pluginManager.withPlugin("java") {
            // Reselect the spec variant of each module on the compile classpath. Modules without one, such as
            // external libraries, are skipped.
            val specArtifacts = project.configurations.named("compileClasspath").flatMap { classpath ->
                classpath.incoming.artifactView {
                    withVariantReselection()
                    lenient(true)
                    componentFilter { it is ProjectComponentIdentifier }
                    attributes { specAttributes(project.objects) }
                }.artifacts.resolvedArtifacts
            }
            // The code sees what it compiles against; the tests also see this module's own API.
            generateCode.configure { classpath.from(project.configurations.named("compileClasspath")) }
            generateTests.configure { classpath.from(project.configurations.named("testCompileClasspath")) }
            project.tasks.withType(AbstractKotlinFromSpecTask::class.java).configureEach {
                dependencySpecDirectories.from(specArtifacts.map { artifacts -> artifacts.map { it.file } })
                dependencyModules.set(
                    specArtifacts.map { artifacts ->
                        artifacts.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath }
                    }
                )
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            kotlinSources(sourceSets.getByName("main")).srcDir(generateCode.flatMap { it.outputDirectory })
            kotlinSources(sourceSets.getByName("test")).srcDir(generateTests.flatMap { it.outputDirectory })
        }
    }

    /** The Kotlin plugin adds a `kotlin` source directory set to each Java source set. */
    private fun kotlinSources(sourceSet: org.gradle.api.tasks.SourceSet): SourceDirectorySet =
        sourceSet.extensions.getByName("kotlin") as SourceDirectorySet

    companion object {
        const val EXTENSION_NAME = "specDrivenKotlin"
        const val SPEC_ELEMENTS = "specElements"

        /** The `DocsType` of the spec variant. */
        const val SPEC_DOCS_TYPE = "runai-spec"

        fun AttributeContainer.specAttributes(objects: ObjectFactory) {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.DOCUMENTATION))
            attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType::class.java, SPEC_DOCS_TYPE))
        }
    }
}
