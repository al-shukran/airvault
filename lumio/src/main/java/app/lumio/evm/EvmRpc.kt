package app.lumio.evm

import app.lensly.core.wallet.toHex
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * A minimal JSON-RPC client. It makes the reads that Lumio shows (eth_chainId, eth_getBalance and eth_getTransactionCount) and the reads
 * that a send needs (the pending nonce, the fees and the gas estimate), and it broadcasts a signed transaction. Each request holds public
 * data only: an address, an amount, or a signed transaction that anyone can broadcast. It follows no redirects, so an https address cannot
 * turn into a plain one, and every call is bounded by [timeoutMs]. The client never runs on the vault, and it has no key material to send.
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
        val id = quantity(url, "eth_chainId", NO_PARAMS)
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
        return quantity(url, "eth_getBalance", strings(address, LATEST))
    }

    /** The transaction count (the nonce) of [address] at the latest block. It counts the transactions the address has sent. */
    fun transactionCount(url: String, address: String): Long = count(url, address, LATEST)

    /**
     * The nonce of the next transaction from [address]: its count with the pending transactions included, so a send does not reuse the
     * nonce of a transaction that is still waiting to be mined.
     */
    fun pendingTransactionCount(url: String, address: String): Long = count(url, address, PENDING)

    /** The priority fee per gas that the endpoint suggests, in wei. */
    fun maxPriorityFee(url: String): BigInteger = quantity(url, "eth_maxPriorityFeePerGas", NO_PARAMS)

    /**
     * The base fee per gas of the next block, in wei: the last of the base fees that eth_feeHistory reports for the newest block. A fee cap
     * of twice this value stays valid through several full blocks, as the base fee rises by at most 12.5% a block.
     */
    fun baseFee(url: String): BigInteger {
        val history = call(url, "eth_feeHistory", JsonArray(listOf(JsonPrimitive("0x1"), JsonPrimitive(LATEST), JsonArray(emptyList()))))
        val base = ((history as? JsonObject)?.get("baseFeePerGas") as? JsonArray)?.lastOrNull()
            ?: throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint gave no base fee.")
        return parseQuantity(stringOf(base))
    }

    /** The gas that a transfer of [valueWei] from [from] to [to] uses, as the endpoint estimates it. */
    fun estimateGas(url: String, from: String, to: String, valueWei: BigInteger): BigInteger {
        requireAddress(from)
        requireAddress(to)
        require(valueWei.signum() >= 0) { "The amount must not be negative." }
        val transfer = JsonObject(
            mapOf(
                "from" to JsonPrimitive(from),
                "to" to JsonPrimitive(to),
                "value" to JsonPrimitive("0x" + valueWei.toString(RADIX_HEX)),
            ),
        )
        return quantity(url, "eth_estimateGas", JsonArray(listOf(transfer)))
    }

    /**
     * Broadcasts the signed transaction [raw] and returns the hash that the endpoint reports, as 0x and 64 lowercase hex digits. An endpoint
     * that refuses the transaction fails with [RpcFailure.Reason.ERROR].
     */
    fun sendRawTransaction(url: String, raw: ByteArray): String {
        val hash = stringOf(call(url, "eth_sendRawTransaction", strings("0x" + raw.toHex()))).lowercase()
        if (!HASH.matches(hash)) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent a value that is not a transaction hash.")
        return hash
    }

    private fun count(url: String, address: String, block: String): Long {
        requireAddress(address)
        val count = quantity(url, "eth_getTransactionCount", strings(address, block))
        if (count.bitLength() > MAX_LONG_BITS) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint reports a count that is too large.")
        return count.toLong()
    }

    private fun quantity(url: String, method: String, params: JsonArray): BigInteger = parseQuantity(stringOf(call(url, method, params)))

    /** The result member of the answer to [method]. An error object, or a missing or null result, is a failure. */
    private fun call(url: String, method: String, params: JsonArray): JsonElement {
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
                "params" to params,
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

    /** The result member of a JSON-RPC answer. An error object, a missing result or a null result is a failure. */
    private fun resultOf(text: String): JsonElement {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent something that is not JSON-RPC.")
        val error = root["error"]
        if (error != null && error !is JsonNull) throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.")
        val result = root["result"]
        if (result == null || result is JsonNull) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent no result.")
        return result
    }

    private fun stringOf(item: JsonElement): String {
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw RpcFailure(RpcFailure.Reason.MALFORMED, "The endpoint sent no result.")
        return primitive.content
    }

    private fun strings(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun requireAddress(address: String) {
        require(ADDRESS.matches(address)) { "Not an EVM address." }
    }

    companion object {
        /** Each call is bounded by this many milliseconds, for connecting, reading and the whole call. */
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L

        private const val LATEST = "latest"
        private const val PENDING = "pending"
        private const val RADIX_HEX = 16
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_LONG_BITS = 63
        private val JSON_TYPE = "application/json".toMediaType()
        private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
        private val HASH = Regex("0x[0-9a-f]{64}")
        private val NO_PARAMS = JsonArray(emptyList())
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
