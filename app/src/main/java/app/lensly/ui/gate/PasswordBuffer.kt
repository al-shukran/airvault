package app.lensly.ui.gate

/**
 * Mutable password buffer backed by a [CharArray], so the plaintext never sits in an immutable
 * String. Backing arrays that are replaced while growing are zero-filled first, and [clear] wipes
 * every slot, including those past [length].
 */
class PasswordBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {
    private var chars: CharArray = CharArray(initialCapacity.coerceIn(1, MAX_LENGTH))

    /** Number of characters currently entered. */
    var length: Int = 0
        private set

    /** Appends [c]. Returns false, and changes nothing, once [MAX_LENGTH] characters are held. */
    fun append(c: Char): Boolean {
        if (length >= MAX_LENGTH) return false
        if (length == chars.size) grow()
        chars[length] = c
        length++
        return true
    }

    /** Removes the last character and zero-fills its slot. No-op when empty. */
    fun backspace() {
        if (length == 0) return
        length--
        chars[length] = NUL
    }

    /** Zero-fills the whole backing array and resets [length] to 0. */
    fun clear() {
        chars.fill(NUL)
        length = 0
    }

    fun isEmpty(): Boolean = length == 0

    /**
     * Returns a fresh copy of the entered characters. The caller owns the copy and should
     * zero-fill it (`fill('\u0000')`) as soon as it is no longer needed.
     */
    fun toCharArray(): CharArray = chars.copyOf(length)

    private fun grow() {
        val bigger = chars.copyOf(minOf(chars.size * 2, MAX_LENGTH))
        chars.fill(NUL)
        chars = bigger
    }

    /** Exposes the raw backing array so tests can check that unused slots are zero-filled. */
    internal fun backingArrayForTest(): CharArray = chars

    companion object {
        const val MAX_LENGTH = 128
        private const val DEFAULT_CAPACITY = 16
        private const val NUL = '\u0000'
    }
}
