package app.lumio

import android.content.Intent

/** Benchmark stub, the same as the release one. A benchmark build has no demo seed, so the launch extra asks for nothing. */
internal object DebugDemoSeed {
    fun mnemonicOf(intent: Intent?): String? = null

    suspend fun seed(app: LumioApp, mnemonic: String, showHome: () -> Unit) = Unit
}
