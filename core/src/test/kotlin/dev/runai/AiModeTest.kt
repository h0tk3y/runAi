package dev.runai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AiModeTest {

    @Test
    fun `mode names are case insensitive`() {
        assertThat(AiMode.parse("Replay")).isEqualTo(AiMode.REPLAY)
    }

    @Test
    fun `an unknown mode lists the valid ones`() {
        assertThatThrownBy { AiMode.parse("record") }.hasMessage("Unknown runAi mode 'record'. Use one of: live, replay, off.")
    }
}
