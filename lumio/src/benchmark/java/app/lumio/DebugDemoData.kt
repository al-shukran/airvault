package app.lumio

import android.content.Intent
import app.lensly.app.export.PairingPayload
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import app.lumio.data.provider.Provider
import app.lumio.network.NetworkMode
import app.lumio.send.SubmitMachine

/** Stub: this build has no demo data mode, so every source is the real one and no extra opens a screen. */
internal object DebugDemoData {
    val active: Boolean get() = false

    fun offer(intent: Intent) = Unit

    fun providers(real: List<Provider>): List<Provider> = real

    /** The side the demo seed selects, or null when this build has no demo mode. */
    val mode: NetworkMode? get() = null

    fun flag(key: String): Boolean = false

    fun text(key: String): String = ""

    /** The step of the send that the open frame asks for, or null. */
    fun sendStage(): String? = null

    /** The pairing that a Pair Review frame shows before any scan, or null. */
    fun pairing(): PairingPayload? = null

    fun signedMachine(failed: Boolean): SubmitMachine<ByteArray, String> = throw UnsupportedOperationException()

    fun lastChanceSeconds(seconds: Int, stage: String?): Int = seconds

    @Composable
    fun Entry(controller: NavHostController, selection: NetworkSelection, request: Int) = Unit
}
