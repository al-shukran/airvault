package app.lensly.core.evm

import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits

/** Raised for transaction bytes that are malformed, non-canonical, or outside the supported schema. */
internal class MalformedTxException(message: String) : Exception(message)

internal fun malformedTx(reason: String): Nothing = throw MalformedTxException(reason)

/**
 * Strict RLP. Single bytes must not be wrapped, lengths must be minimal, and the input must be exactly one item.
 * Input larger than [ParserLimits.MAX_SIGN_DATA_BYTES], nesting deeper than [ParserLimits.MAX_RLP_DEPTH], more than
 * [ParserLimits.MAX_RLP_ITEMS] items, or a decode that runs past its time budget are all rejected.
 */
internal sealed class Rlp {
    class Str(val bytes: ByteArray) : Rlp()

    class Lst(val items: List<Rlp>) : Rlp()

    companion object {
        fun decode(
            bytes: ByteArray,
            budget: DecodeBudget = DecodeBudget("RLP", ParserLimits.MAX_RLP_ITEMS),
        ): Rlp {
            if (bytes.size > ParserLimits.MAX_SIGN_DATA_BYTES) {
                malformedTx("RLP is larger than ${ParserLimits.MAX_SIGN_DATA_BYTES} bytes")
            }
            val reader = Reader(bytes, budget)
            val item = reader.read(depth = 0, limit = bytes.size)
            if (reader.position != bytes.size) malformedTx("trailing bytes after RLP item")
            return item
        }
    }

    private class Reader(private val bytes: ByteArray, private val budget: DecodeBudget) {
        var position = 0
            private set

        fun read(depth: Int, limit: Int): Rlp {
            budget.charge()?.let { malformedTx(it) }
            if (depth > ParserLimits.MAX_RLP_DEPTH) malformedTx("RLP nested deeper than ${ParserLimits.MAX_RLP_DEPTH}")
            if (position >= limit) malformedTx("truncated RLP")
            val prefix = bytes[position++].toInt() and 0xFF
            return when {
                prefix < 0x80 -> Str(byteArrayOf(prefix.toByte()))
                prefix < 0xB8 -> {
                    val length = prefix - 0x80
                    val payload = take(length, limit)
                    if (length == 1 && (payload[0].toInt() and 0xFF) < 0x80) {
                        malformedTx("single byte below 0x80 must not be length-prefixed")
                    }
                    Str(payload)
                }
                prefix < 0xC0 -> Str(take(longLength(prefix - 0xB7, limit), limit))
                prefix < 0xF8 -> list(prefix - 0xC0, depth, limit)
                else -> list(longLength(prefix - 0xF7, limit), depth, limit)
            }
        }

        private fun take(length: Int, limit: Int): ByteArray {
            if (length > limit - position) malformedTx("truncated RLP")
            val start = position
            position += length
            return bytes.copyOfRange(start, position)
        }

        private fun list(length: Int, depth: Int, limit: Int): Rlp {
            if (length > limit - position) malformedTx("truncated RLP")
            val end = position + length
            val items = ArrayList<Rlp>()
            while (position < end) items.add(read(depth + 1, end))
            return Lst(items)
        }

        /** Reads a long-form length. Long forms must be used only for payloads of 56 bytes or more, with no leading zero. */
        private fun longLength(lengthBytes: Int, limit: Int): Int {
            if (lengthBytes > limit - position) malformedTx("truncated RLP length")
            if (bytes[position] == 0.toByte()) malformedTx("RLP length has a leading zero")
            var value = 0L
            repeat(lengthBytes) { value = (value shl 8) or (bytes[position++].toLong() and 0xFF) }
            if (value < 56) malformedTx("RLP length should use the short form")
            if (value > limit - position) malformedTx("truncated RLP")
            return value.toInt()
        }
    }
}
