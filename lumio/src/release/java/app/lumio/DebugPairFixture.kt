package app.lumio

import android.content.Intent

/** Release stub. Release builds have no pairing fixture, so the scan reads the camera only. */
internal object DebugPairFixture {
    fun partsFor(intent: Intent?): List<String>? = null
}
