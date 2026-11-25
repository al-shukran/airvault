package app.lumio.send

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcSendInput
import app.lumio.evm.EvmChain
import app.lumio.evm.SendInput
import java.math.BigDecimal
import java.math.BigInteger

/**
 * What a scanned code asks for: the address it pays, and the amount in the chain's units when it names one. [chainId] is the other network that an
 * Ethereum code names, when the send may move to it, else null.
 */
class ScannedPayment(val address: String, val amount: String?, val chainId: Long? = null) {
    /**
     * The amount the form holds after this scan. A scan that moves the send to another network drops the typed amount, which was for the old network's
     * asset, and fills the code's own, which is in the new network's native coin. A scan that stays fills the code's only into a blank amount for the native coin.
     */
    fun amountAfter(typed: String, native: Boolean): String {
        val kept = if (chainId != null) "" else typed
        return if (amount != null && kept.isBlank() && (chainId != null || native)) amount else kept
    }
}

/**
 * Reads a code that the camera sees on an Ethereum send: a plain address, or an EIP-681 `ethereum:` request with an optional chain and an
 * optional value in wei. A call to a contract is refused. A code for a chain other than [read]'s `chain` is refused, unless it is one of `others`, which
 * the send may move to. The refusal tells a chain in `otherSide`, which is on but on the other side of the mainnet/testnet switch, from one that is off. Throws IllegalArgumentException with the words the scan shows.
 */
object EvmPaymentCode {
    private const val SCHEME = "ethereum:"
    private val WEI = Regex("\\d+(\\.\\d+)?(e\\d+)?", RegexOption.IGNORE_CASE)

    /** The most decimal digits of a uint256, the largest amount an Ethereum transfer holds: a value with more is refused. */
    private const val MAX_WEI_DIGITS = 78
    private const val TOO_LARGE = "The value in this code is too large for an amount."

    fun read(text: String, chain: EvmChain, others: List<EvmChain> = emptyList(), otherSide: List<EvmChain> = emptyList()): ScannedPayment {
        val code = text.trim()
        return when {
            code.startsWith(SCHEME, ignoreCase = true) -> request(code.substring(SCHEME.length), chain, others, otherSide)
            code.startsWith("bitcoin:", ignoreCase = true) -> throw IllegalArgumentException("This is a Bitcoin payment code. Use Send Bitcoin for it.")
            code.startsWith("0x", ignoreCase = true) -> ScannedPayment(SendInput.recipient(code), null)
            else -> throw IllegalArgumentException("This code is not an Ethereum address or payment request.")
        }
    }

    /** `address[@chainId][?value=wei&...]`. Other parameters are ignored, as EIP-681 allows. */
    private fun request(body: String, selected: EvmChain, others: List<EvmChain>, otherSide: List<EvmChain>): ScannedPayment {
        val target = body.substringBefore('?')
        val query = body.substringAfter('?', missingDelimiterValue = "")
        if ('/' in target) throw IllegalArgumentException("This code calls a contract. Lumio sends only transfers.")
        val address = target.substringBefore('@')
        var chain = selected
        if ('@' in target) {
            val id = target.substringAfter('@').toLongOrNull()
                ?: throw IllegalArgumentException("This code names a chain that is not a number.")
            if (id != selected.chainId) {
                chain = others.firstOrNull { it.chainId == id }
                    ?: throw IllegalArgumentException("This code is for chain $id, but ${selected.name} is selected. ${refusalAdvice(id, others, otherSide)}")
            }
        }
        val value = query.split('&').firstOrNull { it.startsWith("value=", ignoreCase = true) }?.substringAfter('=')
        return ScannedPayment(SendInput.recipient(address), value?.let { amountOf(it, chain) }, chain.chainId.takeIf { it != selected.chainId })
    }

    private fun refusalAdvice(id: Long, others: List<EvmChain>, otherSide: List<EvmChain>): String {
        val onTheOtherSide = otherSide.firstOrNull { it.chainId == id }
        return when {
            onTheOtherSide != null -> "Switch to ${if (onTheOtherSide.testnet) "Testnet" else "Mainnet"} to scan it."
            others.isEmpty() -> "Choose that network to scan it."
            else -> "Turn that network on to scan it."
        }
    }

    /** The value of an EIP-681 request, in wei, as the amount of [chain]. A value of zero names no amount. */
    private fun amountOf(text: String, chain: EvmChain): String? {
        if (!WEI.matches(text)) throw IllegalArgumentException("The value in this code is not an amount.")
        val value = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException(TOO_LARGE, e)
        }
        // The digits are counted before the value is expanded: an exponent of millions would otherwise take seconds to expand.
        if (value.precision() - value.scale() > MAX_WEI_DIGITS) throw IllegalArgumentException(TOO_LARGE)
        val wei = try {
            value.toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("The value in this code is not a whole number of wei.", e)
        }
        return wei.takeIf { it.signum() > 0 }?.let { format(it, chain.decimals) }
    }

    private fun format(wei: BigInteger, decimals: Int): String = BigDecimal(wei).movePointLeft(decimals).stripTrailingZeros().toPlainString()
}

/**
 * Reads a code that the camera sees on a Bitcoin send: a plain address, or a BIP21 `bitcoin:` request with an optional amount in BTC. An
 * address of the other network is refused with the network it is for. Throws IllegalArgumentException with the words the scan shows.
 */
object BtcPaymentCode {
    private const val SCHEME = "bitcoin:"

    fun read(text: String, network: BtcNetwork): ScannedPayment {
        val code = text.trim()
        if (!code.startsWith(SCHEME, ignoreCase = true)) return ScannedPayment(addressOf(code, network), null)
        val body = code.substring(SCHEME.length)
        val query = body.substringAfter('?', missingDelimiterValue = "")
        val amount = query.split('&').firstOrNull { it.startsWith("amount=", ignoreCase = true) }?.substringAfter('=')
        return ScannedPayment(
            address = addressOf(body.substringBefore('?'), network),
            amount = amount?.also { BtcSendInput.amountSats(it) }?.trim(),
        )
    }

    /** [text] as an address of [network]. When it is an address of the other network, the words say which network it is for. */
    private fun addressOf(text: String, network: BtcNetwork): String {
        runCatching { BtcSendInput.recipient(text, network) }.onSuccess { return it }
        val other = BtcNetwork.entries.firstOrNull { it != network && runCatching { BtcSendInput.recipient(text, it) }.isSuccess }
        throw IllegalArgumentException(
            if (other != null) {
                "This address is for ${name(other)}, but ${name(network)} is selected. Choose that network to scan it."
            } else {
                "This code is not a Bitcoin address or payment request."
            },
        )
    }

    private fun name(network: BtcNetwork): String = if (network == BtcNetwork.MAINNET) "mainnet" else "testnet4"
}
