package dev.graidle.android

import java.io.File

/** Checks a committed translation file against the source resources, with the same rules as a model answer. */
object TranslationCheck {

    fun verify(source: List<StringResource>, translationFile: File): List<String> {
        if (!translationFile.isFile) return listOf("${translationFile.name} is missing")
        val translated = try {
            StringResourcesXml.parse(translationFile).entries.associateBy { it.name }
        } catch (e: Exception) {
            return listOf("${translationFile.name} cannot be read: ${e.message}")
        }

        val problems = mutableListOf<String>()
        for (entry in source) {
            val target = translated[entry.name]
            when {
                target == null -> problems += "'${entry.name}' is missing"
                entry is StringEntry && target is StringEntry ->
                    AndroidStringChecks.checkString(entry.value, target.value).forEach { problems += "string '${entry.name}': $it" }
                entry is PluralsEntry && target is PluralsEntry ->
                    AndroidStringChecks.checkPlurals(entry.items, target.items).forEach { problems += "plurals '${entry.name}': $it" }
                else -> problems += "'${entry.name}' has a different resource type than in the source"
            }
        }
        val sourceNames = source.map { it.name }.toSet()
        translated.keys.filter { it !in sourceNames }.forEach { problems += "'$it' is not in the source strings" }
        return problems
    }
}
