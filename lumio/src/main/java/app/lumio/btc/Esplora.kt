package app.lumio.btc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One side of an address's history, in satoshis: the confirmed chain, or the mempool. */
data class EsploraSide(
    val fundedCount: Long,
    val fundedSum: Long,
    val spentCount: Long,
    val spentSum: Long,
    val txCount: Long,
) {
    /** What this side adds to the address: funded minus spent. It is negative when a mempool side spends more than it funds. */
    val net: Long get() = fundedSum - spentSum
}

/** An address's history, confirmed and in the mempool, as `GET /address/{address}` reports it. */
data class EsploraAddressStats(val chain: EsploraSide, val mempool: EsploraSide) {
    /** True once the address has appeared in any transaction, confirmed or not. The gap-limit scan counts an address that is not used. */
    val used: Boolean get() = chain.txCount + mempool.txCount > 0
}

/** The previous output an input spends. [address] is null for a script with no address. */
data class EsploraPrevout(val address: String?, val valueSats: Long)

/** One input of a transaction. A coinbase input has no previous output. */
data class EsploraInput(val prevout: EsploraPrevout?)

/** One output of a transaction. [address] is null for a script with no address, such as OP_RETURN. */
data class EsploraOutput(val address: String?, val valueSats: Long)

/** A transaction from `GET /address/{address}/txs`. [blockHeight] and [blockTimeSec] are null while it is unconfirmed. */
data class EsploraTx(
    val txid: String,
    val inputs: List<EsploraInput>,
    val outputs: List<EsploraOutput>,
    val blockHeight: Long?,
    val blockTimeSec: Long?,
)

/** An unspent output from `GET /address/{address}/utxo`. [blockHeight] is null while it is unconfirmed. */
data class EsploraUtxo(val txid: String, val vout: Int, val valueSats: Long, val blockHeight: Long?)

/** A server answer that Lumio cannot use: an HTTP error status, a body that is not the Esplora format, or a body that is too large. */
class EsploraException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A minimal client for an Esplora HTTP API, the one that blockstream.info and mempool.space serve. It reads the endpoints a watch-only
 * wallet needs, over the base URL it is given, with a 10-second connect and read timeout. An answer is size-capped and parsed strictly:
 * a damaged answer is an [EsploraException], never a partial result. Only the network's own [baseUrl] is ever asked, and the addresses
 * in the paths are checked to be plain Bitcoin address characters first.
 */
class EsploraClient(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    /** The confirmed and mempool history of [address]. */
    fun addressStats(address: String): EsploraAddressStats = EsploraParse.addressStats(get("/address/${checked(address)}"))

    /** The most recent transactions that involve [address]: the mempool first, then up to 25 confirmed ones. */
    fun addressTxs(address: String): List<EsploraTx> = EsploraParse.txs(get("/address/${checked(address)}/txs"))

    /** The unspent outputs of [address], confirmed and unconfirmed. */
    fun addressUtxos(address: String): List<EsploraUtxo> = EsploraParse.utxos(get("/address/${checked(address)}/utxo"))

    /** The height of the best block the server knows. */
    fun tipHeight(): Long = EsploraParse.tipHeight(get("/blocks/tip/height"))

    /** The fee rate in sat/vB for each confirmation target, in blocks. */
    fun feeEstimates(): Map<Int, Double> = EsploraParse.feeEstimates(get("/fee-estimates"))

    private fun get(path: String): String {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            val code = connection.responseCode
            if (code !in 200..299) throw EsploraException("The server answered HTTP $code.")
            return connection.inputStream.use { readBounded(it) }.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun checked(address: String): String {
        require(ADDRESS.matches(address)) { "Not a Bitcoin address." }
        return address
    }

    private companion object {
        const val TIMEOUT_MS = 10_000

        /** The largest answer Lumio reads. The longest history page is far below it. */
        const val MAX_BODY_BYTES = 4 shl 20

        /** Base58 and bech32 addresses are plain letters and digits. */
        val ADDRESS = Regex("[A-Za-z0-9]{14,90}")

        fun readBounded(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BODY_BYTES) throw EsploraException("The server's answer is larger than Lumio reads.")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

/** The parsers of the Esplora answers. Each one rejects an answer that lacks a field it needs, rather than guessing at it. */
internal object EsploraParse {
    fun addressStats(text: String): EsploraAddressStats = guard(text) { root ->
        EsploraAddressStats(side(root.obj("chain_stats")), side(root.obj("mempool_stats")))
    }

    fun txs(text: String): List<EsploraTx> = guardList(text) { item ->
        val tx = item.jsonObject
        val status = tx.obj("status")
        val confirmed = status.bool("confirmed")
        EsploraTx(
            txid = tx.str("txid"),
            inputs = tx.arr("vin").map { input ->
                val prevout = (input.jsonObject["prevout"] as? JsonObject)?.let { p ->
                    EsploraPrevout(address = p.optStr("scriptpubkey_address"), valueSats = p.long("value"))
                }
                EsploraInput(prevout)
            },
            outputs = tx.arr("vout").map { out ->
                val o = out.jsonObject
                EsploraOutput(address = o.optStr("scriptpubkey_address"), valueSats = o.long("value"))
            },
            blockHeight = if (confirmed) status.optLong("block_height") else null,
            blockTimeSec = if (confirmed) status.optLong("block_time") else null,
        )
    }

    fun utxos(text: String): List<EsploraUtxo> = guardList(text) { item ->
        val utxo = item.jsonObject
        val status = utxo.obj("status")
        val confirmed = status.bool("confirmed")
        EsploraUtxo(
            txid = utxo.str("txid"),
            vout = utxo.long("vout").also { require(it in 0..Int.MAX_VALUE) { "vout out of range" } }.toInt(),
            valueSats = utxo.long("value"),
            blockHeight = if (confirmed) status.optLong("block_height") else null,
        )
    }

    fun tipHeight(text: String): Long {
        val height = text.trim().toLongOrNull()
        if (height == null || height < 0) throw EsploraException("The server's tip height is not a number.")
        return height
    }

    fun feeEstimates(text: String): Map<Int, Double> = guard(text) { root ->
        root.entries.associate { (target, rate) ->
            val blocks = target.toIntOrNull() ?: throw IllegalArgumentException("fee target is not a number")
            blocks to ((rate as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("fee rate is not a number"))
        }
    }

    private fun side(o: JsonObject): EsploraSide = EsploraSide(
        fundedCount = o.long("funded_txo_count"),
        fundedSum = o.long("funded_txo_sum"),
        spentCount = o.long("spent_txo_count"),
        spentSum = o.long("spent_txo_sum"),
        txCount = o.long("tx_count"),
    )

    private inline fun <T> guard(text: String, block: (JsonObject) -> T): T = try {
        block(Json.parseToJsonElement(text).jsonObject)
    } catch (e: IllegalArgumentException) {
        throw EsploraException("The server's answer is not in the Esplora format.", e)
    } catch (e: IllegalStateException) {
        throw EsploraException("The server's answer is not in the Esplora format.", e)
    }

    private inline fun <T> guardList(text: String, block: (JsonElement) -> T): List<T> = try {
        Json.parseToJsonElement(text).let { element ->
            (element as? JsonArray ?: throw IllegalArgumentException("not an array")).map(block)
        }
    } catch (e: IllegalArgumentException) {
        throw EsploraException("The server's answer is not in the Esplora format.", e)
    } catch (e: IllegalStateException) {
        throw EsploraException("The server's answer is not in the Esplora format.", e)
    }

    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: throw IllegalArgumentException("missing object $key")

    private fun JsonObject.arr(key: String): JsonArray =
        this[key] as? JsonArray ?: throw IllegalArgumentException("missing array $key")

    private fun JsonObject.str(key: String): String {
        val primitive = this[key] as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw IllegalArgumentException("missing string $key")
        return primitive.content
    }

    private fun JsonObject.optStr(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: throw IllegalArgumentException("missing number $key")

    private fun JsonObject.optLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
}
