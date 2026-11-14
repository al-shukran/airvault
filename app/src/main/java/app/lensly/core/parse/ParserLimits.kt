package app.lensly.core.parse

/**
 * The limits of the Parser Sandbox. Every parser that reads an online request stays inside these. A request that breaks
 * one is refused with a reason that names the limit, and none of them may crash the app.
 */
internal object ParserLimits {
    /** The most parts (animated frames) one UR may declare. A request of the largest size fits in parts of 68 bytes. */
    const val MAX_UR_PARTS = 1024

    /** The longest scanned UR part, in characters. A version-40 QR code holds at most 4,296 alphanumeric characters. */
    const val MAX_UR_PART_CHARS = 4096

    /** The largest data to sign, in bytes. It also caps every RLP payload, which carries that data. */
    const val MAX_SIGN_DATA_BYTES = 64 * 1024

    /** The deepest nesting of a CBOR payload. */
    const val MAX_CBOR_DEPTH = 8

    /** The most items one CBOR payload may hold. A complete eth-sign-request holds about sixty. */
    const val MAX_CBOR_ITEMS = 256

    /** The deepest nesting of an RLP transaction. */
    const val MAX_RLP_DEPTH = 8

    /** The most items one RLP transaction may hold. Its access list is the only part that grows with the transaction. */
    const val MAX_RLP_ITEMS = 4096

    /** The largest calldata a transaction may carry. A token transfer or approval needs 68 bytes. */
    const val MAX_CALLDATA_BYTES = 1024

    /** The deepest level a call of a multicall may sit at. The calls of the outer multicall are level 1, the calls inside them level 2. */
    const val MAX_NESTED_DEPTH = 3

    /** The most calls one request may show, the calls of nested multicalls included. */
    const val MAX_NESTED_CALLS = 32

    /** The most calls one multicall may read in the Parser Sandbox, nested ones included. It bounds a batch whose offsets overlap. */
    const val MAX_NESTED_NODES = 256

    /** The time one decode may take, in nanoseconds. */
    const val DECODE_BUDGET_NANOS = 1_000_000_000L
}

/**
 * The work one decode may do: at most [maxItems] items, and at most [maxNanos] of time. A parser calls [charge] once
 * for each item it reads, and refuses the input when the call returns a reason. [nanoTime] is injected so that a test
 * can run past the time budget without waiting for it.
 */
internal class DecodeBudget(
    /** Names the parser in the refusal reason, for example "CBOR". */
    private val name: String,
    private val maxItems: Int,
    private val maxNanos: Long = ParserLimits.DECODE_BUDGET_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private var items = 0

    /** Counts one item. Returns the reason to refuse the input, or null while the budget holds. */
    fun charge(): String? {
        items++
        if (items > maxItems) return "$name has more than $maxItems items"
        if (nanoTime() - started > maxNanos) return "$name decoding ran past its time budget"
        return null
    }
}
