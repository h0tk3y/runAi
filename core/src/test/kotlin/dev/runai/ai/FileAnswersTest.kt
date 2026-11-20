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
    fun `keeping a file that has no previous version is a problem, unless the answer has its content`() {
        val empty = """{"status":"ok","reason":"","files":[{"path":"a.md","content":"","keep":true}]}"""
        val withContent = """{"status":"ok","reason":"","files":[{"path":"a.md","content":"new","keep":true}]}"""

        assertThat(FileAnswers.parse(empty)).isEqualTo(
            Validation.Invalid(listOf("'a.md' has \"keep\" set, but the previous result has no such file. Give its content."))
        )
        assertThat(FileAnswers.parse(withContent)).isEqualTo(Validation.Valid(mapOf("a.md" to "new")))
    }

    @Test
    fun `answers without keep still parse`() {
        assertThat(FileAnswers.parse("""{"status":"ok","reason":"","files":[{"path":"a.md","content":"x"}]}"""))
            .isEqualTo(Validation.Valid(mapOf("a.md" to "x")))
    }

    @Test
    fun `the schema has keep only with a previous result, and can add statuses and properties`() {
        assertThat(FileAnswers.schema()).doesNotContain("keep").contains(""""required":["path","content"]""")
        val schema = FileAnswers.schema(
            extraStatuses = listOf("unclear"),
            extraProperties = mapOf("notes" to """{"type":"string"}"""),
            withKeep = true,
        )

        assertThat(schema).contains(
            """"keep":{"type":"boolean"}""",
            """"required":["path","content","keep"]""",
            """"enum":["ok","cannot_complete","unclear"]""",
            """"notes":{"type":"string"}""",
            """"required":["status","reason","files","notes"]""",
        )
    }
}
