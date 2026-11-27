package app.lensly.core.registry

import app.airvault.abi.AbiType
import app.airvault.abi.AbiTypes
import app.airvault.abi.AbiValue
import app.airvault.abi.ArrayType
import app.airvault.abi.AtomicType
import app.airvault.abi.DecodedParam
import app.airvault.abi.DynamicBytes
import app.airvault.abi.TupleType
import app.airvault.abi.WhitelistParam
import app.airvault.abi.hexBytesOrNull
import app.airvault.abi.utf8TextOf
import java.math.BigInteger
import org.web3j.crypto.Keys

/**
 * The rows a review shows for one decoded argument, as label and value pairs in order. A static argument is one row, labelled with its
 * name and type. An array is a row with its item count, then one row per item, labelled `name[i]`. A tuple has no row of its own: each
 * field is a row, labelled `name.field` with the name the entry gives it, or `name.0`, `name.1` ... when the entry names none.
 *
 * An address is its EIP-55 checksum, a uint or int its exact decimal, a bool true or false, and a bytesN its full hex. A bytes value shows
 * its length and the first [PREVIEW_BYTES] bytes as hex, with an ellipsis when it is longer. The review shows the call's full calldata in
 * a Calldata row beside these arguments, so every byte is shown. A string shows its text in quotes. [amountOf] may replace the value of a
 * top-level uint marked tokenRef, and returns null to keep it.
 */
object AbiRows {
    /** One row: its [label] and [text], and whether [text] is an address, so a review can show it as one. */
    data class Row(val label: String, val text: String, val isAddress: Boolean = false)

    private const val PREVIEW_BYTES = 32
    private const val ADDRESS_HEX = 40
    private const val TWO_POW_256 = 256

    /** The rows of [decoded], in the order the review lists them, as label and text. */
    fun of(decoded: DecodedParam, amountOf: (BigInteger) -> String? = { null }): List<Pair<String, String>> =
        typed(decoded, amountOf).map { it.label to it.text }

    /** The rows of [decoded] as [of] lists them, each marked when it is an address: an `address` argument, or an item or field of one. */
    fun typed(decoded: DecodedParam, amountOf: (BigInteger) -> String? = { null }): List<Row> {
        val type = checkNotNull(AbiTypes.parse(decoded.param.type)) { "param type ${decoded.param.type} is not canonical" }
        val rows = ArrayList<Row>()
        node(decoded.param.name, type, decoded.param, decoded.value, rows)
        val atomic = type as? AtomicType
        val word = decoded.value as? AbiValue.Word
        if (decoded.param.tokenRef && atomic != null && word != null) {
            amountOf(BigInteger(word.hex, 16))?.let { rows[0] = rows[0].copy(text = it) }
        }
        return rows
    }

    /**
     * Adds the rows of [value], which has [type]. [shape] is the param whose components name the tuple at the base of [type], or null
     * when there are none to name.
     */
    private fun node(label: String, type: AbiType, shape: WhitelistParam?, value: AbiValue, rows: MutableList<Row>) {
        when (type) {
            is AtomicType -> rows += Row("$label (${type.canonical})", wordText(type.canonical, (value as AbiValue.Word).hex), type.canonical == "address")
            is DynamicBytes -> rows += Row("$label (${type.canonical})", dynamicText(type.canonical, (value as AbiValue.Dynamic).hex))
            is ArrayType -> {
                val items = (value as AbiValue.Items).items
                rows += Row("$label (${typeName(type)})", itemCount(items.size))
                items.forEachIndexed { index, item -> node("$label[$index]", type.element, shape, item, rows) }
            }
            is TupleType -> {
                val fields = (value as AbiValue.Items).items
                fields.forEachIndexed { index, field ->
                    val component = shape?.components?.getOrNull(index)
                    val name = component?.name ?: index.toString()
                    node("$label.$name", type.components[index], component, field, rows)
                }
            }
        }
    }

    /** The type as a row shows it: a tuple is `tuple`, so a list of structs stays short, and each array dimension is kept. */
    private fun typeName(type: AbiType): String = when (type) {
        is TupleType -> "tuple"
        is ArrayType -> typeName(type.element) + "[" + (type.length?.toString() ?: "") + "]"
        else -> type.canonical
    }

    private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

    private fun wordText(type: String, hex: String): String = when {
        type == "address" -> Keys.toChecksumAddress("0x" + hex.takeLast(ADDRESS_HEX))
        type == "bool" -> if (hex.last() == '1') "true" else "false"
        type.startsWith("uint") -> BigInteger(hex, 16).toString()
        type.startsWith("int") -> {
            val unsigned = BigInteger(hex, 16)
            val signed = if (unsigned.testBit(TWO_POW_256 - 1)) unsigned - BigInteger.ONE.shiftLeft(TWO_POW_256) else unsigned
            signed.toString()
        }
        else -> "0x" + hex.take(2 * type.removePrefix("bytes").toInt())
    }

    private fun dynamicText(type: String, hex: String): String {
        val bytes = hex.length / 2
        if (type == "string") return "\"" + checkNotNull(utf8TextOf(checkNotNull(hexBytesOrNull(hex)))) + "\""
        if (bytes == 0) return "0 bytes"
        val preview = hex.take(2 * PREVIEW_BYTES)
        val ellipsis = if (bytes > PREVIEW_BYTES) "…" else ""
        return "$bytes bytes: 0x$preview$ellipsis"
    }
}
