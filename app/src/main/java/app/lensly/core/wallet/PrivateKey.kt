package app.lensly.core.wallet

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves

/**
 * A secp256k1 private key, as a Single Key wallet holds it: 32 bytes, at least 1 and below the curve order n.
 * Nothing is derived from the key, so it cannot be made again from anything else.
 */
object PrivateKey {
    /** A private key is 32 bytes. */
    const val BYTES = 32

    /** A private key typed as hex, without its 0x prefix, is 64 digits. */
    const val HEX_DIGITS = 2 * BYTES

    private val curveOrder: BigInteger = SECNamedCurves.getByName("secp256k1").n

    /** The outcome of [parse]. A refusal says why in a short line, and never repeats the typed text. */
    sealed interface Parse {
        /** The key as 32 bytes. The caller owns the array and must zero it. */
        class Ok(val key: ByteArray) : Parse

        /** The text is not a valid key. [reason] fits on one line of the screen. */
        class Rejected(val reason: String) : Parse
    }

    /**
     * Reads a private key typed as hex: 64 digits, with an optional 0x or 0X in front. Upper and lower case both work.
     * [typed] is read and not kept, so the caller zeroes it. A key that is zero, or not below n, is refused.
     */
    fun parse(typed: CharArray): Parse {
        val start = if (typed.size >= 2 && typed[0] == '0' && (typed[1] == 'x' || typed[1] == 'X')) 2 else 0
        val digits = typed.size - start
        if (digits == 0) return Parse.Rejected("Type $HEX_DIGITS hex digits.")
        if ((start until typed.size).any { hexValue(typed[it]) < 0 }) return Parse.Rejected(NOT_HEX)
        if (digits != HEX_DIGITS) return Parse.Rejected("$HEX_DIGITS hex digits needed, you typed $digits.")

        val key = ByteArray(BYTES) { i ->
            ((hexValue(typed[start + 2 * i]) shl 4) or hexValue(typed[start + 2 * i + 1])).toByte()
        }
        val value = BigInteger(1, key)
        return when {
            value.signum() == 0 -> refuse(key, ZERO)
            value >= curveOrder -> refuse(key, TOO_LARGE)
            else -> Parse.Ok(key)
        }
    }

    /** True when [key] is 32 bytes, at least 1, and below n. */
    fun isValid(key: ByteArray): Boolean {
        if (key.size != BYTES) return false
        val value = BigInteger(1, key)
        return value.signum() > 0 && value < curveOrder
    }

    private fun refuse(key: ByteArray, reason: String): Parse {
        key.fill(0)
        return Parse.Rejected(reason)
    }

    /** The value of a hex digit, or -1 for any other character. */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val NOT_HEX = "Only 0-9 and a-f are allowed."
    private const val ZERO = "A key cannot be zero."
    private const val TOO_LARGE = "Not valid: at or above the order n."
}
