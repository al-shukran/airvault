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
 * The words, their entropy and the derived seed live in buffers this instance owns, and [wipe] zeroes every one of
 * them. Create one for a single operation and use it with `use { }`, so it is wiped afterwards. Reading a wiped
 * instance throws [IllegalStateException], except for [equals], [hashCode] and [iterator], which a screen holding the
 * instance may call again after the wipe.
 *
 * The [List] view lets screens and records read the words one at a time. Each [get] decodes a word from the buffer
 * into a short-lived String. [text] returns a wipeable copy for callers that can handle one.
 */
class Mnemonic private constructor(
    /** The 24 words joined by single spaces, as lowercase ASCII characters. */
    private val wordChars: CharArray,
    /** The 32 bytes of entropy the words encode. */
    private val entropy: ByteArray,
) : AbstractList<String>(), AutoCloseable {

    /** The 64-byte BIP39 seed, filled by [deriveSeed] and zeroed by [wipe]. */
    private val seed = ByteArray(SEED_BYTES)

    private var wiped = false

    override val size: Int
        get() {
            checkLive()
            return WORD_COUNT
        }

    override fun get(index: Int): String {
        checkLive()
        if (index < 0 || index >= WORD_COUNT) throw IndexOutOfBoundsException("index $index, size $WORD_COUNT")
        var start = 0
        var word = 0
        while (word < index) {
            while (wordChars[start] != WORD_SEPARATOR) start++
            start++
            word++
        }
        var end = start
        while (end < wordChars.size && wordChars[end] != WORD_SEPARATOR) end++
        return String(wordChars, start, end - start)
    }

    /** A fresh copy of the 24 words joined by spaces, for display. The caller must wipe the returned array. */
    fun text(): CharArray {
        checkLive()
        return wordChars.copyOf()
    }

    /**
     * The 64-byte BIP39 seed for this mnemonic and [passphrase]. A fresh copy is returned; the caller must wipe it.
     * The passphrase is NFKD-normalized as BIP39 requires; web3j does not do this itself.
     */
    fun deriveSeed(passphrase: String = ""): ByteArray {
        checkLive()
        val normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
        // web3j takes the phrase as a String, so one copy exists for the call. The buffer itself stays wipeable.
        val derived = MnemonicUtils.generateSeed(String(wordChars), normalized)
        try {
            derived.copyInto(seed)
            return derived.copyOf()
        } finally {
            derived.fill(0)
        }
    }

    /** Zeroes every buffer this instance owns. Safe to call more than once. */
    fun wipe() {
        wordChars.fill('\u0000')
        entropy.fill(0)
        seed.fill(0)
        wiped = true
    }

    override fun close() = wipe()

    /**
     * A wiped instance compares by identity, because its contents are gone. A screen that recomposes with a reference
     * to it therefore compares safely instead of reading zeroed buffers.
     */
    override fun equals(other: Any?): Boolean = if (wiped) this === other else super.equals(other)

    /** The identity hash of a wiped instance, since a content hash would read the zeroed buffers. */
    override fun hashCode(): Int = if (wiped) System.identityHashCode(this) else super.hashCode()

    /** A wiped instance has no words left to iterate, so it iterates as empty rather than throwing. */
    override fun iterator(): Iterator<String> = if (wiped) emptyList<String>().iterator() else super.iterator()

    private fun checkLive() {
        check(!wiped) { "Mnemonic has been wiped" }
    }

    companion object {
        const val WORD_COUNT = 24
        const val ENTROPY_BYTES = 32

        private const val SEED_BYTES = 64
        private const val BITS_PER_WORD = 11
        private const val CHECKSUM_BITS = 8 // ENT / 32 for 256-bit entropy
        private const val WORD_SEPARATOR = ' '

        private val wordIndex: Map<String, Int> by lazy {
            MnemonicUtils.getWords().withIndex().associate { (index, word) -> word to index }
        }

        /** Generates 24 words from 256 bits read from [random]. */
        fun generate(random: SecureRandom = SecureRandom()): Mnemonic {
            val entropy = ByteArray(ENTROPY_BYTES)
            try {
                random.nextBytes(entropy)
                return fromEntropy(entropy)
            } finally {
                entropy.fill(0)
            }
        }

        /** Encodes exactly 32 bytes of entropy as 24 words. The caller keeps ownership of [entropy]. */
        fun fromEntropy(entropy: ByteArray): Mnemonic {
            require(entropy.size == ENTROPY_BYTES) { "entropy must be $ENTROPY_BYTES bytes" }
            val words = MnemonicUtils.generateMnemonic(entropy)
            return Mnemonic(words.toCharArray(), entropy.copyOf())
        }

        /** Takes exactly 24 lowercase English BIP39 words whose checksum matches. */
        fun of(words: List<String>): Mnemonic {
            val entropy = entropyOf(words) ?: throw IllegalArgumentException("not a valid 24-word BIP39 mnemonic")
            return Mnemonic(joined(words), entropy)
        }

        /** True only for exactly 24 lowercase English BIP39 words whose checksum matches. */
        fun isValid(words: List<String>): Boolean {
            val entropy = entropyOf(words) ?: return false
            entropy.fill(0)
            return true
        }

        /**
         * The 64-byte BIP39 seed. Throws [IllegalArgumentException] unless [words] pass [isValid]. A [Mnemonic]
         * instance derives the seed from its own buffers; a plain list is wrapped for the call and wiped after it.
         */
        fun toSeed(words: List<String>, passphrase: String = ""): ByteArray {
            if (words is Mnemonic) return words.deriveSeed(passphrase)
            return of(words).use { it.deriveSeed(passphrase) }
        }

        /** Copies [words] into one space-separated character buffer, without building a joined String. */
        private fun joined(words: List<String>): CharArray {
            val chars = CharArray(words.sumOf { it.length } + words.size - 1)
            var at = 0
            for ((index, word) in words.withIndex()) {
                if (index > 0) chars[at++] = WORD_SEPARATOR
                word.forEach { chars[at++] = it }
            }
            return chars
        }

        /**
         * The 32 bytes of entropy [words] encode, or null when they are not exactly 24 valid words. The caller
         * owns the returned array and must wipe it.
         */
        private fun entropyOf(words: List<String>): ByteArray? {
            if (words.size != WORD_COUNT) return null
            var bits = BigInteger.ZERO
            for (word in words) {
                val index = wordIndex[word] ?: return null
                bits = bits.shiftLeft(BITS_PER_WORD).or(BigInteger.valueOf(index.toLong()))
            }
            // 264 bits: 256 bits of entropy followed by the first 8 bits of SHA-256(entropy).
            val entropy = Numeric.toBytesPadded(bits.shiftRight(CHECKSUM_BITS), ENTROPY_BYTES)
            val checksum = bits.toInt() and 0xff
            val expected = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
            if (checksum == expected) return entropy
            entropy.fill(0)
            return null
        }
    }
}
