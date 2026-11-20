package dev.runai.specs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class SpecQuotesTest {

    private val spec = ModuleSpecFiles(
        ":lexer",
        File("/work/project/lexer/src/main/spec"),
        linkedMapOf(
            "intro.md" to "# Lexer\n\nSplits text into tokens.\n",
            "lexer.md" to "## Behavior\n\n1. Whitespace separates tokens.\n2. A number is one or more digits,\n   optionally followed by `.` and digits.\n",
        ),
    )

    @Test
    fun `finds the file, line and column where a quote starts`() {
        val location = SpecQuotes.locate("A number is one or more digits, optionally followed by . and digits", spec)

        assertThat(location).isEqualTo(SpecLocation(File("/work/project/lexer/src/main/spec/lexer.md"), 4, 4))
        assertThat(location.toString()).isEqualTo("file:///work/project/lexer/src/main/spec/lexer.md:4:4")
    }

    @Test
    fun `ignores case, code marks and final punctuation`() {
        assertThat(SpecQuotes.locate("WHITESPACE separates tokens", spec)?.line).isEqualTo(3)
        assertThat(SpecQuotes.locate("splits text into tokens.", spec)?.file?.name).isEqualTo("intro.md")
    }

    @Test
    fun `returns null for a quote that is not in the spec`() {
        assertThat(SpecQuotes.locate("Whitespace is an error", spec)).isNull()
        assertThat(SpecQuotes.locate("  ", spec)).isNull()
    }
}
