package dev.graidle.demo.calc.parser

import dev.graidle.demo.calc.ast.Binary
import dev.graidle.demo.calc.ast.BinaryOperator
import dev.graidle.demo.calc.ast.Expr
import dev.graidle.demo.calc.ast.Negate
import dev.graidle.demo.calc.ast.NumberLiteral
import dev.graidle.demo.calc.lexer.Token
import dev.graidle.demo.calc.lexer.TokenType

class ParseException(message: String, val position: Int) : Exception(message)

fun parse(tokens: List<Token>): Expr {
    if (tokens.isEmpty()) throw ParseException("Empty expression", 0)
    val parser = Parser(tokens)
    val expr = parser.expression()
    parser.peek()?.let { throw unexpected(it) }
    return expr
}

private fun unexpected(token: Token): ParseException =
    ParseException("Unexpected '${token.text}'", token.position)

private class Parser(private val tokens: List<Token>) {
    private var index = 0

    private val endPosition: Int = tokens.last().let { it.position + it.text.length }

    fun peek(): Token? = tokens.getOrNull(index)

    private fun next(): Token = tokens[index++]

    private fun matchOperator(vararg types: TokenType): BinaryOperator? {
        val token = peek() ?: return null
        if (token.type !in types) return null
        index++
        return token.type.toOperator()
    }

    fun expression(): Expr {
        var left = term()
        while (true) {
            val op = matchOperator(TokenType.PLUS, TokenType.MINUS) ?: return left
            left = Binary(op, left, term())
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (true) {
            val op = matchOperator(TokenType.TIMES, TokenType.DIVIDE) ?: return left
            left = Binary(op, left, unary())
        }
    }

    private fun unary(): Expr {
        if (peek()?.type == TokenType.MINUS) {
            index++
            return Negate(unary())
        }
        return power()
    }

    private fun power(): Expr {
        val base = primary()
        val op = matchOperator(TokenType.POWER) ?: return base
        return Binary(op, base, unary())
    }

    private fun primary(): Expr {
        val token = peek() ?: throw ParseException("Unexpected end of expression", endPosition)
        return when (token.type) {
            TokenType.NUMBER -> {
                next()
                NumberLiteral(token.text.toDouble())
            }
            TokenType.LEFT_PAREN -> {
                next()
                val inner = expression()
                val close = peek() ?: throw ParseException("Missing ')'", endPosition)
                if (close.type != TokenType.RIGHT_PAREN) throw unexpected(close)
                next()
                inner
            }
            else -> throw unexpected(token)
        }
    }
}

private fun TokenType.toOperator(): BinaryOperator? = when (this) {
    TokenType.PLUS -> BinaryOperator.PLUS
    TokenType.MINUS -> BinaryOperator.MINUS
    TokenType.TIMES -> BinaryOperator.TIMES
    TokenType.DIVIDE -> BinaryOperator.DIVIDE
    TokenType.POWER -> BinaryOperator.POWER
    else -> null
}
