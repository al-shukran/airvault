package app.lumio

import android.content.Intent

/** Release stub. Release builds have no demo seed, so the launch extra asks for nothing and the vault is never seeded. */
internal object DebugDemoSeed {
    fun mnemonicOf(intent: Intent?): String? = null

    suspend fun seed(app: LumioApp, mnemonic: String, showHome: () -> Unit) = Unit
}
