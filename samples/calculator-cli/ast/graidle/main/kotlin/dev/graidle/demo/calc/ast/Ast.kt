package dev.graidle.demo.calc.ast

import java.math.BigDecimal

enum class BinaryOperator(val symbol: Char) {
    PLUS('+'), MINUS('-'), TIMES('*'), DIVIDE('/'), POWER('^')
}

sealed interface Expr

data class NumberLiteral(val value: Double) : Expr

data class Negate(val operand: Expr) : Expr

data class Binary(val operator: BinaryOperator, val left: Expr, val right: Expr) : Expr

fun Expr.render(): String = when (this) {
    is NumberLiteral -> renderNumber(value)
    is Negate -> "-" + operand.render()
    is Binary -> "(" + left.render() + " " + operator.symbol + " " + right.render() + ")"
}

private fun renderNumber(value: Double): String =
    if (value.isFinite() && value == Math.floor(value)) {
        BigDecimal(value).toBigInteger().toString()
    } else {
        value.toString()
    }
