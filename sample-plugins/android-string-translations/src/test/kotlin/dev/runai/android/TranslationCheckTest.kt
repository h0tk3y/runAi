package dev.runai.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationCheckTest {

    @TempDir
    lateinit var dir: File

    private val source = listOf(
        StringEntry("greeting", "Hello, %1\$s!"),
        PluralsEntry("files", linkedMapOf("one" to "%d file", "other" to "%d files")),
    )

    private fun translation(vararg entries: StringResource): File =
        dir.resolve("values-de/strings.xml").also { StringResourcesXml.write(StringResourceFile(emptyMap(), entries.toList()), it) }

    @Test
    fun `accepts a complete translation`() {
        val file = translation(
            StringEntry("greeting", "Hallo, %1\$s!"),
            PluralsEntry("files", linkedMapOf("one" to "%d Datei", "other" to "%d Dateien")),
        )

        assertThat(TranslationCheck.verify(source, file)).isEmpty()
    }

    @Test
    fun `reports broken, missing and extra resources`() {
        val file = translation(StringEntry("greeting", "Hallo!"), StringEntry("old", "Alt"))

        assertThat(TranslationCheck.verify(source, file)).containsExactly(
            "string 'greeting': format specifiers must be [%1\$s], found []",
            "'files' is missing",
            "'old' is not in the source strings",
        )
    }

    @Test
    fun `reports a missing file`() {
        assertThat(TranslationCheck.verify(source, dir.resolve("values-fr/strings.xml"))).containsExactly("strings.xml is missing")
    }
}
