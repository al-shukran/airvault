package app.lensly.core.wallet

import java.security.MessageDigest
import kotlin.math.log2

/**
 * Mixes device randomness with the user's dice rolls or coin flips.
 *
 * User input is either dice digits "1" to "6" (about 2.585 bits per roll) or coin letters "H" and "T"
 * (1 bit per flip). Any other input is rejected.
 */
object EntropyMixer {

    /** SHA-256(deviceEntropy || utf8(userInput)), 32 bytes, ready for [Mnemonic.fromEntropy]. */
    fun mix(deviceEntropy: ByteArray, userInput: String): ByteArray {
        require(deviceEntropy.size == Mnemonic.ENTROPY_BYTES) {
            "device entropy must be ${Mnemonic.ENTROPY_BYTES} bytes"
        }
        classify(userInput)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(deviceEntropy)
        digest.update(userInput.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /** SHA-256 of the device entropy as lowercase hex. Show it to the user before they roll. */
    fun commitment(deviceEntropy: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(deviceEntropy).toHex()

    /** Estimated bits contributed by [userInput]: dice n * log2(6), coin n. */
    fun userBits(userInput: String): Double = when (classify(userInput)) {
        Source.DICE -> userInput.length * log2(6.0)
        Source.COIN -> userInput.length.toDouble()
    }

    private enum class Source { DICE, COIN }

    private fun classify(input: String): Source = when {
        input.isNotEmpty() && input.all { it in '1'..'6' } -> Source.DICE
        input.isNotEmpty() && input.all { it == 'H' || it == 'T' } -> Source.COIN
        else -> throw IllegalArgumentException("user input must be dice digits 1-6 or coin letters H/T")
    }
}
