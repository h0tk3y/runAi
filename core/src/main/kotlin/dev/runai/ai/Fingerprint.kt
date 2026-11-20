package dev.runai.ai

import java.io.File
import java.security.MessageDigest

/**
 * A hash of everything a task sends to the model. It does not depend on file paths, the order of [put] calls, or
 * line endings, so it is the same on every machine and operating system.
 */
class Fingerprint {

    private val entries = sortedMapOf<String, String?>()

    fun put(key: String, value: String?): Fingerprint {
        require(key !in entries) { "Fingerprint key '$key' is already set." }
        entries[key] = value?.replace("\r\n", "\n")
        return this
    }

    fun putText(key: String, file: File?): Fingerprint = put(key, file?.readText())

    /** A hex SHA-256 hash. An absent value and an empty value give different hashes. */
    fun hash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (key, value) ->
            digest.update(encode(key))
            digest.update(if (value == null) byteArrayOf(0) else byteArrayOf(1) + encode(value))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return "${bytes.size}:".toByteArray(Charsets.UTF_8) + bytes
    }
}
