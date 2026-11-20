package dev.graidle.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File
import java.util.Collections

/** A record of every model request a task made. Written next to the task's outputs and cached with them. */
class Transcript(private val task: String) {

    data class Entry(
        val label: String,
        val attempt: Int,
        val requestedModel: String,
        val servedModel: String,
        val stopReason: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val durationMs: Long,
        val problems: List<String>,
        val prompt: String,
        val response: String,
    )

    private val entries: MutableList<Entry> = Collections.synchronizedList(mutableListOf())

    fun add(entry: Entry) {
        entries += entry
    }

    fun writeTo(file: File) {
        val snapshot = synchronized(entries) { entries.toList() }
        val root = mapper.createObjectNode()
        root.put("task", task)
        root.put("requests", snapshot.size)
        root.put("inputTokens", snapshot.sumOf { it.inputTokens })
        root.put("outputTokens", snapshot.sumOf { it.outputTokens })
        val list = root.putArray("entries")
        snapshot.forEach { e ->
            list.addObject().apply {
                put("label", e.label)
                put("attempt", e.attempt)
                put("requestedModel", e.requestedModel)
                put("servedModel", e.servedModel)
                put("stopReason", e.stopReason)
                put("inputTokens", e.inputTokens)
                put("outputTokens", e.outputTokens)
                put("durationMs", e.durationMs)
                putArray("problems").apply { e.problems.forEach { add(it) } }
                put("prompt", e.prompt)
                put("response", e.response)
            }
        }
        file.parentFile.mkdirs()
        mapper.writeValue(file, root)
    }

    private companion object {
        val mapper: ObjectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    }
}
