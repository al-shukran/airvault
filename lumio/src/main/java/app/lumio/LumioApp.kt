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
import app.lensly.core.vault.VaultSession
import app.lumio.data.provider.Http
import app.lumio.data.rpc.JsonRpc
import app.lumio.evm.EvmReads
import app.lumio.evm.EvmRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Lumio's application object. It owns the holders that the shared cover and gate use. Lumio holds no key material. Its vault is
 * the App Password gate, and it keeps no wallet secret. The chain registry and the RPC client are shared by every screen.
 */
class LumioApp : Application(), CoverApplication {
    override val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this)) }
    override val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    /** The chain registry, read from the catalogs in this APK. Debug builds include the debug testnets. */
    val registry: Registry by lazy { RegistryLoader.fromAssets(assets) }

    /** The HTTP stack that every RPC call shares: no redirects, and each call is bounded by a timeout. */
    val rpcHttp: Http by lazy { JsonRpc.newHttp() }

    /** The timers of the scheduler's waiting calls. They live as long as the app. */
    private val rpcScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** The chain check for an RPC address that the owner enters in settings. Plain HTTP is accepted only in a debug build. */
    val evmRpc: EvmRpc by lazy { EvmRpc(allowLocalHttp = BuildConfig.DEBUG) }

    private var evmReads: Pair<VaultSession, EvmReads>? = null

    /** The EVM reads of [session]. They are built once per unlocked session, so the scheduler keeps its health and rates across refreshes. */
    @Synchronized
    fun evmReadsFor(session: VaultSession): EvmReads {
        evmReads?.takeIf { it.first === session }?.let { return it.second }
        return EvmReads.open(session, rpcHttp, rpcScope, allowLocalHttp = BuildConfig.DEBUG).also { evmReads = session to it }
    }

    override fun onCreate() {
        super.onCreate()
        logBackupExclusion()
    }
}
