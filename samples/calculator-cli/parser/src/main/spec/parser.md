# Parser

Builds a syntax tree from the tokens of an arithmetic expression.

## API

```kotlin
package dev.graidle.demo.calc.parser

import dev.graidle.demo.calc.ast.Expr
import dev.graidle.demo.calc.lexer.Token

class ParseException(message: String, val position: Int) : Exception(message)

fun parse(tokens: List<Token>): Expr
```

## Behavior

1. `parse` follows this grammar, from the lowest to the highest precedence:

   ```
   expression := term (("+" | "-") term)*
   term       := unary (("*" | "/") unary)*
   unary      := "-" unary | power
   power      := primary ("^" unary)?
   primary    := NUMBER | "(" expression ")"
   ```

   So `+`, `-`, `*` and `/` are left-associative, `^` is right-associative, and unary minus binds less tightly than `^`: `2 ^ 3 ^ 2` is `2 ^ (3 ^ 2)`, and `-2 ^ 2` is `-(2 ^ 2)`.
2. A `NUMBER` token becomes a `NumberLiteral` whose value is the token text converted to `Double`. A unary minus becomes `Negate`, and a binary operator becomes `Binary` with the matching `BinaryOperator`.
3. The end position of the input is the position of the last token plus the length of its text.
4. An empty token list throws `ParseException("Empty expression", 0)`.
5. When the input ends where a number, `-` or `(` is needed, `parse` throws `ParseException("Unexpected end of expression", end position)`.
6. When the input ends where `)` is needed, `parse` throws `ParseException("Missing ')'", end position)`.
7. Any token where it cannot continue the expression, including tokens left after a complete expression, throws `ParseException("Unexpected 'text'", position of the token)`, where `text` is the token text.
