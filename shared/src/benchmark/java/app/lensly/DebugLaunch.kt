package app.lensly

import android.content.Intent

/**
 * Benchmark stub, the same as the release one. The benchmark variant is not debuggable and has no debug launch hook,
 * so the hidden area opens only by the two-finger gesture.
 */
internal object DebugLaunch {
    fun opensHidden(intent: Intent): Boolean = false
}
