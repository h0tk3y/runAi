package dev.graidle.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AndroidStringChecksTest {

    @Test
    fun `finds format specifiers but not percent signs in text`() {
        assertThat(AndroidStringChecks.formatSpecifiers("%1\$s has %2\$d items, %.1f%% done"))
            .containsExactly("%%", "%.1f", "%1\$s", "%2\$d")
        assertThat(AndroidStringChecks.formatSpecifiers("100 % sûr")).isEmpty()
    }

    @Test
    fun `escapes quotes outside tags only`() {
        val fixed = AndroidStringChecks.normalize("""L'application <a href="x">s'ouvre</a> \'déjà\'""", "The app opens")

        assertThat(fixed).isEqualTo("""L\'application <a href="x">s\'ouvre</a> \'déjà\'""")
    }

    @Test
    fun `keeps quotes when the source value is quoted`() {
        assertThat(AndroidStringChecks.normalize("\"  Hallo \"", "\"  Hello \"")).isEqualTo("\"  Hallo \"")
    }

    @Test
    fun `escapes a leading at sign that would make a reference`() {
        assertThat(AndroidStringChecks.normalize("@Name ist vergeben", "@name is taken")).isEqualTo("\\@Name ist vergeben")
    }

    @Test
    fun `reports changed specifiers, tags and xliff content`() {
        val source = """Hi <b>%1${'$'}s</b>, wait <xliff:g id="t">%2${'$'}d min</xliff:g>"""
        val target = """Hallo %1${'$'}s, warte <xliff:g id="t">%2${'$'}d Min.</xliff:g>"""

        assertThat(AndroidStringChecks.checkString(source, target)).containsExactly(
            "XML tags must be [b, xliff:g], found [xliff:g]",
            "<xliff:g> contents must be [%2\$d min], found [%2\$d Min.]",
        )
    }

    @Test
    fun `reports broken XML`() {
        assertThat(AndroidStringChecks.checkString("<b>Hi</b>", "<b>Hallo")).singleElement()
            .asString().startsWith("the value is not well-formed XML")
    }

    @Test
    fun `accepts plurals with other quantities than the source`() {
        val source = mapOf("one" to "%d file", "other" to "%d files")
        val russian = mapOf("one" to "%d файл", "few" to "%d файла", "many" to "%d файлов", "other" to "%d файла")

        assertThat(AndroidStringChecks.checkPlurals(source, russian)).isEmpty()
    }

    @Test
    fun `rejects plurals without other or with unknown quantities`() {
        val source = mapOf("one" to "%d file", "other" to "%d files")

        assertThat(AndroidStringChecks.checkPlurals(source, mapOf("one" to "%d Datei", "several" to "%d Dateien")))
            .contains("unknown quantities [several]; use only [few, many, one, other, two, zero]", "the quantity 'other' is missing")
    }
}
