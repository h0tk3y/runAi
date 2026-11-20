package dev.runai.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FileAnswersTest {

    @Test
    fun `a kept file gets its previous content byte for byte`() {
        val answer = """{"status":"ok","reason":"","files":[
            {"path":"a.md","content":"","keep":true},
            {"path":"b.md","content":"new","keep":false}]}"""

        val result = FileAnswers.parse(answer, previous = mapOf("a.md" to "old\r\ntext", "c.md" to "gone"))

        assertThat(result).isEqualTo(Validation.Valid(mapOf("a.md" to "old\r\ntext", "b.md" to "new")))
    }

    @Test
    fun `keeping a file that has no previous version is a problem`() {
        val answer = """{"status":"ok","reason":"","files":[{"path":"a.md","content":"","keep":true}]}"""

        assertThat(FileAnswers.parse(answer)).isEqualTo(
            Validation.Invalid(listOf("'a.md' has \"keep\" set, but the previous result has no such file"))
        )
    }

    @Test
    fun `answers without keep still parse`() {
        assertThat(FileAnswers.parse("""{"status":"ok","reason":"","files":[{"path":"a.md","content":"x"}]}"""))
            .isEqualTo(Validation.Valid(mapOf("a.md" to "x")))
    }

    @Test
    fun `the schema requires keep and can add statuses and properties`() {
        val schema = FileAnswers.schema(extraStatuses = listOf("unclear"), extraProperties = mapOf("notes" to """{"type":"string"}"""))

        assertThat(schema).contains(
            """"keep":{"type":"boolean"}""",
            """"required":["path","content","keep"]""",
            """"enum":["ok","cannot_complete","unclear"]""",
            """"notes":{"type":"string"}""",
            """"required":["status","reason","files","notes"]""",
        )
    }
}
