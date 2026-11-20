# Lexer

Splits the text of an arithmetic expression into tokens.

## API

```kotlin
package dev.runai.demo.calc.lexer

enum class TokenType { NUMBER, PLUS, MINUS, TIMES, DIVIDE, POWER, LEFT_PAREN, RIGHT_PAREN }

data class Token(val type: TokenType, val text: String, val position: Int)

class LexerException(message: String, val position: Int) : Exception(message)

fun tokenize(input: String): List<Token>
```

## Behavior

1. Whitespace separates tokens, and can also come before the first token or after the last one. It is not part of any token.
2. A number is one or more digits, optionally followed by `.` and one or more digits: `3`, `42`, `0.5`. Its type is `NUMBER`, and its text is the digits as written.
3. `+`, `-`, `*`, `/`, `^`, `(` and `)` are one-character tokens of type `PLUS`, `MINUS`, `TIMES`, `DIVIDE`, `POWER`, `LEFT_PAREN` and `RIGHT_PAREN`.
4. `position` is the zero-based index of the first character of the token in the input.
5. A blank input gives an empty list.
6. Any other character throws `LexerException` with the message `Unexpected character 'c'`, where `c` is the character, and the position of that character.
7. A `.` that does not follow a digit, or that is not followed by a digit, throws `LexerException` with the message `Unexpected character '.'` and the position of the `.`. So `.5` and `5.` are errors.
