package dev.graidle.ai

/**
 * The committed result that a task replaces, with the reason in words for the model, such as "its inputs changed".
 * Tasks show it in their prompts, so the model keeps whatever the changes do not affect: a regenerated result then
 * differs from the committed one only where it has to.
 */
data class PreviousResult(val files: Map<String, String>, val reason: String) {

    /** A prompt section for tasks whose answer is a set of files; see [FileAnswers]. */
    fun renderForFiles(): String = buildString {
        append("The task generated a result before, shown below. It must be generated again, because ").append(reason).append('\n')
        append(
            """
            Read the previous result first. Keep every part of it that the changes do not affect exactly as it is, and change only what the changed inputs or the problems require. For a file that needs no change, set "keep" to true and leave "content" empty. Leave out files that are no longer needed.
            """.trimIndent()
        )
        append("\n\nPrevious result:\n\n")
        files.forEach { (path, text) ->
            append("<file path=\"").append(path).append("\">\n").append(text)
            if (!text.endsWith("\n")) append('\n')
            append("</file>\n\n")
        }
    }
}
