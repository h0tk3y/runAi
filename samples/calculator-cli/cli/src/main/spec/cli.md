# Calculator command line

The `calc` command: evaluates the arithmetic expression given as its arguments.

## API

```kotlin
package dev.graidle.demo.calc.cli

fun run(args: List<String>, out: Appendable, err: Appendable): Int

fun main(args: Array<String>)
```

## Behavior

1. The expression is the arguments joined with single spaces, so `calc 2 + 3` and `calc "2+3"` both evaluate `2+3`.
2. `run` tokenizes the expression with the lexer module, parses the tokens with the parser module, evaluates the tree with the evaluator module, and formats the value with the format module.
3. On success, `run` writes the formatted value and a line break to `out`, and returns `0`. For example, `2 * (3 + 4)` writes `14`, and `1 / 3` writes `0.3333333333`.
4. With no arguments, or with `--help` or `-h` as the only argument, `run` writes this text to `out`, and returns `0`:

   ```
   Usage: calc <expression>
   Evaluates an arithmetic expression with + - * / ^ and parentheses.
   ```

5. When the lexer or the parser throws, `run` writes these three lines to `err`, and returns `1`. The caret is under the error position: two spaces, then as many spaces as the position, then `^`.

   ```
   Error: <message>
     <expression>
     <spaces>^
   ```

   For example, `2 * (3` writes `Error: Missing ')'`, then `  2 * (3`, then `  ` followed by six spaces and `^`.
6. When the evaluator throws, `run` writes `Error: <message>` and a line break to `err`, and returns `1`.
7. `main` is in the file `Main.kt`. It calls `run` with the arguments, `System.out` and `System.err`, and exits the process with the returned code.
