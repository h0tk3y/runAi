package dev.graidle.demo.calc.evaluator

import dev.graidle.demo.calc.ast.Binary
import dev.graidle.demo.calc.ast.BinaryOperator
import dev.graidle.demo.calc.ast.Expr
import dev.graidle.demo.calc.ast.Negate
import dev.graidle.demo.calc.ast.NumberLiteral

class EvaluationException(message: String) : Exception(message)

fun evaluate(expr: Expr): Double = when (expr) {
    is NumberLiteral -> finite(expr.value)
    is Negate -> finite(-evaluate(expr.operand))
    is Binary -> evaluateBinary(expr)
}

private fun evaluateBinary(expr: Binary): Double {
    val left = evaluate(expr.left)
    val right = evaluate(expr.right)
    val result = when (expr.operator) {
        BinaryOperator.PLUS -> left + right
        BinaryOperator.MINUS -> left - right
        BinaryOperator.TIMES -> left * right
        BinaryOperator.DIVIDE -> {
            if (right == 0.0) throw EvaluationException("Division by zero")
            left / right
        }
        BinaryOperator.POWER -> Math.pow(left, right)
    }
    return finite(result)
}

private fun finite(value: Double): Double {
    if (!value.isFinite()) throw EvaluationException("Result is not a finite number")
    return value
}
