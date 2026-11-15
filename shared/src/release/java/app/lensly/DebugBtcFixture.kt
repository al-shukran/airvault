package app.lensly

import android.content.Intent

/** Stub. Builds without the debug hook take no Bitcoin fixture, so the sign screen only ever scans what the camera reads. */
object DebugBtcFixture {
    fun offer(intent: Intent) = Unit

    fun take(): String? = null
}
