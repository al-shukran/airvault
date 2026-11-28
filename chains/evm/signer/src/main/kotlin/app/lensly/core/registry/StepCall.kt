package app.lensly.core.registry

import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.hexBytesOrNull
import app.airvault.chains.evm.signer.MULTICALL3_ADDRESS
import app.airvault.core.model.AddressGroups
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

private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
private val MAX_INSTANT_SECONDS: BigInteger = BigInteger.valueOf(Instant.MAX.epochSecond)

/** The wrapped native coin on Ethereum mainnet, the only network the Uniswap routers are approved on. */
internal const val WETH_MAINNET = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"

/** [address] for a sentence: its EIP-55 checksum as the first two and last two groups of 4, as the summary shows it. The full value is under Details. */
internal fun shortAddress(address: String): String = AddressGroups.ends(Keys.toChecksumAddress(address))

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
 * network. [caller] is the address the contract sees as its message sender: the signer, or the batch contract that calls it inside a
 * batch. A router flag for the message sender means [caller], so inside a batch it is not the signer. Addresses are lowercase. The names and amounts a step shows come from [registry], so a token it does not know is shown as its
 * short address and raw base units, and never as a guessed symbol or decimals. [held] is the tokens a router step has left in the router and
 * no later step has taken out, so the steps of one call share it. A token's value is the amount known to be there (a wrap of a stated
 * amount), which a later spend of that amount takes out, or null when the amount is not known (a swap output), which only a step that
 * takes everything takes out.
 */
internal class StepCall(
    val registry: Registry,
    val network: NetworkEntry,
    val contract: String,
    val from: String,
    val sent: BigInteger,
    private val params: List<DecodedParam>,
    val caller: String = from,
    private val held: MutableMap<String, BigInteger?> = LinkedHashMap(),
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

    /** "you" for the signing address, the name of the batch that calls the contract ([callerName]), and the short address of any other. */
    fun who(address: String): String = when (address) {
        from -> "you"
        caller -> callerName
        else -> shortAddress(address)
    }

    /**
     * What the contract's message sender is in words: "you" for the signer, "Multicall3" for the real Multicall3 contract, and the short
     * address of any other batch contract, which must never wear the name of a well-known one.
     */
    val callerName: String
        get() = when {
            caller == from -> "you"
            caller == MULTICALL3_ADDRESS.lowercase() -> "Multicall3"
            else -> shortAddress(caller)
        }

    /** [callerName] as a possessive before a noun: "your" for the signer, and "Multicall3's" or "<short address>'s" for the batch. */
    val callerOwn: String get() = if (caller == from) "your" else "${callerName}'s"

    /**
     * The address a step pays when it names [recipient]: the flag 0x…01 is [caller], the flag 0x…02 and the router's own address are both
     * [ADDRESS_THIS] because either leaves the tokens in the router, and any other value is itself.
     */
    private fun paid(recipient: String): String = when (recipient) {
        MSG_SENDER -> caller
        contract -> ADDRESS_THIS
        else -> recipient
    }

    /** How a step names [recipient]: "you", "the router" for the flag 0x…02, the batch ([callerName]), or the short address of another. */
    fun recipientName(recipient: String): String = paid(recipient).let { if (it == ADDRESS_THIS) "the router" else who(it) }

    /** What a swap says about where its output goes, or null when it goes to the signer: "kept in the router", or "sent to <name>". */
    fun destination(recipient: String): String? = when (val address = paid(recipient)) {
        from -> null
        ADDRESS_THIS -> "kept in the router"
        else -> "sent to ${recipientName(address)}"
    }

    /** Whether a step has left a token in the router that no later step has taken out, where anyone can take it. */
    val leavesInRouter: Boolean get() = held.isNotEmpty()

    /** A step that pays an unknown amount of [token] to [recipient]: the flag 0x…02 or the router's own address keeps it in the router. */
    fun keepInRouter(token: String, recipient: String) {
        if (paid(recipient) == ADDRESS_THIS) held[token] = null
    }

    /** A step that pays [amount] of [token] to [recipient], such as a wrap of a stated amount: it adds to any amount of [token] already known there. */
    fun keepInRouter(token: String, amount: BigInteger, recipient: String) {
        if (paid(recipient) != ADDRESS_THIS || amount.signum() == 0) return
        val known = held[token]
        if (!held.containsKey(token)) held[token] = amount else if (known != null) held[token] = known + amount
    }

    /** A step that takes all [token], or only [amount] of it, out of the router to [recipient]: the router itself leaves it there. */
    fun takeFromRouter(token: String, recipient: String, amount: BigInteger? = null) {
        if (paid(recipient) == ADDRESS_THIS) return
        if (amount == null) emptyRouterOf(token) else spendFromRouter(token, amount)
    }

    /** A step that spends or unwraps all [token] the router holds, so none is left. */
    fun emptyRouterOf(token: String) {
        held -= token
    }

    /** A step that spends [amount] of [token] from the router: it takes out the amount a wrap left there, and no more than that. */
    fun spendFromRouter(token: String, amount: BigInteger) {
        val known = held[token] ?: return
        if (amount >= known) held -= token else held[token] = known - amount
    }

    /** The same call with its steps' leftover check made: [CallSteps.leavesInRouter] says what the router still holds at the end. */
    fun checked(steps: CallSteps): CallSteps = steps.copy(leavesInRouter = leavesInRouter)

    /** A call the same contract makes with [params], sharing what the steps so far left in the router. */
    fun inner(params: List<DecodedParam>): StepCall = StepCall(registry, network, contract, from, BigInteger.ZERO, params, caller, held)

    /**
     * The steps of one line that pays [recipient], as [what] reads in "This <what> <address>, not you". A recipient that is not the signer
     * and not the router earns the warning and its full address as a row, so it gets the grouping, its names and the lookalike check.
     */
    fun paying(line: String, what: String, recipient: String): CallSteps =
        if (paid(recipient) == ADDRESS_THIS) CallSteps(listOf(line), emptyList()) else payingAddress(line, what, paid(recipient))

    /**
     * The steps [lines] of a call that pays or credits other accounts. Each of [others] is an action and the account it reaches, as
     * [notYou] reads them, and an account that is not the signer earns its warning after [warnings]. Inside a batch those accounts are also
     * address rows, which makes the review warn of them as a DANGER; outside a batch the steps are the lines and warnings alone.
     */
    fun steps(lines: List<String>, vararg others: Pair<String, String>, warnings: List<String> = emptyList()): CallSteps {
        val paid = others.filter { it.second != from }
        val rows = if (caller == from) emptyList() else paid.map { it.second }.distinct()
        return CallSteps(lines, warnings + paid.flatMap { (what, address) -> notYou(from, what, address) }, rows)
    }

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
