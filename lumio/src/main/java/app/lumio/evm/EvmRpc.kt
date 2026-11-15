package app.lumio.evm

import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A read that Lumio could not complete. The message is for the owner, and it holds no key and no address. */
class RpcFailure(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { UNREACHABLE, REFUSED, MALFORMED, ERROR, WRONG_CHAIN }
}

/**
 * The RPC addresses Lumio accepts. HTTPS only. Plain HTTP is accepted only for the emulator's host address and this machine, and only in
 * a debug build ([allowLocalHttp]). An address with a user name or password is refused, so a secret never sits in a URL that is stored.
 */
object RpcUrl {
    private const val MAX_LENGTH = 512
    private val LOCAL_HOSTS = setOf("localhost", "10.0.2.2")

    /** The normalized address for [raw]. Throws IllegalArgumentException, with the owner's message, when it is not accepted. */
    fun validate(raw: String, allowLocalHttp: Boolean): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Enter an RPC address." }
        require(text.length <= MAX_LENGTH) { "The RPC address is too long." }
        val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException("That is not a web address.")
        require(url.username.isEmpty() && url.password.isEmpty()) { "The RPC address must not hold a user name or password." }
        when (url.scheme) {
            "https" -> Unit
            "http" -> require(allowLocalHttp && url.host in LOCAL_HOSTS) { "Plain HTTP is refused. Use an https address." }
            else -> throw IllegalArgumentException("Use an https address.")
        }
        return url.toString()
    }
}

/**
 * A minimal JSON-RPC client for the three reads Lumio makes: eth_chainId, eth_getBalance and eth_getTransactionCount. Each request holds
 * public data only: an address and the block tag "latest". It follows no redirects, so an https address cannot turn into a plain one, and
 * every call is bounded by [timeoutMs]. The client never runs on the vault, and it has no key material to send.
 */
class EvmRpc(
    /** True only in a debug build: plain HTTP to the emulator's host address or to localhost. See [RpcUrl.validate]. */
    val allowLocalHttp: Boolean,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** The chain ID that the endpoint at [url] reports. */
    fun chainId(url: String): Long {
        val id = quantity(url, "eth_chainId", emptyList())
        if (id.bitLength() > MAX_LONG_BITS) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint reports a chain ID that is too large.")
        return id.toLong()
    }

    /** Checks that the endpoint at [url] serves chain [expected]. Lumio asks this before it trusts a balance from the endpoint. */
    fun requireChain(url: String, expected: Long) {
        val actual = chainId(url)
        if (actual != expected) throw RpcFailure(RpcFailure.Reason.WRONG_CHAIN, "The endpoint reports chain $actual, not $expected.")
    }

    /** The native balance of [address] at the latest block, in wei. */
    fun balance(url: String, address: String): BigInteger {
        requireAddress(address)
        return quantity(url, "eth_getBalance", listOf(address, LATEST))
    }

    /** The transaction count (the nonce) of [address] at the latest block. It counts the transactions the address has sent. */
    fun transactionCount(url: String, address: String): Long {
        requireAddress(address)
        val count = quantity(url, "eth_getTransactionCount", listOf(address, LATEST))
        if (count.bitLength() > MAX_LONG_BITS) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint reports a count that is too large.")
        return count.toLong()
    }

    private fun quantity(url: String, method: String, params: List<String>): BigInteger = parseQuantity(call(url, method, params))

    private fun call(url: String, method: String, params: List<String>): String {
        val target = try {
            RpcUrl.validate(url, allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            throw RpcFailure(RpcFailure.Reason.REFUSED, e.message ?: "The RPC address is not accepted.")
        }
        val body = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(1),
                "method" to JsonPrimitive(method),
                "params" to JsonArray(params.map { JsonPrimitive(it) }),
            ),
        ).toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(target).post(body).build()
        val text = try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint redirected, and redirects are not followed.")
                }
                if (!response.isSuccessful) throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint answered HTTP ${response.code}.")
                response.peekBody(MAX_RESPONSE_BYTES + 1L).string()
            }
        } catch (e: IOException) {
            throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.")
        }
        if (text.length > MAX_RESPONSE_BYTES) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint's answer is too large.")
        return resultOf(text)
    }

    /** The string result of a JSON-RPC answer. An error object, a missing result or a non-string result is a failure. */
    private fun resultOf(text: String): String {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent something that is not JSON-RPC.")
        val error = root["error"]
        if (error != null && error !is JsonNull) throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.")
        val result = root["result"] as? JsonPrimitive
        if (result == null || !result.isString) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent no result.")
        return result.content
    }

    private fun requireAddress(address: String) {
        require(ADDRESS.matches(address)) { "Not an EVM address." }
    }

    companion object {
        /** Each call is bounded by this many milliseconds, for connecting, reading and the whole call. */
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L

        private const val LATEST = "latest"
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_LONG_BITS = 63
        private val JSON_TYPE = "application/json".toMediaType()
        private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    }
}

private val QUANTITY = Regex("0x[0-9a-fA-F]+")

/** The integer of a JSON-RPC quantity: 0x followed by hexadecimal digits. Any other text is a malformed answer. */
internal fun parseQuantity(text: String): BigInteger {
    if (!QUANTITY.matches(text)) {
        throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent a value that is not a hexadecimal quantity.")
    }
    return BigInteger(text.substring(2), 16)
}
