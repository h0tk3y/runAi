package dev.graidle.specs

/**
 * The public API that a module's specification declares: the `kotlin` code blocks in its `API` section.
 *
 * A specification is Markdown. Its `## API` section (any heading level) holds one or more ```` ```kotlin ```` blocks
 * with the module's public declarations. The section ends at the next heading of the same or a higher level.
 */
data class SpecApi(
    /** The Kotlin code of the API section. */
    val code: String,
    /** The packages it declares. */
    val packages: Set<String>,
    /** The names it declares: classes, interfaces, objects, functions, properties and type aliases. */
    val declarations: Set<String>,
) {
    val isEmpty: Boolean get() = code.isBlank()

    companion object {
        private val heading = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
        private val openingFence = Regex("""^\s*```\s*([\w+-]*)\s*$""")
        private val packageLine = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        private val comment = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val declaration = Regex(
            """\b(?:class|interface|object|fun|typealias|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+?\.)?([A-Za-z_]\w*)"""
        )

        fun parse(markdown: String): SpecApi {
            val code = StringBuilder()
            var apiLevel: Int? = null
            var fenceLanguage: String? = null
            for (line in markdown.lines()) {
                if (fenceLanguage != null) {
                    if (line.trim() == "```") {
                        fenceLanguage = null
                    } else if (apiLevel != null && fenceLanguage == "kotlin") {
                        code.append(line).append('\n')
                    }
                    continue
                }
                val headingMatch = heading.matchEntire(line)
                if (headingMatch != null) {
                    val level = headingMatch.groupValues[1].length
                    val title = headingMatch.groupValues[2]
                    apiLevel = when {
                        title.equals("API", ignoreCase = true) -> level
                        apiLevel != null && level <= apiLevel -> null
                        else -> apiLevel
                    }
                    continue
                }
                openingFence.matchEntire(line)?.let { fenceLanguage = it.groupValues[1].lowercase() }
            }
            return of(code.toString())
        }

        fun of(code: String): SpecApi {
            val withoutComments = comment.replace(code, "")
            val withoutImports = withoutComments.lines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
            return SpecApi(
                code = code,
                packages = packageLine.findAll(withoutComments).map { it.groupValues[1] }.toSet(),
                declarations = declaration.findAll(withoutImports).map { it.groupValues[1] }.toSet(),
            )
        }

        /** True when [code] declares [name] the way an API section does. */
        fun declares(code: String, name: String): Boolean = Regex(
            """\b(?:class|interface|object|fun|typealias|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+?\.)?${Regex.escape(name)}\b"""
        ).containsMatchIn(comment.replace(code, ""))
    }
}
