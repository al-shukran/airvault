package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.web3j.crypto.Keys

/** The address a call names for the native coin, which a router uses as ETH. */
internal const val NATIVE_COIN = "0x0000000000000000000000000000000000000000"

private const val ADDRESS_BYTES = 20
private const val ADDRESS_HEX = 40
private const val SHORT_HEAD = 6
private const val SHORT_TAIL = 4

private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** [address] for a sentence: the first six and last four characters of its EIP-55 checksum. The full value is under Details. */
internal fun shortAddress(address: String): String {
    val checksum = Keys.toChecksumAddress(address)
    return checksum.take(SHORT_HEAD) + "…" + checksum.takeLast(SHORT_TAIL)
}

/** [seconds] since the epoch, as a UTC date and time such as 2026-11-29 00:27 UTC. */
internal fun untilText(seconds: BigInteger): String = UNTIL.format(Instant.ofEpochSecond(seconds.toLong()))

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
 * network. Addresses are lowercase. The names and amounts a step shows come from [registry], so a token it does not know is shown as its
 * short address and raw base units, and never as a guessed symbol or decimals.
 */
internal class StepCall(
    val registry: Registry,
    val network: NetworkEntry,
    val contract: String,
    val from: String,
    val sent: BigInteger,
    private val params: List<DecodedParam>,
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

    private fun decimalsOf(token: String): Int? = if (token == NATIVE_COIN) {
        network.nativeDecimals
    } else {
        registry.token(chainId, token)?.decimals
    }
}
