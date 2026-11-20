package dev.runai.android

import org.xml.sax.SAXException

/** Fixed rules that a translated Android string value must follow. */
object AndroidStringChecks {

    /** Format specifiers such as `%s`, `%1$d`, `%.2f` and `%%`. A space flag is not matched, so "100 % sûr" is text. */
    private val formatSpecifier = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    private val openingTag = Regex("""<\s*([A-Za-z][\w:.-]*)""")
    private val xliffElement = Regex("""<xliff:g\b[^>]*>(.*?)</xliff:g>""", RegexOption.DOT_MATCHES_ALL)

    val pluralQuantityOrder = listOf("zero", "one", "two", "few", "many", "other")
    val pluralQuantities = pluralQuantityOrder.toSet()

    fun formatSpecifiers(value: String): List<String> = formatSpecifier.findAll(value).map { it.value }.sorted().toList()

    fun tagNames(value: String): List<String> = openingTag.findAll(value).map { it.groupValues[1] }.sorted().toList()

    fun xliffContents(value: String): List<String> = xliffElement.findAll(value).map { it.groupValues[1] }.sorted().toList()

    /** Returns a description of the XML error, or null if [value] is a well-formed fragment. */
    fun xmlError(value: String): String? = try {
        StringResourcesXml.newDocumentBuilder().parse("<value>$value</value>".byteInputStream())
        null
    } catch (e: SAXException) {
        e.message ?: "not well-formed XML"
    }

    /**
     * Applies the fixes that need no judgment: escapes apostrophes and double quotes outside tags, and escapes a
     * leading `@` or `?` that would otherwise make the value a resource reference.
     */
    fun normalize(target: String, source: String): String {
        var result = if (isQuoted(source)) target else escapeQuotes(target)
        if (!StringResourcesXml.isReference(source) && (result.startsWith("@") || result.startsWith("?"))) {
            result = "\\" + result
        }
        return result
    }

    /** Problems of a translated `<string>` value, compared with its source value. */
    fun checkString(source: String, target: String): List<String> {
        if (target.isBlank()) return listOf("the translation is empty")
        xmlError(target)?.let { return listOf("the value is not well-formed XML: $it") }
        val problems = mutableListOf<String>()
        compare("format specifiers", formatSpecifiers(source), formatSpecifiers(target))?.let(problems::add)
        compare("XML tags", tagNames(source), tagNames(target))?.let(problems::add)
        compare("<xliff:g> contents", xliffContents(source), xliffContents(target))?.let(problems::add)
        return problems
    }

    /** Problems of a translated `<plurals>`, compared with its source items. */
    fun checkPlurals(source: Map<String, String>, target: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        val unknown = target.keys - pluralQuantities
        if (unknown.isNotEmpty()) problems += "unknown quantities ${unknown.sorted()}; use only ${pluralQuantities.sorted()}"
        if ("other" !in target) problems += "the quantity 'other' is missing"

        val sourceSpecifiers = source.values.flatMap(::formatSpecifiers).toSet()
        val sourceXliff = source.values.flatMap(::xliffContents).toSet()
        for ((quantity, value) in target) {
            if (value.isBlank()) {
                problems += "quantity '$quantity': the translation is empty"
                continue
            }
            xmlError(value)?.let { problems += "quantity '$quantity': the value is not well-formed XML: $it" }
            val extra = formatSpecifiers(value).toSet() - sourceSpecifiers
            if (extra.isNotEmpty()) problems += "quantity '$quantity': format specifiers $extra are not in the source"
            val extraXliff = xliffContents(value).toSet() - sourceXliff
            if (extraXliff.isNotEmpty()) problems += "quantity '$quantity': <xliff:g> contents $extraXliff are not in the source"
        }
        val sourceOther = source["other"]
        val targetOther = target["other"]
        if (sourceOther != null && targetOther != null) {
            compare("format specifiers of 'other'", formatSpecifiers(sourceOther), formatSpecifiers(targetOther))?.let(problems::add)
        }
        return problems
    }

    private fun compare(what: String, expected: List<String>, actual: List<String>): String? =
        if (expected == actual) null else "$what must be $expected, found $actual"

    private fun isQuoted(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') && !trimmed.endsWith("\\\"")
    }

    private fun escapeQuotes(value: String): String = buildString {
        var inTag = false
        var inCdata = false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                !inTag && !inCdata && value.startsWith("<![CDATA[", i) -> {
                    inCdata = true
                    append("<![CDATA[")
                    i += 9
                    continue
                }
                inCdata && value.startsWith("]]>", i) -> {
                    inCdata = false
                    append("]]>")
                    i += 3
                    continue
                }
                !inCdata && c == '<' -> inTag = true
                !inCdata && c == '>' -> inTag = false
                !inTag && (c == '\'' || c == '"') && (i == 0 || value[i - 1] != '\\') -> append('\\')
            }
            append(c)
            i++
        }
    }
}
