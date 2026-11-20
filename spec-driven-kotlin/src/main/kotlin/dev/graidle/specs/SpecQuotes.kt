package dev.graidle.specs

import java.io.File

/** A spec as the task sees it: its module, its directory, and its Markdown files by relative path. */
data class ModuleSpecFiles(val module: String, val directory: File, val files: Map<String, String>)

/** Where a quote starts in a spec file. [line] and [column] start at 1. */
data class SpecLocation(val file: File, val line: Int, val column: Int) {

    /** `file:///path/to/spec.md:12:4`, the form Kotlin compiler errors use, which IDEs and terminals turn into links. */
    override fun toString(): String = "${file.absoluteFile.toPath().toUri()}:$line:$column"
}

/**
 * Finds quotes from the model in spec files. The match ignores Markdown emphasis and code marks, line breaks and
 * other runs of whitespace, case, and the final punctuation of the quote, because models drop or change those.
 */
object SpecQuotes {

    /** The shortest quote, after normalization, that counts as a statement. */
    const val MIN_LENGTH = 12

    private val ignored = setOf('`', '*', '>')

    fun normalizedLength(quote: String): Int = normalizeQuote(quote).length

    /** The location of [quote] in [spec], or null when no file of the spec contains it. */
    fun locate(quote: String, spec: ModuleSpecFiles): SpecLocation? {
        val wanted = normalizeQuote(quote)
        if (wanted.isEmpty()) return null
        for ((path, text) in spec.files) {
            val (normalized, offsets) = normalize(text)
            val index = normalized.indexOf(wanted)
            if (index < 0) continue
            // Start at a code or emphasis mark that opens the quoted text, such as the ` in `greet`.
            var offset = offsets[index]
            while (offset > 0 && text[offset - 1] in ignored) offset--
            val lineStart = text.lastIndexOf('\n', offset - 1) + 1
            val line = text.substring(0, offset).count { it == '\n' } + 1
            return SpecLocation(File(spec.directory, path), line, offset - lineStart + 1)
        }
        return null
    }

    private fun normalizeQuote(quote: String): String =
        normalize(quote).first.trim().trimEnd('.', ',', ';', ':').trim()

    /** The normalized text, and for each of its characters, the index of that character in [text]. */
    private fun normalize(text: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val offsets = ArrayList<Int>(text.length)
        text.forEachIndexed { i, c ->
            when {
                c in ignored -> {}
                c.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') {
                    out.append(' ')
                    offsets += i
                }
                else -> {
                    // Char by char, so offsets stay one-to-one with the text.
                    out.append(c.lowercaseChar())
                    offsets += i
                }
            }
        }
        return out.toString() to offsets.toIntArray()
    }
}
