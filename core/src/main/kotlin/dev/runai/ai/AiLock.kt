package dev.runai.ai

import java.io.File

/**
 * The lock file committed next to a task's result. It records the [fingerprint] of the inputs that produced the
 * result. [model] and [promptVersion] are there for reviewers; the fingerprint already covers them.
 */
data class AiLock(val fingerprint: String, val model: String, val promptVersion: String) {

    fun write(file: File, taskPath: String) {
        file.parentFile.mkdirs()
        file.writeText(
            """
            |# runAi lock for $taskPath. Commit this file together with the result.
            |# The fingerprint covers every input that the model saw.
            |fingerprint=$fingerprint
            |model=$model
            |promptVersion=$promptVersion
            |""".trimMargin()
        )
    }

    companion object {
        /** Returns null when the file does not exist or has no fingerprint. */
        fun read(file: File): AiLock? {
            if (!file.isFile) return null
            val values = file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val fingerprint = values["fingerprint"]?.takeIf { it.isNotEmpty() } ?: return null
            return AiLock(fingerprint, values["model"].orEmpty(), values["promptVersion"].orEmpty())
        }
    }
}
