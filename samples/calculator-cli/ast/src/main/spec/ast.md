# AST

The syntax tree of an arithmetic expression.

## API

```kotlin
package dev.runai.demo.calc.ast

enum class BinaryOperator(val symbol: Char) {
    PLUS('+'), MINUS('-'), TIMES('*'), DIVIDE('/'), POWER('^')
}

sealed interface Expr

data class NumberLiteral(val value: Double) : Expr

data class Negate(val operand: Expr) : Expr

data class Binary(val operator: BinaryOperator, val left: Expr, val right: Expr) : Expr

fun Expr.render(): String
```

## Behavior

1. `render` writes every `Binary` in parentheses, with spaces around the operator symbol. `Binary(PLUS, 1, Binary(TIMES, 2, 3))` renders as `(1 + (2 * 3))`.
2. `render` writes a whole number without a fractional part, and other numbers like `Double.toString()`. `2.0` renders as `2`, and `2.5` as `2.5`.
  Files outside Long range and non-fractional files, including -0, NaN, Infinity, and -Infinity, should be written by Double.toString.
3. `render` writes `Negate` as `-` followed by the rendered operand: `-3`, `-(1 + 2)`.
