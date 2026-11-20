package dev.graidle.android

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.graidle.ai.Validation
import java.util.Locale

/** Builds the translation prompt for a chunk of resources and checks the answer. */
class TranslationPrompt(
    private val sourceLanguage: String,
    private val targetQualifier: String,
    private val glossary: String?,
    private val instructions: String?,
) {
    init {
        require(androidQualifier.matches(targetQualifier)) {
            "'$targetQualifier' is not an Android locale qualifier. Use forms such as 'de', 'pt-rBR' or 'b+sr+Latn'."
        }
    }

    val targetLanguageTag: String = qualifierToLanguageTag(targetQualifier)

    fun render(chunk: List<StringResource>): String = buildString {
        val sourceName = displayName(sourceLanguage)
        val targetName = displayName(targetLanguageTag)
        append("Task: Translate Android string resources from $sourceName ($sourceLanguage) to $targetName ")
        append("($targetLanguageTag, Android qualifier \"$targetQualifier\").\n\n")
        append(
            """
            Rules:
            1. Translate every item in "strings" and "plurals". Return each item with the same "name".
            2. Each value is an XML fragment from an Android strings.xml file. Keep these parts unchanged:
               - format specifiers, for example %s, %1${'$'}s, %2${'$'}d, %.2f and %%
               - XML tags and their attributes, for example <b>, <i>, <u> and <a href="...">
               - the text inside <xliff:g> elements
               - escape sequences, for example \n, \t, \' and \"
               - XML entities, for example &amp; and &lt;
            3. Translate the text between the tags. Write apostrophes as \' and double quotes as \".
            4. For each item in "plurals", return the quantities that $targetName uses in the CLDR plural rules. Always include "other". Use only the quantities zero, one, two, few, many and other.
            5. Keep the meaning, tone and approximate length of the source. The text is shown in the user interface of a mobile app.
            """.trimIndent()
        )
        append('\n')
        if (!glossary.isNullOrBlank()) {
            append("6. Use the glossary below when its terms apply. Its translations take priority.\n\n")
            append("Glossary:\n").append(glossary.trim()).append('\n')
        }
        if (!instructions.isNullOrBlank()) {
            append("\nProject instructions:\n").append(instructions.trim()).append('\n')
        }
        append("\nInput:\n")
        append(canonicalInput(chunk))
        append('\n')
    }

    /** The resources as the model sees them, in a stable JSON form. Also used for the fingerprint. */
    fun canonicalInput(entries: List<StringResource>): String = mapper.writeValueAsString(inputJson(entries))

    /** Parses the answer for [chunk], applies the fixed escaping rules, and checks every value. */
    fun check(chunk: List<StringResource>, answer: String): Validation<List<StringResource>> {
        val root: JsonNode = try {
            mapper.readTree(answer)
        } catch (e: JsonProcessingException) {
            return Validation.Invalid(listOf("the answer is not valid JSON: ${e.originalMessage}"))
        }
        if (root.path("status").asText() == "cannot_complete") {
            return Validation.CannotComplete(root.path("reason").asText().ifBlank { "no reason given" })
        }

        val problems = mutableListOf<String>()
        val strings = linkedMapOf<String, String>()
        for (node in root.path("strings")) {
            val name = node.path("name").asText()
            if (strings.put(name, node.path("value").asText()) != null) problems += "string '$name' appears more than once"
        }
        val plurals = linkedMapOf<String, Map<String, String>>()
        for (node in root.path("plurals")) {
            val name = node.path("name").asText()
            val items = linkedMapOf<String, String>()
            for (item in node.path("items")) {
                val quantity = item.path("quantity").asText()
                if (items.put(quantity, item.path("value").asText()) != null) {
                    problems += "plurals '$name': quantity '$quantity' appears more than once"
                }
            }
            if (plurals.put(name, items) != null) problems += "plurals '$name' appears more than once"
        }

        val result = mutableListOf<StringResource>()
        for (entry in chunk) {
            when (entry) {
                is StringEntry -> {
                    val raw = strings.remove(entry.name)
                    if (raw == null) {
                        problems += "string '${entry.name}' is missing"
                        continue
                    }
                    val value = AndroidStringChecks.normalize(raw, entry.value)
                    AndroidStringChecks.checkString(entry.value, value).forEach { problems += "string '${entry.name}': $it" }
                    result += StringEntry(entry.name, value)
                }
                is PluralsEntry -> {
                    val raw = plurals.remove(entry.name)
                    if (raw == null) {
                        problems += "plurals '${entry.name}' is missing"
                        continue
                    }
                    val sourceSample = entry.items["other"] ?: entry.items.values.first()
                    val items = linkedMapOf<String, String>()
                    // Write quantities in CLDR order so the output does not depend on the answer's order.
                    AndroidStringChecks.pluralQuantityOrder.forEach { q -> raw[q]?.let { items[q] = AndroidStringChecks.normalize(it, sourceSample) } }
                    raw.keys.filter { it !in items }.forEach { items[it] = raw.getValue(it) }
                    AndroidStringChecks.checkPlurals(entry.items, items).forEach { problems += "plurals '${entry.name}': $it" }
                    result += PluralsEntry(entry.name, items)
                }
            }
        }
        strings.keys.forEach { problems += "string '$it' is not in the input" }
        plurals.keys.forEach { problems += "plurals '$it' is not in the input" }

        return if (problems.isEmpty()) Validation.Valid(result) else Validation.Invalid(problems)
    }

    private fun inputJson(chunk: List<StringResource>): JsonNode {
        val root = mapper.createObjectNode()
        val strings = root.putArray("strings")
        val plurals = root.putArray("plurals")
        for (entry in chunk) {
            when (entry) {
                is StringEntry -> strings.addObject().put("name", entry.name).put("value", entry.value)
                is PluralsEntry -> {
                    val node = plurals.addObject().put("name", entry.name)
                    val items = node.putArray("items")
                    entry.items.forEach { (quantity, value) -> items.addObject().put("quantity", quantity).put("value", value) }
                }
            }
        }
        return root
    }

    companion object {
        /** Change when the prompt text, the schema or the checks change. */
        const val VERSION = 1

        private val mapper = ObjectMapper()

        private val androidQualifier = Regex("""[a-z]{2,3}(-r[A-Z]{2})?|b\+[a-zA-Z]{2,8}(\+[a-zA-Z0-9]{1,8})*""")

        val SCHEMA: String = """
            {
              "type": "object",
              "properties": {
                "status": { "type": "string", "enum": ["ok", "cannot_complete"] },
                "reason": { "type": "string" },
                "strings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "value": { "type": "string" }
                    },
                    "required": ["name", "value"],
                    "additionalProperties": false
                  }
                },
                "plurals": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "items": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "quantity": { "type": "string", "enum": ["zero", "one", "two", "few", "many", "other"] },
                            "value": { "type": "string" }
                          },
                          "required": ["quantity", "value"],
                          "additionalProperties": false
                        }
                      }
                    },
                    "required": ["name", "items"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["status", "reason", "strings", "plurals"],
              "additionalProperties": false
            }
        """.trimIndent()

        /** `pt-rBR` becomes `pt-BR`, `b+sr+Latn` becomes `sr-Latn`. */
        fun qualifierToLanguageTag(qualifier: String): String = when {
            qualifier.startsWith("b+") -> qualifier.removePrefix("b+").replace('+', '-')
            "-r" in qualifier -> qualifier.replace("-r", "-")
            else -> qualifier
        }

        private fun displayName(tag: String): String =
            Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }
    }
}
