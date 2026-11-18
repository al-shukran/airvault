package app.lumio

import android.content.Intent

/** Benchmark stub, the same as the release one. A benchmark build has no pairing fixture, so the scan reads the camera only. */
internal object DebugPairFixture {
    fun partsFor(intent: Intent?): List<String>? = null
}
