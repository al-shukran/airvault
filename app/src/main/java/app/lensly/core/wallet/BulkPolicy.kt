package app.lensly.core.wallet

/**
 * The rules of a Bulk Run: the Count a run may ask for, the Bulk Cap that bounds it, the presets, and the index range
 * that the new accounts take. The cap is the owner's setting ([BulkCap]), so every check takes it as an argument.
 * Nothing here touches keys or storage, so every rule is tested on the JVM.
 */
object BulkPolicy {
    /** The Counts one tap away on the bulk screen. Each one is at most 100, so it fits every [BulkCap]. */
    val PRESETS: List<Int> = listOf(10, 20, 50)

    /** The most digits a typed Count takes. Four digits reach 9999, which is above the largest [BulkCap]. */
    const val MAX_DIGITS = 4

    /** The outcome of [checkCount]. */
    sealed interface CountCheck {
        /** No Count has been typed yet. */
        data object Missing : CountCheck

        /** Below one account. */
        data object TooFew : CountCheck

        /** Above [cap], so the run is refused. */
        data class TooMany(val cap: Int) : CountCheck

        /** A Count that a run may create. */
        data class Valid(val count: Int) : CountCheck
    }

    /** Checks a typed [count] against 1 and the configured [cap]. Null means that no Count has been typed. */
    fun checkCount(count: Int?, cap: Int): CountCheck = when {
        count == null -> CountCheck.Missing
        count < 1 -> CountCheck.TooFew
        count > cap -> CountCheck.TooMany(cap)
        else -> CountCheck.Valid(count)
    }

    /**
     * The indices of [count] new accounts, numbered after the [existing] ones. Accounts are numbered from 0, so the
     * first new account takes index [existing].
     *
     * Indices are non-hardened BIP32 child numbers, and the account count is an Int, so a run may not take the count
     * past [Int.MAX_VALUE].
     *
     * @throws IllegalArgumentException if [existing] is negative, [count] is below 1, or the count would overflow.
     */
    fun indexRange(existing: Int, count: Int): IntRange {
        require(existing >= 0) { "existing accounts cannot be negative" }
        require(count >= 1) { "a run makes at least 1 account" }
        require(existing.toLong() + count <= Int.MAX_VALUE) { "the account count cannot pass Int.MAX_VALUE" }
        return existing until existing + count
    }

    /**
     * The Count after typing [digit] onto [digits]. A leading zero is replaced rather than kept, so "0" then "5" reads
     * "5". Typing stops at [MAX_DIGITS].
     */
    fun appendDigit(digits: String, digit: Char): String {
        require(digit in '0'..'9') { "a Count takes digits only" }
        if (digits.length >= MAX_DIGITS) return digits
        return if (digits == "0") digit.toString() else digits + digit
    }
}
