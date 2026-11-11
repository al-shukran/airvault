package app.lensly.ui.sign

import app.lensly.core.registry.Amounts
import app.lensly.core.ur.PathStep
import java.math.BigInteger

private const val ASSUMED_DECIMALS = 18

/** The text of a request field the wallet left out. */
const val NOT_STATED = "Not stated"

/**
 * The note under an unknown chain's native amount: [wei] read with 18 decimals, labelled as an assumption. The
 * assumption is stated because the coin's decimals are not in the registry.
 */
internal fun assumedAmountNote(wei: BigInteger): String =
    "≈ ${assumedEighteen(wei)} if 18 decimals (assumption, not verified)"

/**
 * The fee note for an unknown chain: the maximum fee under the 18-decimal assumption, and a warning that the total
 * may be higher, because the fee model is unknown.
 */
internal fun unknownFeeNote(maxFee: BigInteger): String =
    "${assumedAmountNote(maxFee)}. The fee model is unknown, so the total may be higher."

/** [wei] as an amount with 18 decimals, with at least one digit after the point, so 10^18 reads as "1.0". */
private fun assumedEighteen(wei: BigInteger): String {
    val text = Amounts.format(wei, ASSUMED_DECIMALS)
    return if ('.' in text) text else "$text.0"
}

/**
 * Splits an address into 4-character groups separated by spaces. The full address is kept. Text
 * wraps only at the spaces, so no group is cut in half.
 */
internal fun groupAddress(address: String): String = address.chunked(4).joinToString(" ")

/**
 * Splits hexadecimal [hex] (no 0x prefix) into groups of [size] digits separated by spaces. Every digit is kept, and text
 * wraps only at the spaces.
 */
internal fun groupHex(hex: String, size: Int = 8): String = hex.chunked(size).joinToString(" ")

/** A master fingerprint as 8 upper-case hex digits, or [NOT_STATED] when the request does not name one. */
internal fun fingerprintText(fingerprint: Int?): String = fingerprint?.let { "%08X".format(it) } ?: NOT_STATED

/** A derivation path in the usual notation, such as m/44'/60'/0'/0/0. */
internal fun pathText(path: List<PathStep>): String =
    "m" + path.joinToString("") { step -> "/${step.index}" + if (step.hardened) "'" else "" }

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** True for a 0x-prefixed address of 40 hexadecimal digits, in either letter case. */
internal fun isEvmAddress(text: String): Boolean = EVM_ADDRESS.matches(text)
