package app.lumio.data.rpc

import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.failureFor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The RPC addresses Lumio accepts. HTTPS only. Plain HTTP is accepted only for the emulator's host address and this machine, and only in a
 * debug build ([allowLocalHttp]). An address with a user name or password is refused, so a secret never sits in a URL that is stored.
 */
object RpcUrl {
    /** The owner's words for an address that is not accepted. */
    const val REFUSED = "This isn't a valid https address"
    private const val MAX_LENGTH = 512
    private val LOCAL_HOSTS = setOf("localhost", "10.0.2.2")

    /** The normalized address for [raw]. Throws IllegalArgumentException, with the owner's message, when it is not accepted. */
    fun validate(raw: String, allowLocalHttp: Boolean): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Enter an RPC address." }
        require(text.length <= MAX_LENGTH) { REFUSED }
        val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException(REFUSED)
        require(url.username.isEmpty() && url.password.isEmpty()) { REFUSED }
        when (url.scheme) {
            "https" -> Unit
            "http" -> require(allowLocalHttp && url.host in LOCAL_HOSTS) { REFUSED }
            else -> throw IllegalArgumentException(REFUSED)
        }
        return url.toString()
    }
}

/**
 * The one JSON-RPC client. Every RPC provider and the settings check POST through [call], so the rules hold once: HTTPS addresses only, no
 * redirects followed, an answer capped at 64 KiB, and each call bounded by the client's timeouts. A request holds public data only. Each failure
 * is a [ProviderError] whose message is for the owner. It names no address and no key.
 */
object JsonRpc {
    /** Each call is bounded by this many milliseconds, for connecting, reading and the whole call. */
    const val DEFAULT_TIMEOUT_MS: Long = 10_000L

    /** The most a call may spend connecting, whatever its timeout. */
    private const val CONNECT_TIMEOUT_MS: Long = 3_000L

    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val RATE_LIMITED_CODE = -32005
    private const val METHOD_NOT_FOUND_CODE = -32601

    /** The wording of an error that says the endpoint does not serve the chain or the method on its plan, such as "chain is not available on free plan". */
    private val NOT_SERVED = Regex("not (available|enabled|supported)|free plan", RegexOption.IGNORE_CASE)

    /** The wording of a node that refuses a transaction for its balance: "insufficient funds for gas * price + value" (Geth) or "Signer had insufficient balance" (Monad). */
    private val INSUFFICIENT_FUNDS = Regex("insufficient (funds|balance)", RegexOption.IGNORE_CASE)

    /** What the owner reads when the node refuses a transaction for its balance. */
    const val INSUFFICIENT_FUNDS_MESSAGE = "The network refused the transaction: the account does not hold enough for the amount and the fee."

    /**
     * The wording of a node that already holds the transaction it was sent: "already known" (Geth), "known transaction" (Besu), "already imported"
     * (OpenEthereum) or "AlreadyKnown" (Nethermind). The word boundary keeps "unknown transaction" out.
     */
    private val ALREADY_KNOWN = Regex("""\b(already ?known|known transaction|already imported)""", RegexOption.IGNORE_CASE)

    /** The wording of a Solana node that refuses a transaction whose blockhash it does not know: "Blockhash not found" in its simulation error. */
    private val BLOCKHASH_UNKNOWN = Regex("blockhash not found", RegexOption.IGNORE_CASE)

    /**
     * What a caller reads when the network refuses a transaction over its blockhash. A blockhash that has aged out and a durable nonce that has moved
     * draw the same answer, so the caller says which of the two it was.
     */
    const val BLOCKHASH_REFUSED_MESSAGE = "The network refused the transaction because it does not know its blockhash."
    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * The HTTP stack for RPC calls: no redirects, and each call bounded by [timeoutMs]. Connecting alone is cut at [CONNECT_TIMEOUT_MS], so an
     * endpoint that hangs on connect is passed over sooner.
     */
    fun newHttp(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Http = Http(
        OkHttpClient.Builder()
            .connectTimeout(minOf(timeoutMs, CONNECT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    )

    /**
     * The result member of the answer to [method] with [params] at [url]. Blocking, so run it off the main thread. A 429, or a JSON-RPC error
     * that names a rate limit, is [ProviderError.RateLimited]; a 5xx, a timeout or a dropped connection is [ProviderError.Transient]; an error
     * that says the endpoint does not serve the method or the chain is [ProviderError.Unsupported], so the caller moves on without a penalty;
     * a node that already holds the transaction it was sent is [ProviderError.AlreadyKnown]; anything else that fails is [ProviderError.Fatal].
     */
    fun call(client: OkHttpClient, url: String, method: String, params: JsonArray, allowLocalHttp: Boolean, nullIsAnswer: Boolean = false): JsonElement {
        val target = try {
            RpcUrl.validate(url, allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            throw ProviderError.Fatal(e.message ?: "The RPC address is not accepted.")
        }
        val body = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(1),
                "method" to JsonPrimitive(method),
                "params" to params,
            ),
        ).toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(target).post(body).build()
        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw failureFor(response.code, response.header("Retry-After"), banMs = null)
                response.peekBody(MAX_RESPONSE_BYTES + 1L).string()
            }
        } catch (e: IOException) {
            throw ProviderError.Transient(e)
        }
        if (text.length > MAX_RESPONSE_BYTES) throw ProviderError.Unreadable("The endpoint's answer is too large.")
        return resultOf(text, nullIsAnswer)
    }

    /**
     * The result member of a JSON-RPC answer. An error object that names a rate limit is rate limited, any other error object or a missing result is a
     * failure, and so is a null result unless [nullIsAnswer] says null is the answer, as it is for a receipt the network does not hold yet.
     */
    private fun resultOf(text: String, nullIsAnswer: Boolean): JsonElement {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ProviderError.Unreadable("The endpoint sent something that is not JSON-RPC.")
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            val fields = error as? JsonObject
            val code = (fields?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            val message = (fields?.get("message") as? JsonPrimitive)?.content.orEmpty()
            if (code == RATE_LIMITED_CODE || message.contains("rate limit", ignoreCase = true)) throw ProviderError.RateLimited()
            if (code == METHOD_NOT_FOUND_CODE || NOT_SERVED.containsMatchIn(message)) throw ProviderError.Unsupported()
            if (ALREADY_KNOWN.containsMatchIn(message)) throw ProviderError.AlreadyKnown()
            if (INSUFFICIENT_FUNDS.containsMatchIn(message)) throw ProviderError.Fatal(INSUFFICIENT_FUNDS_MESSAGE)
            if (BLOCKHASH_UNKNOWN.containsMatchIn(message)) throw ProviderError.Fatal(BLOCKHASH_REFUSED_MESSAGE)
            throw ProviderError.Fatal("The endpoint returned a JSON-RPC error.")
        }
        val result = root["result"] ?: throw ProviderError.Unreadable("The endpoint sent no result.")
        if (result is JsonNull && !nullIsAnswer) throw ProviderError.Unreadable("The endpoint sent no result.")
        return result
    }
}
