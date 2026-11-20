package app.airvault.abi

import app.airvault.core.model.isSingleLinePrintable
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** One decoded argument of a whitelisted call: the param it belongs to and its value, shaped the way the param's type is. */
data class DecodedParam(val param: WhitelistParam, val value: AbiValue)

/**
 * The value of one argument. Hex has no 0x prefix and is lowercase. An address, bool, uintN, intN or bytesN is a [Word], a bytes or
 * string is a [Dynamic], and an array or a tuple is [Items], in order.
 */
sealed interface AbiValue {
    /** One static value, as its 32-byte word. */
    data class Word(val hex: String) : AbiValue

    /** The bytes of a bytes or string value. */
    data class Dynamic(val hex: String) : AbiValue

    /** The items of an array, or the fields of a tuple, in order. */
    data class Items(val items: List<AbiValue>) : AbiValue
}

/** The decoded params of a whitelisted call, or the reason its calldata is refused. */
sealed interface CallDecode {
    data class Ok(val params: List<DecodedParam>) : CallDecode

    data class Refused(val reason: String) : CallDecode
}

/**
 * Decodes the calldata of a whitelisted call from the types its entry declares, and encodes decoded values back into calldata. The
 * layout is the Solidity ABI's: a static type sits in place, and a dynamic one sits behind an offset that counts from the start of the
 * sequence it belongs to. Every offset, length and count is checked against the calldata before anything is read, every word is
 * checked against its type, and the number of values is bounded. The values are then encoded again in canonical form, and a call is
 * refused unless that encoding is the calldata exactly. So no byte is signed without being shown, and no value is shown that is not
 * signed.
 */
object AbiDecoder {
    private const val SELECTOR_BYTES = 4
    private const val WORD_BYTES = 32
    private const val STRING = "string"

    const val LENGTH_REFUSAL = "The calldata does not match the approved signature"
    const val SHORT_REFUSAL = "The calldata is shorter than its arguments"
    const val OFFSET_REFUSAL = "An argument's offset points outside the calldata"
    const val LENGTH_RUN_REFUSAL = "An argument's length runs past the calldata"
    const val RANGE_REFUSAL = "An argument is out of range for its type"
    const val BOOL_REFUSAL = "A bool argument is not 0 or 1"
    const val PADDING_REFUSAL = "An argument has padding that is not zero"
    const val TEXT_REFUSAL = "A string argument holds characters Lensly cannot show"
    const val CANONICAL_REFUSAL = "The calldata is not the canonical encoding of the approved signature"

    /** The params decoded from [calldata], which is the four-byte selector followed by the arguments. */
    fun decode(calldata: ByteArray, params: List<WhitelistParam>): CallDecode {
        if (calldata.size < SELECTOR_BYTES) return CallDecode.Refused(LENGTH_REFUSAL)
        val types = params.map { checkNotNull(AbiTypes.parse(it.type)) { "param type ${it.type} is not canonical" } }
        val arguments = calldata.copyOfRange(SELECTOR_BYTES, calldata.size)
        // Arguments of static types have one exact length. Dynamic ones are measured by their offsets and lengths instead.
        if (types.none { it.dynamic } && arguments.size.toLong() != types.sumOf { it.headBytes }) {
            return CallDecode.Refused(LENGTH_REFUSAL)
        }
        return try {
            val values = Reader(arguments).sequence(types, base = 0)
            if (encodeSequence(types, values)?.contentEquals(arguments) != true) {
                CallDecode.Refused(CANONICAL_REFUSAL)
            } else {
                CallDecode.Ok(params.zip(values) { param, value -> DecodedParam(param, value) })
            }
        } catch (refusal: Refusal) {
            CallDecode.Refused(refusal.reason)
        }
    }

    /** The arguments that [decoded] encodes to, in canonical form, or null when a value does not fit the type of its param. */
    fun encode(decoded: List<DecodedParam>): ByteArray? {
        val types = decoded.map { AbiTypes.parse(it.param.type) ?: return null }
        return encodeSequence(types, decoded.map { it.value })
    }

    /** Raised while a value is read. It never leaves [decode], which turns it into a refusal. */
    private class Refusal(val reason: String) : RuntimeException(reason, null, false, false)

    /** Reads the values of one calldata. Every position is a byte offset into the arguments. */
    private class Reader(private val args: ByteArray) {
        private val budget = DecodeBudget("ABI", ParserLimits.MAX_ABI_VALUES)

        /** The values of [types], laid out as a head and then a tail, from [base]. Offsets in the head count from [base]. */
        fun sequence(types: List<AbiType>, base: Long): List<AbiValue> {
            var head = base
            return types.map { type ->
                if (type.dynamic) {
                    val offset = wordAt(head)
                    head += WORD_BYTES
                    if (offset.bitLength() > MAX_OFFSET_BITS || base + offset.toLong() + WORD_BYTES > args.size) {
                        throw Refusal(OFFSET_REFUSAL)
                    }
                    value(type, base + offset.toLong())
                } else {
                    val at = head
                    head += type.headBytes
                    value(type, at)
                }
            }
        }

        private fun value(type: AbiType, at: Long): AbiValue {
            budget.charge()?.let { throw Refusal(it) }
            return when (type) {
                is AtomicType -> {
                    val word = slice(at, WORD_BYTES)
                    wordRefusal(type.canonical, word)?.let { throw Refusal(it) }
                    AbiValue.Word(word.toHex())
                }
                is DynamicBytes -> dynamicValue(type, at)
                is ArrayType -> {
                    val length = type.length
                    if (length != null) {
                        AbiValue.Items(sequence(List(length) { type.element }, at))
                    } else {
                        // Every item takes at least one word, so a count larger than the words that follow cannot be read.
                        val count = wordAt(at)
                        val wordsLeft = (args.size - at - WORD_BYTES) / WORD_BYTES
                        if (count > BigInteger.valueOf(wordsLeft)) throw Refusal(LENGTH_RUN_REFUSAL)
                        AbiValue.Items(sequence(List(count.toInt()) { type.element }, at + WORD_BYTES))
                    }
                }
                is TupleType -> AbiValue.Items(sequence(type.components, at))
            }
        }

        private fun dynamicValue(type: DynamicBytes, at: Long): AbiValue {
            val length = wordAt(at)
            if (length > BigInteger.valueOf(args.size.toLong())) throw Refusal(LENGTH_RUN_REFUSAL)
            val size = length.toInt()
            val start = at + WORD_BYTES
            val padded = (size + WORD_BYTES - 1) / WORD_BYTES * WORD_BYTES
            if (start + padded > args.size) throw Refusal(LENGTH_RUN_REFUSAL)
            for (i in size until padded) {
                if (args[(start + i).toInt()] != 0.toByte()) throw Refusal(PADDING_REFUSAL)
            }
            val data = args.copyOfRange(start.toInt(), start.toInt() + size)
            if (type.canonical == STRING && utf8TextOf(data) == null) throw Refusal(TEXT_REFUSAL)
            return AbiValue.Dynamic(data.toHex())
        }

        private fun wordAt(at: Long): BigInteger = BigInteger(1, slice(at, WORD_BYTES))

        private fun slice(at: Long, size: Int): ByteArray {
            if (at < 0 || at + size > args.size) throw Refusal(SHORT_REFUSAL)
            return args.copyOfRange(at.toInt(), at.toInt() + size)
        }
    }

    /** The reason [word] is not a valid value of the atomic [type], or null when it is. */
    private fun wordRefusal(type: String, word: ByteArray): String? = when {
        type == "address" -> if (word.copyOfRange(0, WORD_BYTES - ADDRESS_BYTES).any { it != 0.toByte() }) PADDING_REFUSAL else null
        type == "bool" -> if (BigInteger(1, word).let { it == BigInteger.ZERO || it == BigInteger.ONE }) null else BOOL_REFUSAL
        type.startsWith("uint") -> if (BigInteger(1, word).bitLength() <= widthOf(type)) null else RANGE_REFUSAL
        type.startsWith("int") -> {
            val limit = BigInteger.ONE.shiftLeft(widthOf(type) - 1)
            val value = BigInteger(word)
            if (value >= limit.negate() && value < limit) null else RANGE_REFUSAL
        }
        else -> if (word.copyOfRange(widthOf(type), WORD_BYTES).any { it != 0.toByte() }) PADDING_REFUSAL else null
    }

    /** The canonical encoding of [values] under [types], or null when a value does not fit its type. */
    private fun encodeSequence(types: List<AbiType>, values: List<AbiValue>): ByteArray? {
        if (types.size != values.size) return null
        val headSize = types.fold(0L) { sum, type -> sum + (if (type.dynamic) WORD_BYTES.toLong() else type.headBytes) }
        var heads = ByteArray(0)
        var tails = ByteArray(0)
        for ((type, value) in types.zip(values)) {
            if (type.dynamic) {
                heads += wordOf(headSize + tails.size)
                tails += encodeValue(type, value) ?: return null
            } else {
                heads += encodeValue(type, value) ?: return null
            }
        }
        return heads + tails
    }

    private fun encodeValue(type: AbiType, value: AbiValue): ByteArray? = when (type) {
        is AtomicType -> (value as? AbiValue.Word)?.let { hexBytesOrNull(it.hex) }?.takeIf {
            it.size == WORD_BYTES && wordRefusal(type.canonical, it) == null
        }
        is DynamicBytes -> (value as? AbiValue.Dynamic)?.let { hexBytesOrNull(it.hex) }?.takeIf {
            type.canonical != STRING || utf8TextOf(it) != null
        }?.let { data ->
            wordOf(data.size.toLong()) + data + ByteArray((WORD_BYTES - data.size % WORD_BYTES) % WORD_BYTES)
        }
        is ArrayType -> (value as? AbiValue.Items)?.items?.let { items ->
            val elements = List(items.size) { type.element }
            when {
                type.length == null -> encodeSequence(elements, items)?.let { wordOf(items.size.toLong()) + it }
                items.size == type.length -> encodeSequence(elements, items)
                else -> null
            }
        }
        is TupleType -> (value as? AbiValue.Items)?.let { encodeSequence(type.components, it.items) }
    }

    private fun wordOf(value: Long): ByteArray = ByteArray(WORD_BYTES - Long.SIZE_BYTES) + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    /** The width of an integer type, or the size of a bytesN type, which is the number after its name. */
    private fun widthOf(type: String): Int = type.takeLastWhile { it in '0'..'9' }.toInt()

    private const val ADDRESS_BYTES = 20
    private const val MAX_OFFSET_BITS = 62
}

/**
 * The text of [bytes] when they are valid UTF-8 whose every code point is printable on one line (see [isSingleLinePrintable]), so no spoofing
 * mark can hide in a string the owner reads. An empty string is text. Null when the bytes are not text Lensly shows.
 */
fun utf8TextOf(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .takeIf(::isSingleLinePrintable)
} catch (e: CharacterCodingException) {
    null
}

/** The bytes [hex] spells, or null when it is not hex with a whole number of digits per byte. Either case is accepted. */
fun hexBytesOrNull(hex: String): ByteArray? {
    if (hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
