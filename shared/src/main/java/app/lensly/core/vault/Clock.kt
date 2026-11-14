package app.lensly.core.vault

import android.os.SystemClock

/**
 * Time source for the failure delay. Injected so that tests can move time forward without sleeping.
 */
interface Clock {
    /** Wall-clock time in milliseconds since the Unix epoch. The user can change it. */
    fun wallMillis(): Long

    /**
     * Milliseconds since boot from a monotonic clock that keeps counting during sleep.
     * It restarts at zero on reboot.
     */
    fun elapsedMillis(): Long
}

/** Production clock: `System.currentTimeMillis()` together with `SystemClock.elapsedRealtime()`. */
object RealClock : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
