package app.lensly.core.algorand

/**
 * Reads the msgpack subset that Algorand transactions use, and refuses each encoding that the canonical rules forbid.
 *
 * The rules are the "Canonical Msgpack" section of https://specs.algorand.co/crypto/crypto-msgpack: a map lists its keys
 * in lexicographic order and omits zero-valued pairs, an integer uses the shortest encoding, a positive value is
 * unsigned, and binary data uses the bin family. go-algorand encodes this way: `Canonical`, `RecursiveEmptyCheck` and
 * `PositiveIntUnsigned` are set in https://github.com/algorand/go-algorand/blob/master/protocol/codec.go.
 *
 * Lengths take their shortest header too: fixstr, str8, str16 or str32; bin8, bin16 or bin32; fixmap or map16. map32 is
 * refused, since no transaction map is that large. The reader does not know field names. [AlgoTxnDecoder] applies the
 * zero-value rule and the per-field lengths, and passes each field name in for the error messages.
 */
internal class CanonicalMsgpackReader(private val bytes: ByteArray) {
    private var pos = 0

    /** True once every byte has been read. */
    fun atEnd(): Boolean = pos == bytes.size

    /** The entry count of the transaction map. */
    fun readMapSize(): Int {
        val tag = readTag()
        if (tag in 0x80..0x8f) return tag and 0x0f
        if (tag != 0xde) unexpected("transaction", "a map", tag)
        val count = readBigEndian(2)
        if (count < 16UL) {
            throw AlgoTxnException("transaction map is not canonical: map16 holds $count entries, which fixmap holds")
        }
        return count.toInt()
    }

    /** An unsigned integer, in the shortest of positive fixint, uint8, uint16, uint32 and uint64. */
    fun readUInt(field: String): ULong {
        val tag = readTag()
        return when (tag) {
            in 0x00..0x7f -> tag.toULong()
            0xcc -> readBigEndian(1).also { shortest(field, "uint8", it, 0x80UL) }
            0xcd -> readBigEndian(2).also { shortest(field, "uint16", it, 0x100UL) }
            0xce -> readBigEndian(4).also { shortest(field, "uint32", it, 0x1_0000UL) }
            0xcf -> readBigEndian(8).also { shortest(field, "uint64", it, 0x1_0000_0000UL) }
            else -> unexpected(field, "an unsigned integer", tag)
        }
    }

    /** Binary data of at most [maxLength] bytes, in the shortest bin header. */
    fun readBin(field: String, maxLength: Int): ByteArray {
        val tag = readTag()
        val length = when (tag) {
            0xc4 -> readBigEndian(1)
            0xc5 -> readBigEndian(2).also { shortest(field, "bin16", it, 0x100UL) }
            0xc6 -> readBigEndian(4).also { shortest(field, "bin32", it, 0x1_0000UL) }
            else -> unexpected(field, "binary data", tag)
        }
        return take(field, length, maxLength)
    }

    /** The UTF-8 bytes of a string of at most [maxLength] bytes, in the shortest str header. */
    fun readStr(field: String, maxLength: Int): ByteArray {
        val tag = readTag()
        val length = when (tag) {
            in 0xa0..0xbf -> (tag and 0x1f).toULong()
            0xd9 -> readBigEndian(1).also { shortest(field, "str8", it, 0x20UL) }
            0xda -> readBigEndian(2).also { shortest(field, "str16", it, 0x100UL) }
            0xdb -> readBigEndian(4).also { shortest(field, "str32", it, 0x1_0000UL) }
            else -> unexpected(field, "a string", tag)
        }
        return take(field, length, maxLength)
    }

    private fun take(field: String, length: ULong, maxLength: Int): ByteArray {
        if (length > maxLength.toULong()) {
            throw AlgoTxnException("'$field' is $length bytes; the limit is $maxLength")
        }
        val n = length.toInt()
        if (n > bytes.size - pos) throw AlgoTxnException(TRUNCATED)
        return bytes.copyOfRange(pos, pos + n).also { pos += n }
    }

    /** Refuses a value whose format is longer than [floor] needs. */
    private fun shortest(field: String, format: String, value: ULong, floor: ULong) {
        if (value < floor) {
            throw AlgoTxnException("'$field' is not canonical: $format holds $value, which a shorter format holds")
        }
    }

    private fun unexpected(field: String, expected: String, tag: Int): Nothing {
        val hex = tag.toString(16).padStart(2, '0')
        throw AlgoTxnException("'$field' must be $expected; found msgpack format 0x$hex")
    }

    private fun readTag(): Int {
        if (pos >= bytes.size) throw AlgoTxnException(TRUNCATED)
        return bytes[pos++].toInt() and 0xff
    }

    private fun readBigEndian(width: Int): ULong {
        var value = 0UL
        repeat(width) { value = (value shl 8) or readTag().toULong() }
        return value
    }
}

private const val TRUNCATED = "transaction ends in the middle of a value"

/** Orders two map keys as the canonical encoding does: unsigned bytes left to right, a shorter prefix first. */
internal fun lexicographicOrder(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (diff != 0) return diff
    }
    return a.size - b.size
}
