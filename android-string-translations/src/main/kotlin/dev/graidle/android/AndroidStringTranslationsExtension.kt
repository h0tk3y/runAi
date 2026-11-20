package dev.graidle.android

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures the translation tasks. The model, effort, mode and credentials come from the `graidle` extension of the
 * core plugin.
 */
abstract class AndroidStringTranslationsExtension @Inject constructor(objects: ObjectFactory) {

    /** The default-language `strings.xml`. Defaults to `src/main/res/values/strings.xml`. */
    abstract val sourceStrings: RegularFileProperty

    /** BCP 47 tag of the source language. Defaults to `en`. */
    abstract val sourceLanguage: Property<String>

    /** Optional terms and preferred translations, in any text format. */
    abstract val glossary: RegularFileProperty

    /** Optional instructions for all locales, for example tone or audience. */
    abstract val instructions: Property<String>

    /** How many resources go into one request. */
    abstract val chunkSize: Property<Int>

    /** Target locales, named by their Android resource qualifier, for example `de`, `pt-rBR` or `b+sr+Latn`. */
    val locales: NamedDomainObjectContainer<TranslationLocale> =
        objects.domainObjectContainer(TranslationLocale::class.java)

    fun locales(vararg qualifiers: String) {
        qualifiers.forEach { locales.maybeCreate(it) }
    }
}

interface TranslationLocale : Named {
    /** Optional instructions for this locale only. */
    val instructions: Property<String>
}
