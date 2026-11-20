package dev.graidle.specs

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configures spec-driven Kotlin for one module. The model, effort, mode and credentials come from the `graidle`
 * extension of the core plugin.
 */
abstract class SpecDrivenKotlinExtension {

    /** The Markdown specification of this module. Defaults to `src/main/spec`. */
    abstract val specDirectory: DirectoryProperty

    /** Optional instructions for both the code and the tests, for example conventions of the project. */
    abstract val instructions: Property<String>

    /** Files the generated code must contain, as paths relative to the source root, for example the file with `main`. */
    abstract val requiredFiles: ListProperty<String>
}
