package app.lumio

import android.app.Application
import app.lensly.app.CoverApplication
import app.lensly.app.EntryGestureHolder
import app.lensly.app.EntryGestureStore
import app.lensly.app.VaultHolder
import app.lensly.app.logBackupExclusion
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.Vault
import app.lumio.evm.EvmRpc

/**
 * Lumio's application object. It owns the holders that the shared cover and gate use. Lumio holds no key material. Its vault is
 * the App Password gate, and it keeps no wallet secret. The chain registry and the RPC client are shared by every screen.
 */
class LumioApp : Application(), CoverApplication {
    override val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this)) }
    override val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    /** The chain registry, read from the catalogs in this APK. Debug builds include the debug testnets. */
    val registry: Registry by lazy { RegistryLoader.fromAssets(assets) }

    /** The JSON-RPC client. Plain HTTP is accepted only in a debug build, and only to the emulator's host address or to localhost. */
    val evmRpc: EvmRpc by lazy { EvmRpc(allowLocalHttp = BuildConfig.DEBUG) }

    override fun onCreate() {
        super.onCreate()
        logBackupExclusion()
    }
}
