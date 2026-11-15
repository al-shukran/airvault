package app.lumio.tokens

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A reply that does not decode: a word that is missing, an offset past the end, or text that is not UTF-8. */
internal class AbiException(message: String) : Exception(message)

/**
 * The ABI encoding that Lumio's token reads need, written out: 32-byte words, addresses, unsigned integers, and the dynamic bytes and text of a
 * reply. Hex text has a 0x prefix and lowercase or uppercase digits.
 */
internal object Abi {
    const val WORD = 32
    private val HEX = Regex("0x([0-9a-fA-F]{2})*")
    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    private val TWO_POW_256 = BigInteger.ONE.shiftLeft(256)

    /** The bytes of the hex [text]. Throws [AbiException] when [text] is not hex data. */
    fun bytes(text: String): ByteArray {
        if (!HEX.matches(text)) throw AbiException("The reply is not hex data.")
        return ByteArray((text.length - 2) / 2) { i -> text.substring(2 + 2 * i, 4 + 2 * i).toInt(16).toByte() }
    }

    /** [bytes] as hex text with a 0x prefix. */
    fun hex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** [value] as one 32-byte word, big-endian. Throws IllegalArgumentException outside 0 until 2^256. */
    fun word(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value < TWO_POW_256) { "A word holds 0 to 2^256 - 1." }
        val raw = value.toByteArray()
        val out = ByteArray(WORD)
        val count = minOf(raw.size, WORD)
        System.arraycopy(raw, raw.size - count, out, WORD - count, count)
        return out
    }

    fun word(value: Int): ByteArray = word(BigInteger.valueOf(value.toLong()))

    /** The address [address] (0x and 40 hex digits) as a word, left-padded with zeros. Throws IllegalArgumentException for other text. */
    fun addressWord(address: String): ByteArray {
        require(ADDRESS.matches(address)) { "Not an EVM address." }
        return bytes("0x" + "00".repeat(12) + address.substring(2))
    }

    /** The unsigned integer in the word at [at]. */
    fun uint(bytes: ByteArray, at: Int = 0): BigInteger {
        if (at < 0 || at + WORD > bytes.size) throw AbiException("A word is missing from the reply.")
        return BigInteger(1, bytes.copyOfRange(at, at + WORD))
    }

    /** The word at [at] read as a byte offset or a length within [bytes]. Throws [AbiException] when it points past the end. */
    fun index(bytes: ByteArray, at: Int): Int {
        val value = uint(bytes, at)
        if (value > BigInteger.valueOf(bytes.size.toLong())) throw AbiException("An offset points past the reply.")
        return value.toInt()
    }

    /** The size of [size] bytes padded to whole words. */
    fun padded(size: Int): Int = (size + WORD - 1) / WORD * WORD

    /** UTF-8 text of [bytes], or null when they are not UTF-8. */
    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/** The four ERC-20 reads Lumio makes. A read is a selector and, for a balance, the owner's address as one word. */
internal object Erc20 {
    const val BALANCE_OF = "0x70a08231"
    const val SYMBOL = "0x95d89b41"
    const val NAME = "0x06fdde03"
    const val DECIMALS = "0x313ce567"

    /** The call data of balanceOf(owner). */
    fun balanceOf(owner: String): String = BALANCE_OF + Abi.hex(Abi.addressWord(owner)).removePrefix("0x")

    /**
     * The text a read returns. A name or symbol is a dynamic string. Some older tokens return a bytes32 with the text left-aligned and zero-padded,
     * and that is read too. Null when the reply is neither, or is empty.
     */
    fun text(reply: ByteArray): String? {
        val dynamic = dynamicText(reply)
        val text = if (dynamic != null) dynamic else if (reply.size == Abi.WORD) Abi.utf8(reply.takeWhile { it != 0.toByte() }.toByteArray()) else null
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun dynamicText(reply: ByteArray): String? {
        if (reply.size < 2 * Abi.WORD) return null
        return try {
            val offset = Abi.index(reply, 0)
            val length = Abi.index(reply, offset)
            val start = offset + Abi.WORD
            if (start + length > reply.size) null else Abi.utf8(reply.copyOfRange(start, start + length))
        } catch (e: AbiException) {
            null
        }
    }
}

/**
 * Multicall3 (https://www.multicall3.com), the contract that runs many calls in one call. Its address is the same on every chain it is deployed to.
 * A chain without it answers a call to that address with no data, so the caller reads each token on its own then.
 */
internal object Multicall3 {
    const val ADDRESS = "0xcA11bde05977b3631167028862bE2a173976CA11"
    const val AGGREGATE3 = "0x82ad56cb"

    /** One call of the batch: [target] runs [data], and [allowFailure] lets the batch go on when that call reverts. */
    class Call(val target: String, val allowFailure: Boolean, val data: String)

    /** One answer of the batch: whether its call succeeded, and the bytes it returned. */
    class Result(val success: Boolean, val data: ByteArray)

    /**
     * The call data of aggregate3((address target, bool allowFailure, bytes callData)[]). The one argument is a dynamic array of dynamic tuples, so it
     * is encoded as an offset, a length, an offset for each tuple, and then the tuples.
     */
    fun aggregate3(calls: List<Call>): String {
        val tuples = calls.map { encodeTuple(it) }
        val out = ByteArrayOutputStream()
        out.write(Abi.word(Abi.WORD))
        out.write(Abi.word(calls.size))
        var offset = calls.size * Abi.WORD
        for (tuple in tuples) {
            out.write(Abi.word(offset))
            offset += tuple.size
        }
        tuples.forEach { out.write(it) }
        return AGGREGATE3 + Abi.hex(out.toByteArray()).removePrefix("0x")
    }

    private fun encodeTuple(call: Call): ByteArray {
        val data = Abi.bytes(call.data)
        val out = ByteArrayOutputStream()
        out.write(Abi.addressWord(call.target))
        out.write(Abi.word(if (call.allowFailure) 1 else 0))
        // The bytes follow the tuple's three head words, so the offset is 3 words.
        out.write(Abi.word(3 * Abi.WORD))
        out.write(Abi.word(data.size))
        out.write(data)
        out.write(ByteArray(Abi.padded(data.size) - data.size))
        return out.toByteArray()
    }

    /**
     * The answers of an aggregate3 reply for [expected] calls, in order. Throws [AbiException] when the reply does not hold that many answers or
     * an answer points outside the reply.
     */
    fun decodeAggregate3(text: String, expected: Int): List<Result> {
        val reply = Abi.bytes(text)
        val base = Abi.index(reply, 0)
        val count = Abi.index(reply, base)
        if (count != expected) throw AbiException("The batch answered $count calls, not $expected.")
        val items = base + Abi.WORD
        return (0 until count).map { i ->
            val at = items + Abi.index(reply, items + i * Abi.WORD)
            val success = Abi.uint(reply, at).signum() != 0
            val dataAt = at + Abi.index(reply, at + Abi.WORD)
            val length = Abi.index(reply, dataAt)
            val start = dataAt + Abi.WORD
            if (start + length > reply.size) throw AbiException("An answer points past the reply.")
            Result(success, reply.copyOfRange(start, start + length))
        }
    }
}
