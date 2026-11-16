package app.lensly.core.algorand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The entries of `vectors/algorand-txn/vectors.json`, written by `gen_txn_vectors.py` with py-algorand-sdk 2.12.0 and
 * msgpack 1.2.4. The README in that folder gives the regeneration command.
 */
internal object AlgoTxnVectors {
    /**
     * A transaction the SDK encoded. [expected] holds the fields it must decode to, in the shape [fieldsOf] produces.
     * [bytesToSign] is the SDK's own `bytes_to_sign()`.
     */
    class Valid(val name: String, val raw: ByteArray, val bytesToSign: ByteArray, val expected: JsonObject)

    private val root: JsonObject by lazy {
        val text = checkNotNull(AlgoTxnVectors::class.java.getResourceAsStream("/vectors/algorand-txn/vectors.json")) {
            "missing vectors/algorand-txn/vectors.json"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    /** The py-algorand-sdk version that wrote the vectors. */
    val sdkVersion: String by lazy { root.getValue("sdk").jsonPrimitive.content }

    fun valid(): List<Valid> = root.getValue("positive").jsonArray.map { element ->
        val entry = element.jsonObject
        Valid(
            name = entry.text("name"),
            raw = hexToBytes(entry.text("hex")),
            bytesToSign = hexToBytes(entry.text("bytesToSign")),
            expected = entry.getValue("expected").jsonObject,
        )
    }

    /** The encoding the SDK wrote for a rule-breaking case, such as `unsorted_keys`. */
    fun refused(name: String): ByteArray = root.getValue("negative").jsonArray
        .map { it.jsonObject }
        .single { it.text("name") == name }
        .let { hexToBytes(it.text("hex")) }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

internal fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
