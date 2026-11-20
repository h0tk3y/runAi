package dev.graidle.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StructuredRunnerTest {

    private val request = AiRequest(
        model = "claude-opus-5-5",
        effort = "medium",
        maxTokens = 1000,
        system = "system",
        prompt = "Return a number.",
        outputSchema = "{}",
        refusalFallback = false,
    )

    private fun backend(vararg answers: String): Pair<MutableList<AiRequest>, (AiRequest) -> AiResponse> {
        val seen = mutableListOf<AiRequest>()
        val queue = ArrayDeque(answers.toList())
        return seen to { r ->
            seen += r
            AiResponse(queue.removeFirst(), "claude-opus-5-5", "end_turn", 10, 5)
        }
    }

    private val validate: (String) -> Validation<Int> = { text ->
        when {
            text == "give up" -> Validation.CannotComplete("no number")
            text.toIntOrNull() == null -> Validation.Invalid(listOf("'$text' is not a number"))
            else -> Validation.Valid(text.toInt())
        }
    }

    @Test
    fun `asks again with the problems and the previous answer`() {
        val (seen, complete) = backend("seven", "7")
        val transcript = Transcript(":test")

        val result = StructuredRunner(complete, transcript).run("number", request, maxAttempts = 3, validate)

        assertThat(result).isEqualTo(7)
        assertThat(seen).hasSize(2)
        assertThat(seen[1].prompt)
            .startsWith("Return a number.")
            .contains("- 'seven' is not a number", "Previous answer:\nseven")
    }

    @Test
    fun `fails after the last attempt`() {
        val (seen, complete) = backend("a", "b")

        assertThatThrownBy { StructuredRunner(complete, Transcript(":test")).run("number", request, 2, validate) }
            .isInstanceOf(AiTaskFailedException::class.java)
            .hasMessageContaining("after 2 attempt(s)")
            .hasMessageContaining("'b' is not a number")
        assertThat(seen).hasSize(2)
    }

    @Test
    fun `does not retry when the model cannot complete the task`() {
        val (seen, complete) = backend("give up", "7")

        assertThatThrownBy { StructuredRunner(complete, Transcript(":test")).run("number", request, 3, validate) }
            .hasMessage("The model could not complete 'number': no number")
        assertThat(seen).hasSize(1)
    }
}
