package app.lensly

import android.content.Intent

/** Release stub. Release builds have no debug launch hook, so the hidden area opens only by the gesture. */
internal object DebugLaunch {
    fun opensHidden(intent: Intent): Boolean = false
}
