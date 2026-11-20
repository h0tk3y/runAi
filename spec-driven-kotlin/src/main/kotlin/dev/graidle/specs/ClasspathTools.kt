package dev.graidle.specs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.graidle.ai.AiTool

/**
 * Read-only tools over a module's compile classpath. They show the public API of the libraries and dependency
 * modules, never method bodies, so what the model can see matches what the fingerprint covers.
 */
object ClasspathTools {

    /** The most characters one tool result returns, so a large library cannot flood the conversation. */
    private const val OUTPUT_LIMIT = 30_000
    private const val SEARCH_LIMIT = 60

    private val mapper = ObjectMapper()

    val PROMPT = """
        Tools: the compile classpath of this module holds the libraries and the dependency modules that the build declares. The tools list_packages, list_classes, describe_class and search_classpath show their public API. Before you use a library or a dependency module, look up the classes and members you need, and use them exactly as the tools show them. You can use only what is on the classpath, and the Java standard library.
        On the JVM, top-level Kotlin functions and properties are in a class named after their file, for example GreetingKt, and an extension function takes its receiver as the first parameter.
    """.trimIndent()

    fun tools(index: ClasspathIndex): List<AiTool> = listOf(
        AiTool(
            name = "list_packages",
            description = "Lists the packages on the compile classpath, with the number of public classes and the jars or modules they come from.",
            inputSchema = schema(),
        ) {
            index.packages.entries.joinToString("\n") { (pkg, classes) ->
                "${pkg.ifEmpty { "(default package)" }} (${classes.size} classes, from ${index.packageEntries[pkg].orEmpty().joinToString()})"
            }.limited()
        },
        AiTool(
            name = "list_classes",
            description = "Lists the public classes of one package on the compile classpath.",
            inputSchema = schema("package" to "The package name, for example kotlin.collections."),
        ) { input ->
            val pkg = mapper.readTree(input).path("package").asText()
            val classes = index.packages[pkg] ?: return@AiTool "There is no package '$pkg' on the classpath. Use list_packages or search_classpath."
            classes.joinToString("\n") { "${it.kind} ${it.name}" + if (it.isKotlin) "  // Kotlin" else "" }.limited()
        },
        AiTool(
            name = "describe_class",
            description = "Shows the public API of one class on the compile classpath: its declaration, and its public and protected constructors, methods and fields. Nested classes use '$', for example a.b.Outer\$Inner.",
            inputSchema = schema("name" to "The binary class name, for example kotlin.text.Regex or demo.GreetingKt."),
        ) { input ->
            val name = mapper.readTree(input).path("name").asText()
            val api = index.classes[name] ?: return@AiTool "There is no public class '$name' on the classpath. Use search_classpath."
            (listOf(api.declaration) + index.membersOf(api).map { "  $it" }).joinToString("\n").limited()
        },
        AiTool(
            name = "search_classpath",
            description = "Finds public classes and members on the compile classpath whose simple name contains the query, ignoring case. Returns at most $SEARCH_LIMIT results.",
            inputSchema = schema("query" to "Part of a class or member name, for example Regex or capitalize."),
        ) { input ->
            val query = mapper.readTree(input).path("query").asText().lowercase()
            if (query.isBlank()) return@AiTool "The query is empty."
            val results = sequence {
                index.classes.values.sortedBy { it.name }.forEach { c ->
                    if (c.name.substringAfterLast('.').lowercase().contains(query)) yield("${c.kind} ${c.name}")
                    index.membersOf(c).forEach { m -> if (memberName(m).lowercase().contains(query)) yield("${c.name}: $m") }
                }
            }.take(SEARCH_LIMIT + 1).toList()
            when {
                results.isEmpty() -> "Nothing on the classpath matches '$query'."
                results.size > SEARCH_LIMIT -> (results.take(SEARCH_LIMIT) + "(more results; use a longer query)").joinToString("\n")
                else -> results.joinToString("\n")
            }.limited()
        },
    )

    private fun memberName(member: String): String =
        member.substringBefore('(').substringBefore(':').trim().substringAfterLast(' ')

    private fun String.limited(): String =
        if (length <= OUTPUT_LIMIT) this else take(OUTPUT_LIMIT) + "\n(cut off: the result is longer than $OUTPUT_LIMIT characters)"

    private fun schema(vararg properties: Pair<String, String>): String {
        val root = mapper.createObjectNode().put("type", "object")
        val props = root.putObject("properties")
        properties.forEach { (name, description) -> props.putObject(name).put("type", "string").put("description", description) }
        root.putArray("required").apply { properties.forEach { add(it.first) } }
        root.put("additionalProperties", false)
        return mapper.writeValueAsString(root)
    }
}
