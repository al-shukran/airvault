package app.lensly.core.vault

/** Clock whose readings change only when a test moves them. */
class FakeClock(
    var wall: Long = START_WALL,
    var elapsed: Long = START_ELAPSED,
) : Clock {
    override fun wallMillis(): Long = wall

    override fun elapsedMillis(): Long = elapsed

    /** Moves both clocks forward by [ms], as time passing within one boot does. */
    fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }

    companion object {
        const val START_WALL = 1_800_000_000_000L
        const val START_ELAPSED = 500_000L
    }
}
