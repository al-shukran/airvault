package app.lumio.data.electrum

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress // CI_ALLOW_NETWORK_REFERENCE: loopback bind address only, no network access
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a fake server does with one request. */
internal sealed interface Answer {
    class Reply(val value: JsonElement) : Answer

    class Fail(val message: String) : Answer

    /** No reply to this request. */
    data object Silent : Answer

    /** Closes the connection without a reply. */
    data object Hangup : Answer
}

/**
 * A TLS server that speaks Electrum's newline-delimited JSON-RPC, for tests. [answer] decides each request's reply from its method and parameters.
 * `server.version` is answered by the fake itself, with [protocol]. [batchOrder] reorders a batch's replies before they are written.
 * [notifications] writes a notification before each reply. [closeAfterLines] closes each connection after it has answered that many lines, the
 * handshake included. [lineSizes] records how many requests each line of client traffic carried, so a test can see how batches were split.
 */
internal class FakeElectrum(
    identity: TestIdentity,
    private val protocol: String = "1.4",
    private val notifications: Boolean = false,
    private val closeAfterLines: Int? = null,
    private val batchOrder: (List<JsonObject>) -> List<JsonObject> = { it },
    private val answer: (method: String, params: JsonArray) -> Answer,
) : AutoCloseable {
    // Bound to loopback, not the wildcard address. A wildcard bind can take a port that another process holds on 127.0.0.1, and the client
    // connects to that process instead of this fake.
    private val server = identity.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
    private val threads: ExecutorService = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }

    val port: Int = server.localPort
    val lineSizes = CopyOnWriteArrayList<Int>()
    val connections = AtomicInteger()
    private val accepted = CopyOnWriteArrayList<SSLSocket>()

    init {
        threads.execute {
            while (!server.isClosed) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (e: Exception) {
                    break
                }
                connections.incrementAndGet()
                accepted += socket
                threads.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        socket.use {
            try {
                socket.startHandshake()
                val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val output = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                var lines = 0
                while (true) {
                    val line = input.readLine() ?: break
                    val message = Json.parseToJsonElement(line)
                    val requests = if (message is JsonArray) message.map { it.jsonObject } else listOf(message.jsonObject)
                    lineSizes += requests.size
                    val replies = mutableListOf<JsonObject>()
                    var hangup = false
                    for (request in requests) {
                        val id = request.getValue("id")
                        val method = request.getValue("method").jsonPrimitive.content
                        val params = request["params"] as? JsonArray ?: JsonArray(emptyList())
                        val outcome = if (method == "server.version") {
                            Answer.Reply(JsonArray(listOf(JsonPrimitive("FakeElectrum"), JsonPrimitive(protocol))))
                        } else {
                            answer(method, params)
                        }
                        when (outcome) {
                            is Answer.Reply -> replies += reply(id, result = outcome.value)
                            is Answer.Fail -> replies += reply(id, error = outcome.message)
                            Answer.Silent -> Unit
                            Answer.Hangup -> hangup = true
                        }
                    }
                    if (hangup) break
                    if (notifications) output.write(NOTIFICATION + "\n")
                    val ordered = batchOrder(replies)
                    if (ordered.isNotEmpty()) {
                        val out: JsonElement = if (message is JsonArray) JsonArray(ordered) else ordered.single()
                        output.write(out.toString() + "\n")
                    }
                    output.flush()
                    lines++
                    if (lines == closeAfterLines) break
                }
            } catch (e: Exception) {
                // The client went away or refused the certificate. Either way this connection is over.
            }
        }
    }

    private fun reply(id: JsonElement, result: JsonElement? = null, error: String? = null): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        if (error != null) {
            put("error", buildJsonObject {
                put("code", JsonPrimitive(1))
                put("message", JsonPrimitive(error))
            })
        } else {
            put("result", result ?: JsonNull)
        }
    }

    /** Closes the listener and every connection it accepted, so no client is left reading from a server that is gone. */
    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        threads.shutdownNow()
    }

    private companion object {
        const val NOTIFICATION =
            """{"jsonrpc":"2.0","method":"blockchain.headers.subscribe","params":[{"height":1,"hex":"00"}]}"""
    }
}
