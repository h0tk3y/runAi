package dev.graidle.android

import dev.graidle.ai.Validation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TranslationPromptTest {

    private val chunk = listOf(
        StringEntry("greeting", "Hello, %1\$s!"),
        PluralsEntry("files", linkedMapOf("one" to "%d file", "other" to "%d files")),
    )
    private val prompt = TranslationPrompt("en", "pt-rBR", glossary = "app = aplicativo", instructions = "Use informal tone.")

    @Test
    fun `prompt names the languages and contains the input`() {
        val text = prompt.render(chunk)

        assertThat(text).startsWith("Task: Translate Android string resources from English (en) to Portuguese (Brazil) (pt-BR")
        assertThat(text).contains("app = aplicativo", "Use informal tone.")
        assertThat(text).contains("""{"strings":[{"name":"greeting","value":"Hello, %1${'$'}s!"}]""")
    }

    @Test
    fun `accepts a complete answer and writes plurals in CLDR order`() {
        val answer = """
            {"status":"ok","reason":"","strings":[{"name":"greeting","value":"Olá, %1${'$'}s!"}],
             "plurals":[{"name":"files","items":[{"quantity":"other","value":"%d arquivos"},{"quantity":"one","value":"%d arquivo"}]}]}
        """.trimIndent()

        val result = prompt.check(chunk, answer)

        assertThat(result).isEqualTo(
            Validation.Valid(
                listOf(
                    StringEntry("greeting", "Olá, %1\$s!"),
                    PluralsEntry("files", linkedMapOf("one" to "%d arquivo", "other" to "%d arquivos")),
                )
            )
        )
        assertThat((result as Validation.Valid).value[1].let { (it as PluralsEntry).items.keys }).containsExactly("one", "other")
    }

    @Test
    fun `reports missing, unexpected and broken items`() {
        val answer = """
            {"status":"ok","reason":"","strings":[{"name":"greeting","value":"Olá!"},{"name":"extra","value":"x"}],"plurals":[]}
        """.trimIndent()

        val result = prompt.check(chunk, answer)

        assertThat(result).isInstanceOf(Validation.Invalid::class.java)
        assertThat((result as Validation.Invalid).problems).containsExactly(
            "string 'greeting': format specifiers must be [%1\$s], found []",
            "plurals 'files' is missing",
            "string 'extra' is not in the input",
        )
    }

    @Test
    fun `passes on the model's cannot_complete answer`() {
        val result = prompt.check(chunk, """{"status":"cannot_complete","reason":"ambiguous source","strings":[],"plurals":[]}""")

        assertThat(result).isEqualTo(Validation.CannotComplete("ambiguous source"))
    }

    @Test
    fun `reports an answer that is not JSON`() {
        assertThat(prompt.check(chunk, "Sure! Here is the translation")).isInstanceOf(Validation.Invalid::class.java)
    }

    @Test
    fun `rejects a target that is not an Android qualifier`() {
        assertThatThrownBy { TranslationPrompt("en", "pt_BR", null, null) }
            .hasMessageContaining("is not an Android locale qualifier")
    }

    @Test
    fun `converts Android qualifiers to language tags`() {
        assertThat(TranslationPrompt.qualifierToLanguageTag("de")).isEqualTo("de")
        assertThat(TranslationPrompt.qualifierToLanguageTag("pt-rBR")).isEqualTo("pt-BR")
        assertThat(TranslationPrompt.qualifierToLanguageTag("b+sr+Latn")).isEqualTo("sr-Latn")
    }
}
