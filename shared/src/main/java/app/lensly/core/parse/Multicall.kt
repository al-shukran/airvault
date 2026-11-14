package app.lensly.core.parse

import app.lensly.core.registry.ContractCall
import app.lensly.core.wallet.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.web3j.utils.Numeric

/**
 * The calls a multicall carries, as the Parser Sandbox reads them: the uint256 deadline when the shape has one, then every call in
 * order. The app encodes the batch back to its exact bytes before it shows any of its calls.
 */
data class Multicall(
    val deadline: BigInteger?,
    val calls: List<InnerCall>,
)

/**
 * One call inside a multicall, with its exact [calldata]. [target], [allowFailure] and [value] are set only by a Multicall3 shape,
 * which names each call's contract and its flags. [call] is set when [ContractCall] decodes the calldata, and [nested] when the
 * calldata is a multicall of its own. A call that decodes neither has both null.
 */
data class InnerCall(
    val target: String?,
    val allowFailure: Boolean?,
    val value: BigInteger?,
    val calldata: ByteArray,
    val call: ContractCall?,
    val nested: Multicall?,
)

/**
 * The batch shapes Lensly reads: Uniswap's `multicall` in both of its forms, and the Multicall3 `aggregate` family. [selector] is the
 * Function Selector without 0x. A Multicall3 call is a tuple of its target, the flags its shape has, and its calldata.
 */
enum class MulticallShape(
    val selector: String,
    val function: String,
    val signature: String,
    val hasDeadline: Boolean = false,
    val hasTarget: Boolean = false,
    val hasAllowFailure: Boolean = false,
    val hasValue: Boolean = false,
) {
    BYTES_ARRAY("ac9650d8", "multicall", "multicall(bytes[])"),
    DEADLINE_BYTES_ARRAY("5ae401dc", "multicall", "multicall(uint256,bytes[])", hasDeadline = true),
    AGGREGATE("252dba42", "aggregate", "aggregate((address,bytes)[])", hasTarget = true),
    AGGREGATE3("82ad56cb", "aggregate3", "aggregate3((address,bool,bytes)[])", hasTarget = true, hasAllowFailure = true),
    AGGREGATE3_VALUE(
        "174dea71",
        "aggregate3Value",
        "aggregate3Value((address,bool,uint256,bytes)[])",
        hasTarget = true,
        hasAllowFailure = true,
        hasValue = true,
    ),
    ;

    /** The static words of a call's tuple, which come before the offset of its calldata: the target and each flag the shape has. */
    val tupleWords: Int get() = listOf(hasTarget, hasAllowFailure, hasValue).count { it }

    companion object {
        /** The shape whose Function Selector [calldata] starts with, or null. */
        fun of(calldata: ByteArray): MulticallShape? {
            if (calldata.size < SELECTOR_BYTES) return null
            val selector = calldata.copyOfRange(0, SELECTOR_BYTES).toHex()
            return entries.firstOrNull { it.selector == selector }
        }
    }
}

/** Thrown while a batch is read. The whole batch is then undecodable, and the whitelist decides it like any other call. */
private class MalformedBatch : RuntimeException()

/**
 * The calls of [calldata] when it is a multicall whose batch the Parser Sandbox reads. Null for other calldata, and for a batch that
 * is malformed or larger than [ParserLimits.MAX_NESTED_NODES] calls. Such a call is undecodable, so the whitelist decides it.
 */
internal fun multicallOf(calldata: ByteArray): Multicall? {
    val shape = MulticallShape.of(calldata) ?: return null
    val budget = DecodeBudget("multicall", ParserLimits.MAX_NESTED_NODES)
    return try {
        readBatch(shape, calldata.copyOfRange(SELECTOR_BYTES, calldata.size), budget)
    } catch (e: MalformedBatch) {
        null
    }
}

/**
 * The shape of [calldata] when [calldata] is exactly what [multicall] encodes to, and null otherwise. The app runs this check at every
 * level of a batch, so the calls it shows are the bytes that get signed.
 */
fun encodesAs(calldata: ByteArray, multicall: Multicall): MulticallShape? {
    val shape = MulticallShape.of(calldata) ?: return null
    val encoded = encodeMulticall(shape, multicall.deadline, multicall.calls) ?: return null
    return shape.takeIf { encoded.contentEquals(calldata) }
}

/** How many calls the batch holds, counting the calls of the batches nested inside it. */
fun Multicall.callCount(): Int = calls.sumOf { 1 + (it.nested?.callCount() ?: 0) }

/** The deepest level a call of the batch sits at. The calls of the batch are level 1, and each nested batch adds one. */
fun Multicall.deepestLevel(level: Int = 1): Int =
    calls.maxOfOrNull { call -> maxOf(level, call.nested?.deepestLevel(level + 1) ?: 0) } ?: 0

/**
 * The calldata that [calls] encode to as [shape], with [deadline] when the shape has one. It is null when a call does not fit the
 * shape: a field the shape lacks, a field it has but the call does not, an address that is not 20 bytes, or a value that is not a uint256.
 */
internal fun encodeMulticall(shape: MulticallShape, deadline: BigInteger?, calls: List<InnerCall>): ByteArray? {
    if (shape.hasDeadline != (deadline != null)) return null
    val elements = calls.map { elementOf(shape, it) ?: return null }
    val head = ByteArrayOutputStream()
    if (deadline != null) head.write(uintWord(deadline) ?: return null)
    // The calls follow the head: after the offset word, or after the deadline and the offset word.
    head.write(wordOf(if (shape.hasDeadline) 2 * WORD else WORD))
    return Numeric.hexStringToByteArray(shape.selector) + head.toByteArray() + arrayBytes(elements)
}

/**
 * The batch whose arguments are [args]. The head holds the deadline, if the shape has one, then the offset of the array of calls. The
 * array holds its length, then one offset per call, each counted from the start of the offsets.
 */
private fun readBatch(shape: MulticallShape, args: ByteArray, budget: DecodeBudget): Multicall {
    val words = Words(args)
    val deadline = if (shape.hasDeadline) words.uint(0) else null
    val array = words.index(if (shape.hasDeadline) WORD else 0)
    val count = words.index(array)
    val table = array + WORD
    val calls = List(count) {
        if (budget.charge() != null) throw MalformedBatch()
        // Each word of the table is the offset of one call, counted from the start of the table.
        val at = table + words.index(table + it * WORD)
        innerCall(shape, words, at, budget)
    }
    return Multicall(deadline, calls)
}

/** The call whose encoding starts at [at]. A Multicall3 call is a tuple: its target, its flags, then the offset of its calldata. */
private fun innerCall(shape: MulticallShape, words: Words, at: Int, budget: DecodeBudget): InnerCall {
    if (!shape.hasTarget) return decoded(null, null, null, words.data(at), budget)
    val target = words.address(at)
    var next = at + WORD
    val allowFailure = if (shape.hasAllowFailure) words.bool(next).also { next += WORD } else null
    val value = if (shape.hasValue) words.uint(next).also { next += WORD } else null
    // The offset of the calldata is counted from the start of the tuple.
    val calldata = words.data(at + words.index(next))
    return decoded(target, allowFailure, value, calldata, budget)
}

/** A call with its calldata read: a nested batch when the calldata is one, otherwise the call [ContractCall] decodes, if any. */
private fun decoded(
    target: String?,
    allowFailure: Boolean?,
    value: BigInteger?,
    calldata: ByteArray,
    budget: DecodeBudget,
): InnerCall {
    val nested = MulticallShape.of(calldata)?.let { readBatch(it, calldata.copyOfRange(SELECTOR_BYTES, calldata.size), budget) }
    val call = if (nested == null) ContractCall.decode(calldata) else null
    return InnerCall(target, allowFailure, value, calldata, call, nested)
}

/** The encoding of one call under [shape], or null when the call does not fit the shape. */
private fun elementOf(shape: MulticallShape, call: InnerCall): ByteArray? {
    if (!shape.hasTarget) {
        if (call.target != null || call.allowFailure != null || call.value != null) return null
        return bytesOf(call.calldata)
    }
    val head = ByteArrayOutputStream()
    head.write(addressWord(call.target ?: return null) ?: return null)
    if (shape.hasAllowFailure) {
        head.write(wordOf(if (call.allowFailure ?: return null) 1 else 0))
    } else if (call.allowFailure != null) {
        return null
    }
    if (shape.hasValue) {
        head.write(uintWord(call.value ?: return null) ?: return null)
    } else if (call.value != null) {
        return null
    }
    head.write(wordOf((shape.tupleWords + 1) * WORD))
    return head.toByteArray() + bytesOf(call.calldata)
}

/** A dynamic array: its length, the offset of each element counted from the table, then the elements. */
private fun arrayBytes(elements: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(wordOf(elements.size))
    var offset = elements.size * WORD
    for (element in elements) {
        out.write(wordOf(offset))
        offset += element.size
    }
    for (element in elements) out.write(element)
    return out.toByteArray()
}

/** A dynamic byte string: its length, then its bytes padded with zeros to a whole word. */
private fun bytesOf(data: ByteArray): ByteArray {
    val padded = (data.size + WORD - 1) / WORD * WORD
    return wordOf(data.size) + data + ByteArray(padded - data.size)
}

private fun addressWord(address: String): ByteArray? =
    if (ADDRESS.matches(address)) ByteArray(WORD - ADDRESS_BYTES) + Numeric.hexStringToByteArray(address) else null

private fun uintWord(value: BigInteger): ByteArray? {
    if (value.signum() < 0 || value.bitLength() > WORD * Byte.SIZE_BITS) return null
    val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(WORD - raw.size) + raw
}

private fun wordOf(value: Int): ByteArray = uintWord(BigInteger.valueOf(value.toLong())) ?: error("a length fits in a word")

private const val WORD = 32
private const val SELECTOR_BYTES = 4
private const val ADDRESS_BYTES = 20
private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** The 32-byte words of a batch's arguments. Every read is checked against the bytes there are, so a bad offset or length is malformed. */
private class Words(private val bytes: ByteArray) {
    fun uint(at: Int): BigInteger = BigInteger(1, slice(at, WORD))

    /** A word read as a position or a count in [bytes]. It cannot exceed the size of the arguments. */
    fun index(at: Int): Int {
        val value = uint(at)
        if (value > BigInteger.valueOf(bytes.size.toLong())) throw MalformedBatch()
        return value.toInt()
    }

    fun address(at: Int): String {
        val word = slice(at, WORD)
        if (word.copyOfRange(0, WORD - ADDRESS_BYTES).any { it != 0.toByte() }) throw MalformedBatch()
        return "0x" + word.copyOfRange(WORD - ADDRESS_BYTES, WORD).toHex()
    }

    fun bool(at: Int): Boolean = when (uint(at)) {
        BigInteger.ZERO -> false
        BigInteger.ONE -> true
        else -> throw MalformedBatch()
    }

    /** The bytes that the length word at [at] announces. They follow that word. */
    fun data(at: Int): ByteArray = slice(at + WORD, index(at))

    private fun slice(at: Int, size: Int): ByteArray {
        if (at < 0 || at > bytes.size - size) throw MalformedBatch()
        return bytes.copyOfRange(at, at + size)
    }
}
