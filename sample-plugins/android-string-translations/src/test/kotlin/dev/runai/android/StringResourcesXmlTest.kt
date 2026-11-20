package dev.runai.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StringResourcesXmlTest {

    private val source = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
            <string name="app_name" translatable="false">Demo</string>
            <string name="alias">@string/app_name</string>
            <string name="greeting">Hello, %1${'$'}s!</string>
            <string name="welcome">Welcome to <b>Demo</b> &amp; friends</string>
            <string name="timer">Wait <xliff:g id="time" example="5 min">%1${'$'}s</xliff:g></string>
            <!-- a comment -->
            <string-array name="ignored"><item>One</item></string-array>
            <plurals name="files">
                <item quantity="one">%d file</item>
                <item quantity="other">%d files</item>
            </plurals>
        </resources>
    """.trimIndent()

    @Test
    fun `reads translatable strings and plurals only`() {
        val file = StringResourcesXml.parse(source)

        assertThat(file.namespaces).containsEntry("xliff", "urn:oasis:names:tc:xliff:document:1.2")
        assertThat(file.entries).containsExactly(
            StringEntry("greeting", "Hello, %1\$s!"),
            StringEntry("welcome", "Welcome to <b>Demo</b> &amp; friends"),
            StringEntry("timer", "Wait <xliff:g example=\"5 min\" id=\"time\">%1\$s</xliff:g>"),
            PluralsEntry("files", linkedMapOf("one" to "%d file", "other" to "%d files")),
        )
    }

    @Test
    fun `written file reads back to the same resources`() {
        val file = StringResourcesXml.parse(source)

        val rendered = StringResourcesXml.render(file)

        assertThat(StringResourcesXml.parse(rendered)).isEqualTo(file)
        assertThat(rendered).contains("""<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">""")
        assertThat(rendered).contains("""    <item quantity="other">%d files</item>""")
    }

    @Test
    fun `recognizes resource references`() {
        assertThat(StringResourcesXml.isReference("@string/app_name")).isTrue()
        assertThat(StringResourcesXml.isReference("?attr/colorPrimary")).isTrue()
        assertThat(StringResourcesXml.isReference("@ home")).isFalse()
        assertThat(StringResourcesXml.isReference("\\@home")).isFalse()
    }
}
