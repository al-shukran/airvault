package app.lensly.core.algorand

import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.signer.AlgoTxnDecoder

/**
 * Encodes an unsigned payment as the canonical msgpack that an Algorand account signs. It is the writing side of
 * [AlgoTxnDecoder], and it follows the same rules: keys in lexicographic order, the shortest header for every integer,
 * string, binary value and map, and no zero or empty optional field (go-algorand's codec leaves those out).
 *
 * The encoding is decoded again before it is returned, so a payment that the decoder would refuse throws
 * [AlgoTxnException] and is never encoded. A payment decoded from an SDK encoding encodes back to the same bytes.
 */
object AlgoTxnEncoder {
    /** The canonical encoding of [txn], a payment. Zero or empty optional fields are left out. */
    fun encodePayment(txn: Payment): ByteArray {
        val fields = ArrayList<Pair<String, ByteArray>>()
        fields += "type" to msgpackStr("pay")
        fields += "snd" to msgpackBin(txn.sender)
        if (txn.fee != 0UL) fields += "fee" to msgpackUInt(txn.fee)
        fields += "fv" to msgpackUInt(txn.firstValid)
        fields += "lv" to msgpackUInt(txn.lastValid)
        if (txn.genesisId.isNotEmpty()) fields += "gen" to msgpackStr(txn.genesisId)
        fields += "gh" to msgpackBin(txn.genesisHash)
        if (txn.note.isNotEmpty()) fields += "note" to msgpackBin(txn.note)
        txn.lease?.takeIf { it.isNotEmpty() }?.let { fields += "lx" to msgpackBin(it) }
        txn.group?.takeIf { it.isNotEmpty() }?.let { fields += "grp" to msgpackBin(it) }
        txn.rekeyTo?.takeIf { it.isNotEmpty() }?.let { fields += "rekey" to msgpackBin(it) }
        fields += "rcv" to msgpackBin(txn.receiver)
        if (txn.amount != 0UL) fields += "amt" to msgpackUInt(txn.amount)
        txn.closeTo?.takeIf { it.isNotEmpty() }?.let { fields += "close" to msgpackBin(it) }
        val encoded = msgpackMap(fields.sortedBy { it.first })
        AlgoTxnDecoder.decode(encoded)
        return encoded
    }

    /**
     * The canonical encoding of [txn], an asset transfer (`axfer`). An opt-in is one with a zero amount to the sender's own address, and it is
     * encoded like any other. Zero or empty optional fields are left out, as they are for a payment.
     */
    fun encodeAssetTransfer(txn: AssetTransfer): ByteArray {
        val fields = ArrayList<Pair<String, ByteArray>>()
        fields += "type" to msgpackStr("axfer")
        fields += "snd" to msgpackBin(txn.sender)
        if (txn.fee != 0UL) fields += "fee" to msgpackUInt(txn.fee)
        fields += "fv" to msgpackUInt(txn.firstValid)
        fields += "lv" to msgpackUInt(txn.lastValid)
        if (txn.genesisId.isNotEmpty()) fields += "gen" to msgpackStr(txn.genesisId)
        fields += "gh" to msgpackBin(txn.genesisHash)
        if (txn.note.isNotEmpty()) fields += "note" to msgpackBin(txn.note)
        txn.lease?.takeIf { it.isNotEmpty() }?.let { fields += "lx" to msgpackBin(it) }
        txn.group?.takeIf { it.isNotEmpty() }?.let { fields += "grp" to msgpackBin(it) }
        txn.rekeyTo?.takeIf { it.isNotEmpty() }?.let { fields += "rekey" to msgpackBin(it) }
        fields += "xaid" to msgpackUInt(txn.assetId)
        if (txn.amount != 0UL) fields += "aamt" to msgpackUInt(txn.amount)
        fields += "arcv" to msgpackBin(txn.receiver)
        txn.closeTo?.takeIf { it.isNotEmpty() }?.let { fields += "aclose" to msgpackBin(it) }
        txn.assetSender?.takeIf { it.isNotEmpty() }?.let { fields += "asnd" to msgpackBin(it) }
        val encoded = msgpackMap(fields.sortedBy { it.first })
        AlgoTxnDecoder.decode(encoded)
        return encoded
    }
}

/** An unsigned integer in its shortest msgpack format: a positive fixint, uint8, uint16, uint32 or uint64. */
internal fun msgpackUInt(value: ULong): ByteArray = when {
    value < 0x80UL -> byteArrayOf(value.toInt().toByte())
    value < 0x100UL -> byteArrayOf(0xcc.toByte()) + bigEndian(value, 1)
    value < 0x1_0000UL -> byteArrayOf(0xcd.toByte()) + bigEndian(value, 2)
    value < 0x1_0000_0000UL -> byteArrayOf(0xce.toByte()) + bigEndian(value, 4)
    else -> byteArrayOf(0xcf.toByte()) + bigEndian(value, 8)
}

/** A string in its shortest msgpack format: fixstr, str8, str16 or str32, over the UTF-8 bytes. */
internal fun msgpackStr(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val n = bytes.size
    val header = when {
        n < 0x20 -> byteArrayOf((0xa0 or n).toByte())
        n < 0x100 -> byteArrayOf(0xd9.toByte(), n.toByte())
        n < 0x1_0000 -> byteArrayOf(0xda.toByte()) + bigEndian(n.toULong(), 2)
        else -> byteArrayOf(0xdb.toByte()) + bigEndian(n.toULong(), 4)
    }
    return header + bytes
}

/** Binary data in its shortest msgpack format: bin8, bin16 or bin32. */
internal fun msgpackBin(bytes: ByteArray): ByteArray {
    val n = bytes.size
    val header = when {
        n < 0x100 -> byteArrayOf(0xc4.toByte(), n.toByte())
        n < 0x1_0000 -> byteArrayOf(0xc5.toByte()) + bigEndian(n.toULong(), 2)
        else -> byteArrayOf(0xc6.toByte()) + bigEndian(n.toULong(), 4)
    }
    return header + bytes
}

/** A map of [entries], in the order given, with the shortest header for its count: fixmap, or map16 from 16 entries. */
internal fun msgpackMap(entries: List<Pair<String, ByteArray>>): ByteArray {
    val n = entries.size
    val header = if (n < 0x10) byteArrayOf((0x80 or n).toByte()) else byteArrayOf(0xde.toByte()) + bigEndian(n.toULong(), 2)
    return entries.fold(header) { acc, (key, value) -> acc + msgpackStr(key) + value }
}

private fun bigEndian(value: ULong, width: Int): ByteArray =
    ByteArray(width) { ((value shr (8 * (width - 1 - it))) and 0xFFUL).toInt().toByte() }
