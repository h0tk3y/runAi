package dev.runai.specs

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** A contradiction between the spec of this module and the spec of a dependency, as the model reports it. */
data class SpecContradiction(
    val dependencyModule: String,
    val dependencyQuote: String,
    val ownQuote: String,
    val explanation: String,
)

/** A decision that a spec leaves open, and that the model would have to guess. */
data class SpecUnclearPoint(val module: String, val quote: String, val question: String)

data class SpecIssues(val contradictions: List<SpecContradiction>, val unclear: List<SpecUnclearPoint>)

/**
 * The model must not guess. When this module's spec clearly contradicts a dependency's spec, or leaves open a
 * decision that changes what the API does, the model reports it instead, with word-for-word quotes. Fixed rules check
 * that each quote appears in the spec it names, so an invented report goes back to the model as a problem. A checked
 * report fails the task, and the message points to each quote by file, line and column.
 */
object SpecIssueReports {

    // First: SCHEMAS below uses it while the object initializes.
    private val mapper = ObjectMapper()

    const val CONTRADICTION = "contradiction"
    const val UNCLEAR = "unclear"
    const val CONTRADICTIONS_PROPERTY = "contradictions"
    const val UNCLEAR_PROPERTY = "unclear"

    val STATUSES = listOf(CONTRADICTION, UNCLEAR)

    val SCHEMAS: Map<String, String> = mapOf(
        CONTRADICTIONS_PROPERTY to arraySchema("dependencyModule", "dependencyQuote", "ownQuote", "explanation"),
        UNCLEAR_PROPERTY to arraySchema("module", "quote", "question"),
    )

    /** For modules with dependencies. */
    val CONTRADICTION_RULE = """
        Before you answer, compare the specification of this module with the specifications of the dependency modules. If they clearly contradict each other, do not choose one of them, and do not write any files. A clear contradiction is two statements that cannot both be true, for example a different type, name, error message or result for the same case. Missing details, statements about different cases, and differences that both specifications allow are not contradictions.
        For each contradiction, give the path of the dependency module, quote the statement of the dependency module and the statement of this module word for word, and explain in one sentence why they cannot both be true. Set "status" to "contradiction", and leave "files" empty. When there is no contradiction, leave "contradictions" empty.
    """.trimIndent()

    val UNCLEAR_RULE = """
        If the specifications leave open a decision that changes what the public API does, so that you would have to guess, do not guess, and do not write any files. Decide internal details yourself: they are not unclear. For each open decision, give the path of the module whose specification must answer it, quote the statement of that specification that comes closest to it word for word, and ask one question that the author can answer by adding to the specification. Set "status" to "unclear", and leave "files" empty. When nothing is unclear, leave "unclear" empty.
    """.trimIndent()

    /** The issues that [answer] reports, or null when it reports none. */
    fun read(answer: String): SpecIssues? {
        val root = try {
            mapper.readTree(answer)
        } catch (e: JsonProcessingException) {
            return null
        }
        val issues = SpecIssues(
            contradictions = root.path(CONTRADICTIONS_PROPERTY).map {
                SpecContradiction(it.text("dependencyModule"), it.text("dependencyQuote"), it.text("ownQuote"), it.text("explanation"))
            },
            unclear = root.path(UNCLEAR_PROPERTY).map { SpecUnclearPoint(it.text("module"), it.text("quote"), it.text("question")) },
        )
        val reported = root.path("status").asText() in STATUSES || issues.contradictions.isNotEmpty() || issues.unclear.isNotEmpty()
        return if (reported) issues else null
    }

    /** Problems of a report: unknown modules, and quotes that do not appear in the spec they name. */
    fun check(issues: SpecIssues, own: ModuleSpecFiles, dependencies: List<ModuleSpecFiles>): List<String> {
        if (issues.contradictions.isEmpty() && issues.unclear.isEmpty()) {
            return listOf("\"status\" reports an issue, but \"contradictions\" and \"unclear\" are empty")
        }
        val dependencyByModule = dependencies.associateBy { it.module }
        val anyByModule = dependencyByModule + (own.module to own)
        val problems = mutableListOf<String>()
        issues.contradictions.forEachIndexed { index, c ->
            val label = "contradiction ${index + 1}"
            val dependency = dependencyByModule[c.dependencyModule]
            if (dependency == null) {
                problems += "$label names '${c.dependencyModule}', which is not a dependency module. Use one of: " +
                    dependencyByModule.keys.sorted().joinToString().ifEmpty { "none" }
            } else {
                quoteProblem(label, c.dependencyQuote, dependency)?.let(problems::add)
            }
            quoteProblem(label, c.ownQuote, own)?.let(problems::add)
            if (c.explanation.isBlank()) problems += "$label has no explanation"
        }
        issues.unclear.forEachIndexed { index, u ->
            val label = "unclear point ${index + 1}"
            val spec = anyByModule[u.module]
            if (spec == null) {
                problems += "$label names '${u.module}'. Use this module or a dependency module: ${anyByModule.keys.sorted().joinToString()}"
            } else {
                quoteProblem(label, u.quote, spec)?.let(problems::add)
            }
            if (u.question.isBlank()) problems += "$label has no question"
        }
        return problems
    }

    /** The failure message for a checked report. */
    fun message(taskPath: String, issues: SpecIssues, own: ModuleSpecFiles, dependencies: List<ModuleSpecFiles>): String = buildString {
        val modules = issues.contradictions.map { it.dependencyModule }.distinct().sorted()
        val of = if (modules.size == 1) "its dependency ${modules.single()}" else "its dependencies ${modules.joinToString()}"
        val contradicts = "contradicts the specification of $of"
        val unclear = "is not clear enough to generate the result without guessing"
        val what = when {
            issues.unclear.isEmpty() -> contradicts
            issues.contradictions.isEmpty() -> unclear
            else -> "$contradicts, and it $unclear"
        }
        append("The specification of ${own.module} $what, so runAi did not generate anything.\n")
        append("Resolve the points below in the specifications, then run '$taskPath' again.\n")

        val byModule = dependencies.associateBy { it.module } + (own.module to own)
        var n = 0
        issues.contradictions.forEach { c ->
            append('\n').append(++n).append(". Contradiction\n")
            appendQuote(byModule.getValue(c.dependencyModule), c.dependencyQuote)
            appendQuote(own, c.ownQuote)
            append("   Why they conflict: ").append(c.explanation.trim()).append('\n')
        }
        issues.unclear.forEach { u ->
            append('\n').append(++n).append(". Unclear\n")
            appendQuote(byModule.getValue(u.module), u.quote)
            append("   Question: ").append(u.question.trim()).append('\n')
        }
    }

    private fun StringBuilder.appendQuote(spec: ModuleSpecFiles, quote: String) {
        // The location ends its line, so that terminals do not take following text into the link.
        append("   ").append(spec.module).append(" at ").append(SpecQuotes.locate(quote, spec) ?: spec.directory).append('\n')
        append("     \"").append(quote.trim()).append("\"\n")
    }

    private fun quoteProblem(label: String, quote: String, spec: ModuleSpecFiles): String? {
        val what = "the specification of ${spec.module}"
        return when {
            SpecQuotes.normalizedLength(quote) < SpecQuotes.MIN_LENGTH -> "$label: quote a whole statement from $what, not \"$quote\""
            SpecQuotes.locate(quote, spec) == null -> "$label: the quote \"$quote\" does not appear in $what. Quote it word for word."
            else -> null
        }
    }

    private fun JsonNode.text(name: String): String = path(name).asText()

    private fun arraySchema(vararg fields: String): String {
        val array = mapper.createObjectNode().put("type", "array")
        val item = array.putObject("items").put("type", "object")
        val properties = item.putObject("properties")
        fields.forEach { properties.putObject(it).put("type", "string") }
        item.putArray("required").apply { fields.forEach { add(it) } }
        item.put("additionalProperties", false)
        return mapper.writeValueAsString(array)
    }
}
