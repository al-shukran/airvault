package app.lensly.core.registry

import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.hexBytesOrNull
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.web3j.crypto.Keys

/** The address a call names for the native coin, which a router uses as ETH. */
internal const val NATIVE_COIN = "0x0000000000000000000000000000000000000000"

/** The router flag for the message sender, ActionConstants.MSG_SENDER: whoever calls the router, which inside a batch is the batch contract. */
internal const val MSG_SENDER = "0x0000000000000000000000000000000000000001"

/** The router flag for the router itself, ActionConstants.ADDRESS_THIS. */
internal const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"

private const val ADDRESS_BYTES = 20
private const val ADDRESS_HEX = 40
private const val SHORT_HEAD = 6
private const val SHORT_TAIL = 4

private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
private val MAX_INSTANT_SECONDS: BigInteger = BigInteger.valueOf(Instant.MAX.epochSecond)

/** [address] for a sentence: the first six and last four characters of its EIP-55 checksum. The full value is under Details. */
internal fun shortAddress(address: String): String {
    val checksum = Keys.toChecksumAddress(address)
    return checksum.take(SHORT_HEAD) + "…" + checksum.takeLast(SHORT_TAIL)
}

/**
 * The warning of a step that pays or credits [address], or an empty list when [address] is the signer. [what] is the action that reaches
 * it, as the text "This <what> <address>, not you" reads: "withdraws to", "supplies for" or "repays the debt of".
 */
internal fun notYou(from: String, what: String, address: String): List<String> =
    if (address == from) emptyList() else listOf("This $what ${shortAddress(address)}, not you")

/**
 * [seconds] since the epoch, as a UTC date and time such as 2026-11-29 00:27 UTC, or the raw number when it is outside the dates a clock holds.
 * It is not truncated to a Long first, which would show a different date.
 */
internal fun untilText(seconds: BigInteger): String =
    if (seconds.signum() < 0 || seconds > MAX_INSTANT_SECONDS) "$seconds (not a date)"
    else UNTIL.format(Instant.ofEpochSecond(seconds.toLong()))

/** [decimal] with a comma between each group of three digits of its whole part, such as 1,234,567.5. */
internal fun groupDigits(decimal: String): String {
    val whole = decimal.substringBefore('.').reversed().chunked(3).joinToString(",").reversed()
    val fraction = decimal.substringAfter('.', "")
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}

/** [bips] basis points as a percentage, such as 0.5% for 50. */
internal fun percentOf(bips: BigInteger): String = Amounts.format(bips, 2) + "%"

/** A word read as an unsigned number. */
internal fun AbiValue.word(): BigInteger = BigInteger((this as AbiValue.Word).hex, 16)

/** A word read as an address, in lowercase 0x form. */
internal fun AbiValue.address(): String = "0x" + (this as AbiValue.Word).hex.takeLast(ADDRESS_HEX)

/** The fields of a tuple, or the items of an array. */
internal fun AbiValue.items(): List<AbiValue> = (this as AbiValue.Items).items

/** The bytes of a bytes value. */
internal fun AbiValue.bytes(): ByteArray = checkNotNull(hexBytesOrNull((this as AbiValue.Dynamic).hex)) { "not bytes" }

/** The bytes of a Uniswap v3 path or the like, read as the address at [at]. */
internal fun ByteArray.addressAt(at: Int): String = "0x" + copyOfRange(at, at + ADDRESS_BYTES).toHex()

/**
 * One approved call a step describes: its contract, the signing address, the native amount it sends, its decoded arguments, and its
 * network. [caller] is the address the contract sees as its message sender: the signer, or the Multicall3 contract that calls it inside a
 * batch. A router flag for the message sender means [caller], so inside a batch it is not the signer. Addresses are lowercase. The names and amounts a step shows come from [registry], so a token it does not know is shown as its
 * short address and raw base units, and never as a guessed symbol or decimals.
 */
internal class StepCall(
    val registry: Registry,
    val network: NetworkEntry,
    val contract: String,
    val from: String,
    val sent: BigInteger,
    private val params: List<DecodedParam>,
    val caller: String = from,
) {
    val chainId: Long get() = network.chainId

    /** The wrapped native coin's symbol, such as WETH on Ethereum: W and the native symbol. */
    val wrappedSymbol: String get() = "W${network.nativeSymbol}"

    fun uint(index: Int): BigInteger = params[index].value.word()

    fun address(index: Int): String = params[index].value.address()

    fun bool(index: Int): Boolean = params[index].value.word() == BigInteger.ONE

    fun bytes(index: Int): ByteArray = params[index].value.bytes()

    fun items(index: Int): List<AbiValue> = params[index].value.items()

    /** The name of [token]: the native symbol for the zero address, the registered symbol, or the short address when unregistered. */
    fun symbolOf(token: String): String = if (token == NATIVE_COIN) {
        network.nativeSymbol
    } else {
        registry.token(chainId, token)?.symbol ?: shortAddress(token)
    }

    /** [raw] as a quantity of [token] in its decimals. A token whose decimals are unknown shows the raw integer in base units. */
    fun quantity(raw: BigInteger, token: String): String {
        val decimals = decimalsOf(token) ?: return "$raw base units"
        return groupDigits(Amounts.format(raw, decimals))
    }

    /** [raw] of [token] with its name, such as 1,234.5 USDC, or the raw integer and short address of an unregistered token. */
    fun amount(raw: BigInteger, token: String): String = if (decimalsOf(token) == null) {
        "$raw base units of ${shortAddress(token)}"
    } else {
        "${quantity(raw, token)} ${symbolOf(token)}"
    }

    /** [raw] of the native coin, such as 0.5 ETH. */
    fun nativeAmount(raw: BigInteger): String = "${groupDigits(Amounts.format(raw, network.nativeDecimals))} ${network.nativeSymbol}"

    /** [raw] of the wrapped native coin, which has the native coin's decimals, such as 0.5 WETH. */
    fun wrappedAmount(raw: BigInteger): String = "${groupDigits(Amounts.format(raw, network.nativeDecimals))} $wrappedSymbol"

    /** "you" for the signing address, and the short address of any other. */
    fun who(address: String): String = if (address == from) "you" else shortAddress(address)

    /** What the router's message-sender flag means in words: "you" for the signer, and "Multicall3" for the batch that calls it. */
    val callerName: String get() = if (caller == from) "you" else "Multicall3"

    /** The address a step pays when it names [recipient]: the flag 0x…01 is [caller], and any other value is itself. */
    private fun paid(recipient: String): String = if (recipient == MSG_SENDER) caller else recipient

    /** How a step names [recipient]: "you", "the router" for the flag 0x…02, "Multicall3", or the short address of another. */
    fun recipientName(recipient: String): String = when (val address = paid(recipient)) {
        ADDRESS_THIS -> "the router"
        from -> "you"
        caller -> "Multicall3"
        else -> shortAddress(address)
    }

    /** What a swap says about where its output goes, or null when it goes to the signer: "kept in the router", or "sent to <name>". */
    fun destination(recipient: String): String? = when (val address = paid(recipient)) {
        from -> null
        ADDRESS_THIS -> "kept in the router"
        else -> "sent to ${recipientName(address)}"
    }

    /**
     * The steps of one line that pays [recipient], as [what] reads in "This <what> <address>, not you". A recipient that is not the signer
     * and not the router earns the warning and its full address as a row, so it gets the grouping, its names and the lookalike check.
     */
    fun paying(line: String, what: String, recipient: String): CallSteps =
        if (recipient == ADDRESS_THIS) CallSteps(listOf(line), emptyList()) else payingAddress(line, what, paid(recipient))

    /** [paying] for an [address] the router takes as it is, with no flag mapped. */
    fun payingAddress(line: String, what: String, address: String): CallSteps {
        val other = address != from
        return CallSteps(listOf(line), if (other) notYou(from, what, address) else emptyList(), if (other) listOf(address) else emptyList())
    }

    private fun decimalsOf(token: String): Int? = if (token == NATIVE_COIN) {
        network.nativeDecimals
    } else {
        registry.token(chainId, token)?.decimals
    }
}
