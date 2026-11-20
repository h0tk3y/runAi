# Evaluator

Computes the value of a syntax tree.

## API

```kotlin
package dev.graidle.demo.calc.evaluator

import dev.graidle.demo.calc.ast.Expr

class EvaluationException(message: String) : Exception(message)

fun evaluate(expr: Expr): Double
```

## Behavior

1. `evaluate` uses `Double` arithmetic: `NumberLiteral` is its value, `Negate` is the negated value of its operand, and `Binary` applies its operator to the values of its operands.
2. `POWER` uses `Math.pow`: `2 ^ 10` is `1024.0`, and `2 ^ -1` is `0.5`.
3. A division whose right operand evaluates to `0.0` or `-0.0` throws `EvaluationException("Division by zero")`.
4. A result that is not finite (infinite or NaN), at any step, throws `EvaluationException("Result is not a finite number")`. For example, `10 ^ 400` and `(-8) ^ 0.5`.
