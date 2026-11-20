package dev.graidle.testing

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local stand-in for the Messages API. It returns answers as the text of an assistant message, the way structured
 * outputs arrive. A request takes the next answer of the first matching route ([answerWhen]), or else the next answer
 * of the shared queue ([enqueue]). Routes make answers independent of the order in which parallel tasks send requests.
 */
class FakeClaudeServer : AutoCloseable {

    data class Recorded(val body: String, val betaHeader: String?)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val answers = ConcurrentLinkedQueue<String>()
    private val routes = CopyOnWriteArrayList<Pair<(String) -> Boolean, ConcurrentLinkedQueue<String>>>()
    val requests: MutableList<Recorded> = CopyOnWriteArrayList()

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            requests += Recorded(body, exchange.requestHeaders.getFirst("anthropic-beta"))
            val answer = routes.firstOrNull { (matches, queue) -> queue.isNotEmpty() && matches(body) }?.second?.poll()
                ?: answers.poll()
            val (status, json) = if (answer == null) {
                400 to """{"type":"error","error":{"type":"invalid_request_error","message":"no fake answer queued"}}"""
            } else {
                200 to if (answer.startsWith(TOOL_USE)) toolUseMessage(answer.removePrefix(TOOL_USE)) else message(answer)
            }
            val bytes = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun enqueue(vararg structuredAnswers: String) {
        answers += structuredAnswers
    }

    /** Answers requests whose JSON body contains all of [fragments], in order. */
    fun answerWhen(vararg fragments: String, answers: List<String>) {
        routes += Pair({ body: String -> fragments.all { it in body } }, ConcurrentLinkedQueue(answers))
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        private const val TOOL_USE = "\u0000tool_use:"

        /** An answer that calls the tool [name] with [inputJson], instead of a final answer. */
        fun toolUse(name: String, inputJson: String): String = "$TOOL_USE$name|$inputJson"
    }

    private var toolUseIds = 0

    /** An assistant turn that calls one tool. [spec] is "name|input JSON". */
    private fun toolUseMessage(spec: String): String {
        val name = spec.substringBefore('|')
        val input = spec.substringAfter('|')
        return """
            {"id":"msg_fake","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"tool_use","id":"toolu_${++toolUseIds}","name":${jsonString(name)},"input":$input}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":120,"output_tokens":40}}
        """.trimIndent()
    }

    private fun message(text: String) = """
        {"id":"msg_fake","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"text","text":${jsonString(text)}}],
         "stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":120,"output_tokens":40}}
    """.trimIndent()

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
