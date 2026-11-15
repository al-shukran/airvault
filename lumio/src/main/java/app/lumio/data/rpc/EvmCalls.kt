package app.lumio.data.rpc

import app.lensly.core.wallet.toHex
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.ContractCall
import app.lumio.data.provider.EstimateGas
import app.lumio.data.provider.GasFees
import app.lumio.data.provider.GasQuote
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Nonce
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * The JSON-RPC calls behind each request on an EVM network, named as Ethereum's JSON-RPC names them. A call holds public data only: an address,
 * an amount, or a signed transaction that anyone can broadcast. Blocking, so run it off the main thread.
 */
internal object EvmCalls {
    private const val LATEST = "latest"
    private const val PENDING = "pending"
    private const val RADIX = 16
    private const val MAX_LONG_BITS = 63
    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    private val HASH = Regex("0x[0-9a-f]{64}")
    private val HEX_DATA = Regex("0x([0-9a-fA-F]{2})*")
    private val NO_PARAMS = JsonArray(emptyList())

    /**
     * The answer to [req] from the endpoint at [url]. Throws [ProviderError] for a failure of the endpoint, and IllegalArgumentException for an
     * address that is not an EVM address, before any call is made.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> execute(client: OkHttpClient, url: String, allowLocalHttp: Boolean, req: Request<T>): T {
        fun call(method: String, params: JsonArray): JsonElement = JsonRpc.call(client, url, method, params, allowLocalHttp)
        val result: Any = when (req) {
            is NativeBalance -> quantity(call("eth_getBalance", strings(address(req.address), LATEST)))
            is Nonce -> count(call("eth_getTransactionCount", strings(address(req.address), if (req.pending) PENDING else LATEST)))
            is EstimateGas -> {
                address(req.from)
                address(req.to)
                require(req.valueWei.signum() >= 0) { "The amount must not be negative." }
                val transfer = JsonObject(
                    mapOf(
                        "from" to JsonPrimitive(req.from),
                        "to" to JsonPrimitive(req.to),
                        "value" to JsonPrimitive("0x" + req.valueWei.toString(RADIX)),
                    ),
                )
                quantity(call("eth_estimateGas", JsonArray(listOf(transfer))))
            }
            is GasFees -> gasFees { method, params -> call(method, params) }
            is Broadcast -> hash(call("eth_sendRawTransaction", strings("0x" + req.raw.toHex())))
            is ContractCall -> {
                val transaction = JsonObject(mapOf("to" to JsonPrimitive(req.to), "data" to JsonPrimitive(req.data)))
                hexData(call("eth_call", JsonArray(listOf(transaction, JsonPrimitive(LATEST)))))
            }
            else -> throw ProviderError.Unsupported()
        }
        return result as T
    }

    /** The chain ID that the endpoint at [url] reports. The settings check compares it with the chain the owner named. */
    fun chainId(client: OkHttpClient, url: String, allowLocalHttp: Boolean): Long {
        val id = quantity(JsonRpc.call(client, url, "eth_chainId", NO_PARAMS, allowLocalHttp))
        if (id.bitLength() > MAX_LONG_BITS) throw ProviderError.Fatal("The endpoint reports a chain ID that is too large.")
        return id.toLong()
    }

    /**
     * The next block's base fee, from the newest entry of eth_feeHistory, and the suggested priority fee. A fee cap of twice the base fee stays
     * valid through several full blocks, as the base fee rises by at most 12.5% a block.
     */
    private fun gasFees(call: (String, JsonArray) -> JsonElement): GasQuote {
        val history = call("eth_feeHistory", JsonArray(listOf(JsonPrimitive("0x1"), JsonPrimitive(LATEST), JsonArray(emptyList()))))
        val base = ((history as? JsonObject)?.get("baseFeePerGas") as? JsonArray)?.lastOrNull()
            ?: throw ProviderError.Fatal("The endpoint gave no base fee.")
        return GasQuote(baseFee = quantity(base), priorityFee = quantity(call("eth_maxPriorityFeePerGas", NO_PARAMS)))
    }

    private fun address(text: String): String {
        require(ADDRESS.matches(text)) { "Not an EVM address." }
        return text
    }

    private fun count(element: JsonElement): Long {
        val value = quantity(element)
        if (value.bitLength() > MAX_LONG_BITS) throw ProviderError.Fatal("The endpoint reports a count that is too large.")
        return value.toLong()
    }

    /** The return data of a call: hex with a 0x prefix, `0x` for an empty answer. Any other value is a malformed answer. */
    private fun hexData(element: JsonElement): String {
        val text = stringOf(element)
        if (!HEX_DATA.matches(text)) throw ProviderError.Fatal("The endpoint sent a value that is not hex data.")
        return text
    }

    private fun hash(element: JsonElement): String {
        val hash = stringOf(element).lowercase()
        if (!HASH.matches(hash)) throw ProviderError.Fatal("The endpoint sent a value that is not a transaction hash.")
        return hash
    }

    private fun quantity(element: JsonElement): BigInteger = parseQuantity(stringOf(element))

    private fun stringOf(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw ProviderError.Fatal("The endpoint sent no result.")
        return primitive.content
    }

    private fun strings(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}

private val QUANTITY = Regex("0x[0-9a-fA-F]+")

/** The integer of a JSON-RPC quantity: 0x followed by hexadecimal digits. Any other text is a malformed answer. */
internal fun parseQuantity(text: String): BigInteger {
    if (!QUANTITY.matches(text)) throw ProviderError.Fatal("The endpoint sent a value that is not a hexadecimal quantity.")
    return BigInteger(text.substring(2), 16)
}
