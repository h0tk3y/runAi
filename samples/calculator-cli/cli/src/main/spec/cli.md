# Calculator command line

The `calc` command: evaluates the arithmetic expression given as its arguments. It uses the Clikt library (`com.github.ajalt.clikt`) to define and parse its command line.

## API

```kotlin
package dev.graidle.demo.calc.cli

fun run(args: List<String>, out: Appendable, err: Appendable): Int

fun main(args: Array<String>)
```

## Behavior

1. The command line is a Clikt command named `calc`, with the help text `Evaluates an arithmetic expression with + - * / ^ and parentheses.` and one argument, `expression`, that takes one or more values.
2. The expression is the values of `expression` joined with single spaces, so `calc 2 + 3` and `calc "2+3"` both evaluate `2+3`. Clikt reads a value that starts with `-` as an option, so an expression that starts with a negative number needs `--` before it: `calc -- -2 + 3`.
3. `run` tokenizes the expression with the lexer module, parses the tokens with the parser module, evaluates the tree with the evaluator module, and formats the value with the format module.
4. On success, `run` writes the formatted value and a line break to `out`, and returns `0`. For example, `2 * (3 + 4)` writes `14`, and `1 / 3` writes `0.3333333333`.
5. With `--help` or `-h`, or with no arguments, `run` writes the help text that Clikt formats for the command to `out`, and returns `0`. The help text starts with `Usage: calc`.
6. When Clikt reports any other problem with the command line, such as an unknown option, `run` writes the message that Clikt formats for it, and a line break, to `err`, and returns `1`.
7. When the lexer or the parser throws, `run` writes these three lines to `err`, and returns `1`. The caret is under the error position: two spaces, then as many spaces as the position, then `^`.

   ```
   Error: <message>
     <expression>
     <spaces>^
   ```

   For example, `2 * (3` writes `Error: Missing ')'`, then `  2 * (3`, then `  ` followed by six spaces and `^`.
8. When the evaluator throws, `run` writes `Error: <message>` and a line break to `err`, and returns `1`.
9. `run` writes only to `out` and `err`, never to `System.out` or `System.err` directly, so that callers can capture the output.
10. `main` is in the file `Main.kt`. It calls `run` with the arguments, `System.out` and `System.err`, and exits the process with the returned code.
