package dev.runai.tasks

import java.io.File

/**
 * A fixed rule for the result of an [RunAi], for example "the JSON parses" or "every row has five columns".
 * Implement it as a class in `buildSrc`, a convention plugin or a plugin, and add it to [RunAi.checks].
 *
 * The task runs every check on each answer from the model, and sends the problems back to the model on the next
 * attempt. It also runs the checks on a committed result, for example after a hand edit.
 *
 * Gradle tracks the implementation class of a check. Declare a check's settings as properties with input
 * annotations, such as `@get:Input`. Do not use a lambda: Gradle cannot track it.
 */
interface AiResultCheck {

    /** Returns the problems found in [outputDir]. An empty list means the result follows the rule. */
    fun check(outputDir: File): List<String>
}
