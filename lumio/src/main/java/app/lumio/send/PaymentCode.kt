package app.lumio.send

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.BtcSendInput
import app.lumio.evm.EvmChain
import app.lumio.evm.SendInput
import java.math.BigDecimal
import java.math.BigInteger

/** What a scanned code asks for: the address it pays, and the amount in the chain's units when it names one. */
class ScannedPayment(val address: String, val amount: String?)

/**
 * Reads a code that the camera sees on an Ethereum send: a plain address, or an EIP-681 `ethereum:` request with an optional chain and an
 * optional value in wei. A call to a contract is refused. A code for another chain is refused, so the owner chooses that chain first.
 * Throws IllegalArgumentException with the words the scan shows.
 */
object EvmPaymentCode {
    private const val SCHEME = "ethereum:"
    private val WEI = Regex("\\d+(\\.\\d+)?(e\\d+)?", RegexOption.IGNORE_CASE)

    fun read(text: String, chain: EvmChain): ScannedPayment {
        val code = text.trim()
        return when {
            code.startsWith(SCHEME, ignoreCase = true) -> request(code.substring(SCHEME.length), chain)
            code.startsWith("bitcoin:", ignoreCase = true) -> throw IllegalArgumentException("This is a Bitcoin payment code. Use Send Bitcoin for it.")
            code.startsWith("0x", ignoreCase = true) -> ScannedPayment(SendInput.recipient(code), null)
            else -> throw IllegalArgumentException("This code is not an Ethereum address or payment request.")
        }
    }

    /** `address[@chainId][?value=wei&...]`. Other parameters are ignored, as EIP-681 allows. */
    private fun request(body: String, chain: EvmChain): ScannedPayment {
        val target = body.substringBefore('?')
        val query = body.substringAfter('?', missingDelimiterValue = "")
        if ('/' in target) throw IllegalArgumentException("This code calls a contract. Lumio sends only transfers.")
        val address = target.substringBefore('@')
        if ('@' in target) {
            val id = target.substringAfter('@').toLongOrNull()
                ?: throw IllegalArgumentException("This code names a chain that is not a number.")
            if (id != chain.chainId) {
                throw IllegalArgumentException("This code is for chain $id, but ${chain.name} is selected. Choose that network to scan it.")
            }
        }
        val value = query.split('&').firstOrNull { it.startsWith("value=", ignoreCase = true) }?.substringAfter('=')
        return ScannedPayment(SendInput.recipient(address), value?.let { amountOf(it, chain) })
    }

    /** The value of an EIP-681 request, in wei, as the amount of [chain]. A value of zero names no amount. */
    private fun amountOf(text: String, chain: EvmChain): String? {
        if (!WEI.matches(text)) throw IllegalArgumentException("The value in this code is not an amount.")
        val wei = try {
            BigDecimal(text).toBigIntegerExact()
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
