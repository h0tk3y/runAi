package dev.graidle.demo.calc.lexer

enum class TokenType { NUMBER, PLUS, MINUS, TIMES, DIVIDE, POWER, LEFT_PAREN, RIGHT_PAREN }

data class Token(val type: TokenType, val text: String, val position: Int)

class LexerException(message: String, val position: Int) : Exception(message)

private val SYMBOLS: Map<Char, TokenType> = mapOf(
    '+' to TokenType.PLUS,
    '-' to TokenType.MINUS,
    '*' to TokenType.TIMES,
    '/' to TokenType.DIVIDE,
    '^' to TokenType.POWER,
    '(' to TokenType.LEFT_PAREN,
    ')' to TokenType.RIGHT_PAREN,
)

fun tokenize(input: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            c.isWhitespace() -> i++
            c.isDigitChar() -> {
                val end = numberEnd(input, i)
                tokens += Token(TokenType.NUMBER, input.substring(i, end), i)
                i = end
            }
            else -> {
                val type = SYMBOLS[c] ?: throw unexpected(c, i)
                tokens += Token(type, c.toString(), i)
                i++
            }
        }
    }
    return tokens.toList()
}

private fun Char.isDigitChar(): Boolean = this in '0'..'9'

private fun digitsEnd(input: String, start: Int): Int {
    var i = start
    while (i < input.length && input[i].isDigitChar()) i++
    return i
}

private fun numberEnd(input: String, start: Int): Int {
    val intEnd = digitsEnd(input, start)
    if (intEnd >= input.length || input[intEnd] != '.') return intEnd
    val fractionStart = intEnd + 1
    if (fractionStart >= input.length || !input[fractionStart].isDigitChar()) {
        throw unexpected('.', intEnd)
    }
    return digitsEnd(input, fractionStart)
}

private fun unexpected(c: Char, position: Int): LexerException =
    LexerException("Unexpected character '$c'", position)
