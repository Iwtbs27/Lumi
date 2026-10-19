package ai.opencyvis.testing

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Loopback HTTP only: the real clients, queue, engine and device backend remain in use. */
internal class LocalAgentModel(private val childToolName: String = "research_action") : AutoCloseable {
    class Request(val body: JSONObject, val toolName: String) {
        val response = CompletableDeferred<JSONObject>()
        val text: String get() = body.getJSONArray("messages").objects().joinToString("\n") { message ->
            when (val content = message.get("content")) {
                is JSONArray -> content.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }
                else -> content.toString()
            }
        }
        val hasImage: Boolean get() {
            val messages = body.getJSONArray("messages")
            val content = messages.getJSONObject(messages.length() - 1).optJSONArray("content") ?: return false
            return content.objects().any { block ->
                block.optString("type") == "image_url" && block.getJSONObject("image_url").getString("url")
                    .let { it.startsWith("data:image/") && it.substringAfter(";base64,", "").isNotBlank() }
            }
        }
        fun lastUserObject(): JSONObject {
            val content = body.getJSONArray("messages").objects().last { it.getString("role") == "user" }.get("content")
            val text = if (content is JSONArray) content.objects().first { it.getString("type") == "text" }.getString("text")
                else content.toString()
            return JSONObject(text)
        }
        fun reply(action: String, vararg fields: Pair<String, Any>) {
            val base = if (toolName == "phone_action") mapOf("thought" to "Local acceptance $action", "memory_update" to emptyList<Any>()) else emptyMap()
            check(response.complete(JSONObject(base + mapOf("action_type" to action) + fields.toMap())))
        }
    }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<Request>()
    private val parents = Channel<Request>(Channel.UNLIMITED)
    private val children = Channel<Request>(Channel.UNLIMITED)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool { work -> Thread(work, "agent-local-model").apply { isDaemon = true } }
    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                sockets += socket
                executor.execute {
                    try { socket.use { handle(it) } }
                    catch (failure: Exception) { if (!server.isClosed) { parents.close(failure); children.close(failure) } }
                    finally { sockets -= socket }
                }
            }
        }
    }
    suspend fun nextParent(instruction: String): Request = withTimeout(60_000) {
        parents.receive().also {
            assertEquals("[task]\n$instruction", it.body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertTrue("Parent observes a real screenshot each round", it.hasImage)
        }
    }
    suspend fun nextChild(): Request = withTimeout(60_000) {
        children.receive().also {
            val tools = it.body.getJSONArray("tools")
            assertEquals(1, tools.length())
            val function = tools.getJSONObject(0).getJSONObject("function")
            assertEquals(childToolName, function.getString("name"))
            assertFalse(tools.toString().contains("phone_action"))
            val actions = function.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("action_type").getJSONArray("enum")
            assertEquals(if (childToolName == "research_action") setOf("read_artifact", "read_evidence", "submit_result")
                else setOf("read_material", "inspect_sdk", "write_code", "read_code", "run_code", "read_output", "submit_result"),
                (0 until actions.length()).map { index -> actions.getString(index) }.toSet())
        }
    }
    private fun handle(socket: Socket) = runBlocking {
        val input = BufferedInputStream(socket.getInputStream())
        readLine(input)
        var length = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        check(length > 0)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) { val size = input.read(bytes, read, length - read); check(size > 0); read += size }
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        val name = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name")
        val stream = socket.getOutputStream()
        if (name !in setOf("phone_action", childToolName)) {
            val error = "{\"error\":{\"message\":\"Only parent/child acceptance requests are scripted\"}}"
            stream.write("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${error.length}\r\nConnection: close\r\n\r\n$error".toByteArray())
            return@runBlocking
        }
        val request = Request(body, name)
        requests += request
        (if (name == "phone_action") parents else children).send(request)
        val result = request.response.await()
        val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
            .put("id", "call_local").put("type", "function").put("function", JSONObject()
                .put("name", name).put("arguments", result.toString()))))
        val chunks = listOf(
            JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta).put("finish_reason", JSONObject.NULL))),
            JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls"))),
            JSONObject().put("choices", JSONArray()).put("usage", JSONObject()
                .put("prompt_tokens", INPUT_TOKENS).put("completion_tokens", OUTPUT_TOKENS)
                .put("total_tokens", INPUT_TOKENS + OUTPUT_TOKENS)))
        val sse = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        stream.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
        stream.write(sse.toByteArray(Charsets.UTF_8))
        stream.flush()
    }
    private fun readLine(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            check(next >= 0)
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(next.toChar())
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { it.close() }
        requests.forEach { it.response.cancel() }
        executor.shutdownNow()
    }
}


private const val INPUT_TOKENS = 100
private const val OUTPUT_TOKENS = 10
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
