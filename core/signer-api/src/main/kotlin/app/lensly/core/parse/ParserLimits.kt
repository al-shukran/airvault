package app.lensly.core.parse

/**
 * The limits of the Parser Sandbox. Every parser that reads an online request stays inside these. A request that breaks
 * one is refused with a reason that names the limit, and none of them may crash the app.
 *
 * The core sets the limits, and a request parser receives them as a value. An app may lower them with [lowerTo], never
 * raise them. `ParserLimits()` holds the core's own limits. The constants in the companion object are the same values, for
 * the parsers that read them directly.
 */
data class ParserLimits(
    /** The largest data to sign one request may carry, in bytes. */
    val maxSignDataBytes: Int = MAX_SIGN_DATA_BYTES,
    /** The most bytes of one request's CBOR payload, as its UR carries it. It also bounds the body an app request crosses with. */
    val maxPayloadBytes: Int = MAX_APP_PAYLOAD_BYTES,
) {
    /** These limits, each one lowered to [app]'s value where that is lower. An app cannot raise a limit this way. */
    fun lowerTo(app: ParserLimits): ParserLimits = ParserLimits(
        maxSignDataBytes = minOf(maxSignDataBytes, app.maxSignDataBytes),
        maxPayloadBytes = minOf(maxPayloadBytes, app.maxPayloadBytes),
    )

    companion object {
        /** The most parts (animated frames) one UR may declare. A request of the largest size fits in parts of 68 bytes. */
        const val MAX_UR_PARTS = 1024

        /** The longest scanned UR part, in characters. A version-40 QR code holds at most 4,296 alphanumeric characters. */
        const val MAX_UR_PART_CHARS = 4096

        /** The largest data to sign, in bytes. It also caps every RLP payload, which carries that data. */
        const val MAX_SIGN_DATA_BYTES = 64 * 1024

        /**
         * The most bytes of one request's CBOR payload. The data to sign is the bulk of it, and the other fields are small, so the
         * whole payload has the same fixed headroom as a sol-sign-request.
         */
        const val MAX_APP_PAYLOAD_BYTES = MAX_SIGN_DATA_BYTES + 4096

        /** The deepest nesting of a CBOR payload. */
        const val MAX_CBOR_DEPTH = 8

        /** The most items one CBOR payload may hold. A complete eth-sign-request holds about sixty. */
        const val MAX_CBOR_ITEMS = 256

        /**
         * The most items one AirVault pairing may hold. A pairing carries every account of a wallet, so it is larger than a request: 100
         * Ethereum accounts and 400 Bitcoin keys, the most a wallet can list, take about 8,000 items. The bound still stops a crafted code.
         */
        const val MAX_PAIRING_ITEMS = 16 * 1024

        /** The deepest nesting of an RLP transaction. */
        const val MAX_RLP_DEPTH = 8

        /** The most items one RLP transaction may hold. Its access list is the only part that grows with the transaction. */
        const val MAX_RLP_ITEMS = 4096

        /**
         * The largest calldata a transaction may carry, in bytes. A token transfer or approval needs 68 bytes, and a router or
         * multicall batch often runs 1.5 to 6 KB. A transaction above this size is refused.
         */
        const val MAX_CALLDATA_BYTES = 16 * 1024

        /** The deepest level a call of a multicall may sit at. The calls of the outer multicall are level 1, the calls inside them level 2. */
        const val MAX_NESTED_DEPTH = 3

        /** The most calls one request may show, the calls of nested multicalls included. */
        const val MAX_NESTED_CALLS = 32

        /** The most calls one multicall may read in the Parser Sandbox, nested ones included. It bounds a batch whose offsets overlap. */
        const val MAX_NESTED_NODES = 256

        /** The deepest nesting of an ABI type: arrays and tuples inside one another, as a whitelist entry may declare them. */
        const val MAX_ABI_DEPTH = 8

        /** The longest fixed-length array an ABI type may declare. A calldata of [MAX_CALLDATA_BYTES] holds that many words. */
        const val MAX_ABI_ARRAY_LENGTH = MAX_CALLDATA_BYTES / 32

        /** The most values one whitelisted call may decode to: every argument, array item and tuple field counted. */
        const val MAX_ABI_VALUES = 1024

        /** The deepest nesting of an EIP-712 document, counting its objects and arrays. */
        const val MAX_TYPED_DEPTH = 8
        /** The most JSON values one EIP-712 document may hold. */
        const val MAX_TYPED_VALUES = 4096
        /** The most struct types one EIP-712 document may declare. */
        const val MAX_TYPED_TYPES = 64
        /** The most members one struct type of an EIP-712 document may declare. */
        const val MAX_TYPED_MEMBERS = 64
        /** The time one decode may take, in nanoseconds. */
        const val DECODE_BUDGET_NANOS = 1_000_000_000L
    }
}

/**
 * The work one decode may do: at most [maxItems] items, and at most [maxNanos] of time. A parser calls [charge] once
 * for each item it reads, and refuses the input when the call returns a reason. [nanoTime] is injected so that a test
 * can run past the time budget without waiting for it.
 */
class DecodeBudget(
    /** Names the parser in the refusal reason, for example "CBOR". */
    private val name: String,
    private val maxItems: Int,
    private val maxNanos: Long = ParserLimits.DECODE_BUDGET_NANOS,
    private val nanoTime: () -> Long = DecodeClock.nanoTime,
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

/**
 * The clock that a [DecodeBudget] reads when its caller gives none. The budget is wall-clock time, so a loaded runner can spend it
 * on a decode that is fine. A test whose subject is not the budget stops this clock for its run, and gives it back afterwards.
 */
object DecodeClock {
    @Volatile
    var nanoTime: () -> Long = System::nanoTime
}
