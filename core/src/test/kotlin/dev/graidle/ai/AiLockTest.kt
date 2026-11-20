package dev.graidle.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AiLockTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `reads back what it writes`() {
        val file = dir.resolve("translations/de.lock")
        val lock = AiLock("abc123", "claude-opus-5-5", "system-1/translate-1")

        lock.write(file, ":translateStringsDe")

        assertThat(AiLock.read(file)).isEqualTo(lock)
        assertThat(file.readText()).startsWith("# grAIdle lock for :translateStringsDe.")
    }

    @Test
    fun `a missing file or fingerprint means no lock`() {
        assertThat(AiLock.read(dir.resolve("missing.lock"))).isNull()
        assertThat(AiLock.read(dir.resolve("empty.lock").apply { writeText("model=m\n") })).isNull()
    }
}
