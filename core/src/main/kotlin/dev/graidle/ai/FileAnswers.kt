package dev.graidle.ai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * Helpers for tasks whose answer is a set of text files. The answer has the form
 * `{"status": "ok", "reason": "", "files": [{"path": "...", "content": "...", "keep": false}]}`. With `"keep": true`, a
 * file keeps its content from the previous result, byte for byte, and its `content` is ignored.
 *
 * The model never writes files: the task checks the answer and writes the files itself, so it can enforce which paths
 * are allowed.
 */
object FileAnswers {

    const val CONTENT_INSTRUCTION = "Put the complete content of each file in \"content\"."

    private val mapper = ObjectMapper()

    /**
     * The JSON schema of the answer. With [allowedPaths], the schema allows only those paths. [extraStatuses] adds
     * values for `status`, and [extraProperties] adds required top-level properties, each given as a JSON schema.
     * Tasks that add them read those parts of the answer themselves.
     */
    fun schema(
        allowedPaths: List<String>? = null,
        extraStatuses: List<String> = emptyList(),
        extraProperties: Map<String, String> = emptyMap(),
    ): String {
        val root = mapper.createObjectNode().put("type", "object")
        val properties = root.putObject("properties")
        properties.putObject("status").put("type", "string").putArray("enum").add("ok").add("cannot_complete")
            .apply { extraStatuses.forEach { add(it) } }
        properties.putObject("reason").put("type", "string")
        val item = properties.putObject("files").put("type", "array").putObject("items").put("type", "object")
        val itemProperties = item.putObject("properties")
        val path = itemProperties.putObject("path").put("type", "string")
        if (allowedPaths != null) path.putArray("enum").apply { allowedPaths.forEach { add(it) } }
        itemProperties.putObject("content").put("type", "string")
        itemProperties.putObject("keep").put("type", "boolean")
        item.putArray("required").add("path").add("content").add("keep")
        item.put("additionalProperties", false)
        extraProperties.forEach { (name, schema) -> properties.set<JsonNode>(name, mapper.readTree(schema)) }
        root.putArray("required").add("status").add("reason").add("files").apply { extraProperties.keys.forEach { add(it) } }
        root.put("additionalProperties", false)
        return mapper.writeValueAsString(root)
    }

    /**
     * Parses an answer into path-to-content pairs, in answer order. A file with `"keep": true` gets its content from
     * [previous]. Reports invalid JSON, unsafe or repeated paths, empty files, and files kept without a previous
     * version. Callers add their own rules for which paths are expected.
     */
    fun parse(answer: String, previous: Map<String, String> = emptyMap()): Validation<Map<String, String>> {
        val root = try {
            mapper.readTree(answer)
        } catch (e: JsonProcessingException) {
            return Validation.Invalid(listOf("the answer is not valid JSON: ${e.originalMessage}"))
        }
        if (root.path("status").asText() == "cannot_complete") {
            return Validation.CannotComplete(root.path("reason").asText().ifBlank { "no reason given" })
        }
        val problems = mutableListOf<String>()
        val files = linkedMapOf<String, String>()
        for (node in root.path("files")) {
            val path = node.path("path").asText()
            val keep = node.path("keep").asBoolean(false)
            val content = if (keep) previous[path] else node.path("content").asText()
            when {
                !isSafeRelativePath(path) -> problems += "'$path' is not a relative path with '/' separators"
                content == null -> problems += "'$path' has \"keep\" set, but the previous result has no such file"
                files.put(path, content) != null -> problems += "'$path' appears more than once"
                content.isBlank() -> problems += "'$path' is empty"
            }
        }
        return if (problems.isEmpty()) Validation.Valid(files) else Validation.Invalid(problems)
    }

    /** Writes [files] under [dir]. Paths must be safe; see [isSafeRelativePath]. */
    fun write(files: Map<String, String>, dir: File) {
        files.forEach { (path, content) ->
            require(isSafeRelativePath(path)) { "Unsafe path '$path'." }
            File(dir, path).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }

    /** Reads every file under [dir], as relative path to text, sorted by path. */
    fun read(dir: File): Map<String, String> = dir.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
        .sortedBy { it.first }
        .toMap()

    /** A relative path with `/` separators that stays inside its base directory. */
    fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
