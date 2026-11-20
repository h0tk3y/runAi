package dev.graidle.demo.calc.cli

import dev.graidle.demo.calc.evaluator.EvaluationException
import dev.graidle.demo.calc.evaluator.evaluate
import dev.graidle.demo.calc.format.formatNumber
import dev.graidle.demo.calc.lexer.LexerException
import dev.graidle.demo.calc.lexer.tokenize
import dev.graidle.demo.calc.parser.ParseException
import dev.graidle.demo.calc.parser.parse
import kotlin.system.exitProcess

private const val USAGE =
    "Usage: calc <expression>\n" +
        "Evaluates an arithmetic expression with + - * / ^ and parentheses.\n"

fun run(args: List<String>, out: Appendable, err: Appendable): Int {
    if (args.isEmpty() || (args.size == 1 && (args[0] == "--help" || args[0] == "-h"))) {
        out.append(USAGE)
        return 0
    }
    val expression = args.joinToString(" ")
    return try {
        val value = evaluate(parse(tokenize(expression)))
        out.append(formatNumber(value)).append("\n")
        0
    } catch (e: LexerException) {
        reportPositioned(err, e.message.orEmpty(), expression, e.position)
    } catch (e: ParseException) {
        reportPositioned(err, e.message.orEmpty(), expression, e.position)
    } catch (e: EvaluationException) {
        err.append("Error: ").append(e.message.orEmpty()).append("\n")
        1
    }
}

private fun reportPositioned(err: Appendable, message: String, expression: String, position: Int): Int {
    err.append("Error: ").append(message).append("\n")
    err.append("  ").append(expression).append("\n")
    err.append("  ").append(" ".repeat(position.coerceAtLeast(0))).append("^\n")
    return 1
}

fun main(args: Array<String>) {
    val code = run(args.toList(), System.out, System.err)
    System.out.flush()
    System.err.flush()
    exitProcess(code)
}
