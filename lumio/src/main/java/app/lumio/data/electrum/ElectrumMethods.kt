package app.lumio.data.electrum

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The scripthash of [scriptPubKey], which is how Electrum servers index a script: its SHA-256 with the bytes in reverse order, as lowercase hex.
 * An address is looked up by the scripthash of its scriptPubKey.
 */
fun scripthash(scriptPubKey: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(scriptPubKey).reversedArray().toLowerHex()

internal fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** A scripthash's balance in satoshis, with the confirmed side and the unconfirmed side kept apart. */
data class ScripthashBalance(val confirmedSats: Long, val unconfirmedSats: Long)

/**
 * A transaction in a scripthash's history. [height] is 0 or -1 while the transaction is unconfirmed. [feeSats] is set for some unconfirmed
 * transactions only.
 */
data class ScripthashHistoryItem(val txid: String, val height: Long, val feeSats: Long?)

/** An unspent output of a scripthash. */
data class ScripthashUnspent(val txid: String, val vout: Int, val height: Long, val valueSats: Long)

/** The tip of the best chain: its height and its raw header in hex. */
data class HeaderTip(val height: Long, val hex: String)

/**
 * The balance of each scripthash, in the order of [scripthashes]. One batch carries them all, split at the server's batch limit. A scripthash
 * that the server refuses has a failure in its own slot.
 */
suspend fun ElectrumConnection.scripthashBalances(scripthashes: List<String>): List<Result<ScripthashBalance>> =
    scripthashBatch("blockchain.scripthash.get_balance", scripthashes) { reply ->
        val fields = reply.jsonObject
        ScripthashBalance(fields.long("confirmed"), fields.long("unconfirmed"))
    }

/** The history of each scripthash, in the order of [scripthashes]. */
suspend fun ElectrumConnection.scripthashHistories(scripthashes: List<String>): List<Result<List<ScripthashHistoryItem>>> =
    scripthashBatch("blockchain.scripthash.get_history", scripthashes) { reply ->
        reply.jsonArray.map { item ->
            val fields = item.jsonObject
            ScripthashHistoryItem(fields.string("tx_hash"), fields.long("height"), fields["fee"]?.jsonPrimitive?.long)
        }
    }

/** The unspent outputs of each scripthash, in the order of [scripthashes]. */
suspend fun ElectrumConnection.scripthashUnspent(scripthashes: List<String>): List<Result<List<ScripthashUnspent>>> =
    scripthashBatch("blockchain.scripthash.listunspent", scripthashes) { reply ->
        reply.jsonArray.map { item ->
            val fields = item.jsonObject
            ScripthashUnspent(fields.string("tx_hash"), fields.long("tx_pos").toInt(), fields.long("height"), fields.long("value"))
        }
    }

/** The tip of the best chain. The server sends each new tip afterwards, and those notifications are ignored. */
suspend fun ElectrumConnection.headerTip(): HeaderTip {
    val fields = call("blockchain.headers.subscribe", JsonArray(emptyList())).jsonObject
    return HeaderTip(fields.long("height"), fields.string("hex"))
}

/** The raw transaction [txid], in hex. A server that does not know it answers [ElectrumError.Rpc]. */
suspend fun ElectrumConnection.transactionHex(txid: String): String =
    call("blockchain.transaction.get", buildJsonArray { add(JsonPrimitive(txid)) }).jsonPrimitive.content

private suspend fun <T> ElectrumConnection.scripthashBatch(
    method: String,
    scripthashes: List<String>,
    parse: (JsonElement) -> T,
): List<Result<T>> = batch(scripthashes.map { RpcCall(method, buildJsonArray { add(JsonPrimitive(it)) }) })
    .map { reply -> reply.mapCatching(parse) }

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
