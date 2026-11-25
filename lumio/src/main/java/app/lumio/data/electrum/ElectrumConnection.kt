package app.lumio.data.electrum

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One JSON-RPC request: its method and its positional parameters. */
class RpcCall(val method: String, val params: JsonArray)

/**
 * One persistent TLS connection to an Electrum server. Each request has an id, and a reader coroutine matches every reply to its request by that
 * id. Replies may arrive in any order, and the calls of several coroutines share the socket. A connection that fails stays closed: [isAlive]
 * turns false, the calls waiting on it fail with [ElectrumError.Transport], and the pool opens a new connection when it needs one.
 */
class ElectrumConnection private constructor(
    val server: ElectrumServer,
    private val socket: SSLSocket,
    private val requestTimeoutMs: Long,
) {
    private class Request(val id: Long, val json: JsonObject, val reply: CompletableDeferred<Result<JsonElement>>)

    private val output = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
    private val writeLock = Mutex()
    private val nextId = AtomicLong(1)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Result<JsonElement>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var failure: ElectrumError.Transport? = null

    private val closed = CompletableDeferred<Unit>()

    init {
        val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        scope.launch { readReplies(input) }
    }

    /** False once the connection has failed or been closed. */
    val isAlive: Boolean get() = failure == null

    /** Suspends until the connection has failed or been closed. */
    internal suspend fun awaitClosed() = closed.await()

    /**
     * Sends [calls] and returns each call's reply, in the order of [calls]. A call that the server answered with an error gets a failure in its own
     * slot, and the other slots are unaffected. The calls go out in batches of at most [ElectrumServer.maxBatch], all at once.
     * @throws ElectrumError.Transport when the connection fails, or when it does not answer within the request timeout.
     */
    suspend fun batch(calls: List<RpcCall>): List<Result<JsonElement>> {
        if (calls.isEmpty()) return emptyList()
        val requests = calls.map { register(it) }
        try {
            send(requests)
            return withTimeoutOrNull(requestTimeoutMs) { requests.map { it.reply.await() } }
                ?: throw fail(ElectrumError.Transport("${server.host} did not answer within $requestTimeoutMs ms"))
        } finally {
            requests.forEach { waiting.remove(it.id) }
        }
    }

    /** Sends one request and returns its result. An error answer throws [ElectrumError.Rpc]. */
    internal suspend fun call(method: String, params: JsonArray): JsonElement =
        batch(listOf(RpcCall(method, params))).single().getOrThrow()

    /** Closes the connection. The calls waiting on it fail. */
    fun close() {
        fail(ElectrumError.Transport("the connection to ${server.host} was closed"))
    }

    private fun register(call: RpcCall): Request {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<Result<JsonElement>>()
        waiting[id] = reply
        // Either this check sees a failure, or the failure drains the entry added above, so no call waits on a dead connection.
        failure?.let {
            waiting.remove(id)
            throw it
        }
        val json = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(call.method))
            put("params", call.params)
        }
        return Request(id, json, reply)
    }

    private suspend fun send(requests: List<Request>) {
        val lines = requests.chunked(server.maxBatch).map { chunk ->
            val message: JsonElement = if (chunk.size == 1) chunk.single().json else JsonArray(chunk.map { it.json })
            message.toString()
        }
        writeLock.withLock {
            try {
                withContext(Dispatchers.IO) {
                    for (line in lines) {
                        output.write(line)
                        output.write("\n")
                    }
                    output.flush()
                }
            } catch (e: IOException) {
                throw fail(ElectrumError.Transport("cannot write to ${server.host}", e))
            }
        }
    }

    private fun readReplies(input: BufferedReader) {
        try {
            while (true) {
                val line = input.readLine() ?: break
                if (line.isNotBlank()) dispatch(Json.parseToJsonElement(line))
            }
            fail(ElectrumError.Transport("${server.host} closed the connection"))
        } catch (e: Exception) {
            fail(ElectrumError.Transport("the connection to ${server.host} failed", e))
        }
    }

    private fun dispatch(message: JsonElement) {
        val replies = if (message is JsonArray) message else listOf(message)
        for (reply in replies) {
            val fields = reply as? JsonObject ?: continue
            // A notification has no id. A reply whose call has given up is dropped.
            val id = (fields["id"] as? JsonPrimitive)?.longOrNull ?: continue
            waiting.remove(id)?.complete(outcome(fields))
        }
    }

    private fun outcome(fields: JsonObject): Result<JsonElement> {
        val error = fields["error"]
        if (error != null && error !is JsonNull) {
            val detail = error as? JsonObject
            val code = (detail?.get("code") as? JsonPrimitive)?.intOrNull
            val message = (detail?.get("message") as? JsonPrimitive)?.contentOrNull ?: error.toString()
            return Result.failure(ElectrumError.Rpc(code, message))
        }
        return Result.success(fields["result"] ?: JsonNull)
    }

    /** Records the first failure, closes the socket, and fails every waiting call. Later failures change nothing. */
    @Synchronized
    private fun fail(cause: ElectrumError.Transport): ElectrumError.Transport {
        failure?.let { return it }
        failure = cause
        closed.complete(Unit)
        runCatching { socket.close() }
        scope.cancel()
        waiting.values.forEach { it.completeExceptionally(cause) }
        waiting.clear()
        return cause
    }

    private suspend fun negotiate() {
        val versions = buildJsonArray {
            add(JsonPrimitive("1.4"))
            add(JsonPrimitive("1.4.2"))
        }
        val reply = call("server.version", buildJsonArray {
            add(JsonPrimitive("Lumio"))
            add(versions)
        })
        val protocol = (reply as? JsonArray)?.getOrNull(1)?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (protocol == null || !atLeast14(protocol)) {
            throw ElectrumError.Transport("${server.host} does not speak Electrum protocol 1.4")
        }
    }

    companion object {
        /**
         * Connects to [server] over TLS, checks its certificate, and negotiates the protocol with `server.version`.
         * @throws ElectrumError.Transport when the server cannot be reached, refuses its certificate, or does not speak Electrum protocol 1.4.
         */
        suspend fun open(server: ElectrumServer, connectTimeoutMs: Long, requestTimeoutMs: Long): ElectrumConnection {
            val socket = withContext(Dispatchers.IO) { connectTls(server, connectTimeoutMs) }
            val connection = ElectrumConnection(server, socket, requestTimeoutMs)
            try {
                connection.negotiate()
            } catch (e: ElectrumError.Rpc) {
                connection.close()
                throw ElectrumError.Transport("${server.host} refused server.version", e)
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
            return connection
        }
    }
}

/** Opens TCP to [server], then TLS over it, and returns the handshaken socket. A failure closes the TCP socket and throws a transport error. */
private fun connectTls(server: ElectrumServer, connectTimeoutMs: Long): SSLSocket {
    val tcp = Socket()
    try {
        tcp.connect(InetSocketAddress(server.host, server.port), connectTimeoutMs.toInt())
        val socket = tlsSocketFactory(server.trust).createSocket(tcp, server.host, server.port, true) as SSLSocket
        socket.soTimeout = connectTimeoutMs.toInt()
        if (server.trust is Trust.Authority) {
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        socket.startHandshake()
        socket.soTimeout = 0
        return socket
    } catch (e: Exception) {
        runCatching { tcp.close() }
        throw ElectrumError.Transport("cannot open a TLS connection to ${server.host}", e)
    }
}

/** Whether a protocol version such as "1.4" or "1.4.2" is 1.4 or newer. */
private fun atLeast14(version: String): Boolean {
    val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    return major > 1 || (major == 1 && minor >= 4)
}
