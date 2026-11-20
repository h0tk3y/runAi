package dev.graidle.specs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecApiTest {

    private val spec = """
        # Lexer

        Splits an expression into tokens.

        ## API

        ```kotlin
        package demo.lexer

        import demo.ast.Position

        enum class TokenType { NUMBER, PLUS }

        data class Token(val type: TokenType, val text: String) // one token

        fun tokenize(input: String): List<Token>

        fun <T> List<T>.second(): T
        ```

        ### Notes on the API

        ```kotlin
        fun alsoInApi(): Int
        ```

        ## Behavior

        ```kotlin
        fun exampleOnly() = tokenize("1 + 2")
        ```
    """.trimIndent()

    @Test
    fun `reads packages and declarations from the API section only`() {
        val api = SpecApi.parse(spec)

        assertThat(api.packages).containsExactly("demo.lexer")
        assertThat(api.declarations).containsExactlyInAnyOrder("TokenType", "Token", "type", "text", "tokenize", "second", "alsoInApi")
    }

    @Test
    fun `a spec without an API section is empty`() {
        assertThat(SpecApi.parse("# Notes\n\n```kotlin\nfun x() = 1\n```").isEmpty).isTrue()
    }

    @Test
    fun `finds declarations with receivers and type parameters`() {
        val code = "package demo\n\nfun <T> List<T>.second(): T = this[1]\ninternal data class Token(val type: TokenType)"

        assertThat(SpecApi.declares(code, "second")).isTrue()
        assertThat(SpecApi.declares(code, "type")).isTrue()
        assertThat(SpecApi.declares(code, "tokenize")).isFalse()
        assertThat(SpecApi.declares("// fun tokenize(input: String)", "tokenize")).isFalse()
    }
}
