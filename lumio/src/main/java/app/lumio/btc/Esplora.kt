package app.lumio.btc

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One side of an address's history, in satoshis: the confirmed chain, or the mempool. */
@Serializable
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
@Serializable
data class EsploraAddressStats(val chain: EsploraSide, val mempool: EsploraSide) {
    /** True once the address has appeared in any transaction, confirmed or not. The gap-limit scan counts an address that is not used. */
    val used: Boolean get() = chain.txCount + mempool.txCount > 0

    /** The coins the address holds, confirmed and unconfirmed. Zero means it holds none, so its unspent outputs are not asked for. */
    val balance: Long get() = chain.net + mempool.net
}

/** The previous output an input spends. [address] is null for a script with no address. */
@Serializable
data class EsploraPrevout(val address: String?, val valueSats: Long)

/** One input of a transaction. A coinbase input has no previous output. */
@Serializable
data class EsploraInput(val prevout: EsploraPrevout?)

/** One output of a transaction. [address] is null for a script with no address, such as OP_RETURN. */
@Serializable
data class EsploraOutput(val address: String?, val valueSats: Long)

/** A transaction from `GET /address/{address}/txs`. [blockHeight] and [blockTimeSec] are null while it is unconfirmed. */
@Serializable
data class EsploraTx(
    val txid: String,
    val inputs: List<EsploraInput>,
    val outputs: List<EsploraOutput>,
    val blockHeight: Long?,
    val blockTimeSec: Long?,
)

/** An unspent output from `GET /address/{address}/utxo`. [blockHeight] is null while it is unconfirmed. */
@Serializable
data class EsploraUtxo(val txid: String, val vout: Int, val valueSats: Long, val blockHeight: Long?)

/** A block as `GET /block/{hash}` reports it: its hash, its height, and its time in seconds. */
@Serializable
data class EsploraBlock(val hash: String, val height: Long, val timestampSec: Long)

/**
 * A transaction's status as `GET /tx/{txid}` reports it. [blockHeight], [blockHash] and [blockTimeSec] are null while it is unconfirmed.
 */
@Serializable
data class EsploraTxStatus(
    val txid: String,
    val confirmed: Boolean,
    val blockHeight: Long?,
    val blockHash: String?,
    val blockTimeSec: Long?,
) {
    /** The confirmations at chain tip [tipHeight]: zero while unconfirmed, and one in the block itself. */
    fun confirmationsAt(tipHeight: Long): Long = blockHeight?.let { maxOf(0L, tipHeight - it + 1) } ?: 0L
}

/** A server answer that Lumio cannot use: a body that is not the Esplora format, or a body that is too large. */
class EsploraException(message: String, cause: Throwable? = null) : IOException(message, cause)

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

    /** The raw transaction, in hex, as `GET /tx/{txid}/hex` answers it. Lowercase, with an even number of digits. */
    fun rawTx(text: String): String {
        val hex = text.trim()
        if (!HEX.matches(hex) || hex.length % 2 != 0) throw EsploraException("The server's answer is not a transaction in hex.")
        return hex.lowercase()
    }

    /** The transaction id that `POST /tx` answers with: 64 hex digits of text. Lowercase. */
    fun broadcastTxid(text: String): String {
        val txid = text.trim()
        if (!TXID.matches(txid)) throw EsploraException("The server's answer is not a transaction id.")
        return txid.lowercase()
    }

    private val HEX = Regex("[0-9a-fA-F]+")
    private val TXID = Regex("[0-9a-fA-F]{64}")

    fun feeEstimates(text: String): Map<Int, Double> = guard(text) { root ->
        root.entries.associate { (target, rate) ->
            val blocks = target.toIntOrNull() ?: throw IllegalArgumentException("fee target is not a number")
            blocks to ((rate as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("fee rate is not a number"))
        }
    }

    fun block(text: String): EsploraBlock = guard(text) { root ->
        EsploraBlock(hash = root.str("id"), height = root.long("height"), timestampSec = root.long("timestamp"))
    }

    fun txStatus(text: String): EsploraTxStatus = guard(text) { root ->
        val status = root.obj("status")
        val confirmed = status.bool("confirmed")
        EsploraTxStatus(
            txid = root.str("txid"),
            confirmed = confirmed,
            blockHeight = if (confirmed) status.optLong("block_height") else null,
            blockHash = if (confirmed) status.optStr("block_hash") else null,
            blockTimeSec = if (confirmed) status.optLong("block_time") else null,
        )
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
