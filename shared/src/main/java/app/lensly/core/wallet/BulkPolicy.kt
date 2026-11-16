package app.lensly.core.wallet

/**
 * The rules of a Bulk Run: the Count a run may ask for, the Bulk Cap that bounds it, and the index range that the new accounts take.
 * The cap is the owner's setting ([BulkCap]), so every check takes it as an argument. Nothing here touches keys or storage, so every
 * rule is tested on the JVM.
 */
object BulkPolicy {
    /** The outcome of [checkCount] and [checkTyped]. */
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

    /** Checks a Count against 1 and the configured [cap]. Null means that no Count has been typed. */
    fun checkCount(count: Int?, cap: Int): CountCheck = when {
        count == null -> CountCheck.Missing
        count < 1 -> CountCheck.TooFew
        count > cap -> CountCheck.TooMany(cap)
        else -> CountCheck.Valid(count)
    }

    /**
     * Checks the Count as it is typed into the count field under [cap]. Empty text, or text with anything but digits, is
     * [CountCheck.Missing]: the field takes digits only, so such text is no Count. Digits too large for an Int are above every cap,
     * so they are [CountCheck.TooMany]. A leading zero does not change the Count: "007" is 7.
     */
    fun checkTyped(text: String, cap: Int): CountCheck {
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return CountCheck.Missing
        return checkCount(text.toIntOrNull() ?: Int.MAX_VALUE, cap)
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
}
