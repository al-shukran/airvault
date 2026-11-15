package app.lumio.data

/**
 * A millisecond clock. The scheduler waits on a monotonic clock, which never goes back. The cache stamps values with the wall clock, so a
 * stamp still means a time after a restart.
 */
fun interface Clock {
    fun nowMs(): Long
}

/** Milliseconds since boot. Use it for waits and timeouts. */
object MonotonicClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

/** Epoch milliseconds. Use it for stamps that are stored. */
object WallClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
