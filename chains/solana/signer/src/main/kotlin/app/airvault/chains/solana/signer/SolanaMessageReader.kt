package app.airvault.chains.solana.signer

/** A message that does not parse or fails a bounds check. [SolanaMessage.decode] turns it into a rejection. */
class MalformedSolanaMessageException(message: String) : Exception(message)

internal fun malformedSolanaMessage(reason: String): Nothing = throw MalformedSolanaMessageException(reason)

/**
 * Reads a serialized message front to back. Every read checks the bytes that remain, so short input is a rejection and never an
 * index error.
 */
class SolanaMessageReader(private val input: ByteArray) {
    private var position = 0

    val remaining: Int get() = input.size - position

    fun peek(what: String): Int {
        if (remaining < 1) malformedSolanaMessage("the message ends before $what")
        return input[position].toInt() and 0xFF
    }

    fun u8(what: String): Int = peek(what).also { position++ }

    fun bytes(count: Int, what: String): ByteArray {
        if (count > remaining) malformedSolanaMessage("the message ends inside $what")
        return input.copyOfRange(position, position + count).also { position += count }
    }

    /**
     * A compact-u16 ("shortvec") value: 7 bits per byte, least significant group first, at most 3 bytes and at most 65535. Only the
     * shortest form is accepted, so 0x80 0x00 (a longer form of 0) is refused.
     */
    fun shortU16(what: String): Int {
        var value = 0
        for (index in 0 until 3) {
            val byte = u8(what)
            // The third byte carries bits 14 and 15 only, so it must be 0x00 to 0x03: that also refuses a fourth byte.
            if (index == 2 && byte > 0x03) malformedSolanaMessage("$what: the length is above 65535 or longer than 3 bytes")
            value = value or ((byte and 0x7F) shl (7 * index))
            if ((byte and 0x80) == 0) {
                if (index > 0 && byte == 0) malformedSolanaMessage("$what: the length is not in its shortest form")
                return value
            }
        }
        malformedSolanaMessage("$what: the length is longer than 3 bytes")
    }

    /** A count of items that each take at least [minBytesEach] bytes. Checked before any item is read or allocated. */
    fun count(what: String, minBytesEach: Int): Int {
        val count = shortU16(what)
        if (count.toLong() * minBytesEach > remaining) {
            malformedSolanaMessage("$what: $count items cannot fit in the $remaining bytes left")
        }
        return count
    }
}
