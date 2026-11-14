package app.lensly.app

/**
 * How long the dice and coin tiles stay hidden after a tap: a time drawn uniformly from [minMs] to [maxMs], both
 * included, in milliseconds. The owner sets both in Settings. A range is valid when 0 <= minMs <= maxMs <= [LIMIT_MS].
 */
data class HideDelay(val minMs: Long, val maxMs: Long) {
    init {
        require(isValid(minMs, maxMs)) { "A hide delay runs from 0 to $LIMIT_MS ms, shortest first." }
    }

    companion object {
        /** The longest hide delay the owner may set, in milliseconds. */
        const val LIMIT_MS = 5_000L

        /** The shortest hide delay of a new vault, in milliseconds. */
        const val DEFAULT_MIN_MS = 100L

        /** The longest hide delay of a new vault, in milliseconds. */
        const val DEFAULT_MAX_MS = 1_000L

        val DEFAULT = HideDelay(DEFAULT_MIN_MS, DEFAULT_MAX_MS)

        /** True when [minMs] and [maxMs] are a range Settings accepts: 0 <= minMs <= maxMs <= [LIMIT_MS]. */
        fun isValid(minMs: Long, maxMs: Long): Boolean = minMs in 0..maxMs && maxMs <= LIMIT_MS

        /** The range from [minMs] to [maxMs], or null when Settings does not accept it. */
        fun of(minMs: Long, maxMs: Long): HideDelay? = if (isValid(minMs, maxMs)) HideDelay(minMs, maxMs) else null
    }
}
