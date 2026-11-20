package dev.runai.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File

class FingerprintTest {

    @Test
    fun `does not depend on the order of entries`() {
        val a = Fingerprint().put("model", "m").put("locale", "de").hash()
        val b = Fingerprint().put("locale", "de").put("model", "m").hash()

        assertThat(a).isEqualTo(b).hasSize(64)
    }

    @Test
    fun `does not depend on line endings`() {
        assertThat(Fingerprint().put("text", "a\r\nb").hash()).isEqualTo(Fingerprint().put("text", "a\nb").hash())
    }

    @Test
    fun `tells absent, empty and moved values apart`() {
        val hashes = listOf(
            Fingerprint().put("a", null).hash(),
            Fingerprint().put("a", "").hash(),
            Fingerprint().put("a", "x").put("b", "").hash(),
            Fingerprint().put("a", "").put("b", "x").hash(),
        )

        assertThat(hashes).doesNotHaveDuplicates()
    }

    @Test
    fun `reads files as text and treats a missing file as absent`() {
        val file = File.createTempFile("glossary", ".md").apply { writeText("app = App"); deleteOnExit() }

        assertThat(Fingerprint().putText("glossary", file).hash()).isEqualTo(Fingerprint().put("glossary", "app = App").hash())
        assertThat(Fingerprint().putText("glossary", null).hash()).isEqualTo(Fingerprint().put("glossary", null).hash())
    }

    @Test
    fun `rejects a key set twice`() {
        assertThatThrownBy { Fingerprint().put("a", "1").put("a", "2") }.hasMessageContaining("already set")
    }
}
