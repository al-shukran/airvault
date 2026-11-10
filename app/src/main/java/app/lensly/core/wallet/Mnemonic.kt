package app.lensly.core.wallet

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric

/**
 * BIP39 mnemonics as Lensly uses them: 24 English words encoding 256 bits of entropy.
 *
 * Words are immutable Strings and cannot be wiped. Drop them once the seed has been derived.
 */
object Mnemonic {
    const val WORD_COUNT = 24
    const val ENTROPY_BYTES = 32

    private const val BITS_PER_WORD = 11
    private const val CHECKSUM_BITS = 8 // ENT / 32 for 256-bit entropy

    private val wordIndex: Map<String, Int> by lazy {
        MnemonicUtils.getWords().withIndex().associate { (index, word) -> word to index }
    }

    /** Generates 24 words from 256 bits read from [random]. */
    fun generate(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(ENTROPY_BYTES)
        try {
            random.nextBytes(entropy)
            return fromEntropy(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /** Encodes exactly 32 bytes of entropy as 24 words. The caller keeps ownership of [entropy]. */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "entropy must be $ENTROPY_BYTES bytes" }
        return MnemonicUtils.generateMnemonic(entropy).split(' ')
    }

    /** True only for exactly 24 lowercase English BIP39 words whose checksum matches. */
    fun isValid(words: List<String>): Boolean {
        if (words.size != WORD_COUNT) return false
        var bits = BigInteger.ZERO
        for (word in words) {
            val index = wordIndex[word] ?: return false
            bits = bits.shiftLeft(BITS_PER_WORD).or(BigInteger.valueOf(index.toLong()))
        }
        // 264 bits: 256 bits of entropy followed by the first 8 bits of SHA-256(entropy).
        val entropy = Numeric.toBytesPadded(bits.shiftRight(CHECKSUM_BITS), ENTROPY_BYTES)
        val checksum = bits.toInt() and 0xff
        val expected = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
        return checksum == expected
    }

    /**
     * The 64-byte BIP39 seed. Throws [IllegalArgumentException] unless [words] pass [isValid].
     * The passphrase is NFKD-normalized as BIP39 requires; web3j does not do this itself.
     */
    fun toSeed(words: List<String>, passphrase: String = ""): ByteArray {
        require(isValid(words)) { "not a valid 24-word BIP39 mnemonic" }
        val normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
        return MnemonicUtils.generateSeed(words.joinToString(" "), normalized)
    }
}
