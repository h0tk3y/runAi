package dev.runai.specs

/** Fixed rules for generated Kotlin sources. They need no compiler. */
object KotlinSourceChecks {

    private val packageLine = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val importLine = Regex("""^\s*import\s+([\w.]+?)(\.\*)?(?:\s+as\s+\w+)?\s*$""", RegexOption.MULTILINE)

    /** Packages from the Kotlin and Java standard libraries. */
    val STANDARD_LIBRARY_PREFIXES = listOf("kotlin.", "java.")

    /**
     * Checks each file: a `.kt` path that matches its package, a package of this module, and imports only from
     * [allowedPrefixes] or from [allowedPackages] (with their sub-packages), never from [forbiddenPrefixes].
     */
    fun checkFiles(
        files: Map<String, String>,
        ownPackages: Set<String>,
        allowedPackages: Set<String>,
        allowedPrefixes: List<String> = STANDARD_LIBRARY_PREFIXES,
        forbiddenPrefixes: List<String> = emptyList(),
    ): List<String> {
        if (files.isEmpty()) return listOf("the answer contains no Kotlin files")
        val problems = mutableListOf<String>()
        for ((path, content) in files) {
            if (!path.endsWith(".kt")) {
                problems += "'$path' is not a Kotlin file: its name must end in .kt"
                continue
            }
            val pkg = packageLine.find(content)?.groupValues?.get(1)
            if (pkg == null) {
                problems += "'$path' has no package declaration"
            } else {
                val expectedDir = pkg.replace('.', '/')
                if (path.substringBeforeLast('/', "") != expectedDir) {
                    problems += "'$path' must be in the directory '$expectedDir', because its package is '$pkg'"
                }
                if (ownPackages.isNotEmpty() && ownPackages.none { pkg == it || pkg.startsWith("$it.") }) {
                    problems += "'$path' uses the package '$pkg'; use a package from the API section: ${ownPackages.sorted().joinToString()}"
                }
            }
            for (match in importLine.findAll(content)) {
                val imported = match.groupValues[1]
                val allowed = (allowedPrefixes.any { imported.startsWith(it) } ||
                    (allowedPackages + ownPackages).any { imported == it || imported.startsWith("$it.") }) &&
                    forbiddenPrefixes.none { imported.startsWith(it) || "$imported." == it }
                if (!allowed) {
                    problems += "'$path': the import of $imported is not allowed. Use only the Kotlin and Java standard " +
                        "libraries and the API of the dependency modules."
                }
            }
        }
        return problems
    }

    /** The names declared in [api] that none of [files] declares. */
    fun missingDeclarations(files: Map<String, String>, api: SpecApi): List<String> {
        val code = files.values.joinToString("\n")
        return api.declarations.filterNot { SpecApi.declares(code, it) }.sorted()
            .map { "'$it' from the API section is not declared" }
    }
}
