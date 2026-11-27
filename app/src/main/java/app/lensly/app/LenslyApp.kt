package app.lensly.app

import android.app.Application
import android.content.Context
import app.lensly.core.parse.BinderTransport
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.SandboxedParser
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout

/**
 * Application object. It owns the single [VaultHolder], so an open vault is shared by every screen. Screens never close the vault
 * themselves; the lock rules in the navigation host do that. It also owns the [EntryGestureHolder], which is kept outside the vault,
 * because the camera reads the gesture before the vault is unlocked. The cover and the gate are shared with Lumio, see [CoverApplication].
 */
class LenslyApp : Application(), CoverApplication {
    // Opening a vault removes the wallets a crash left unfinished, before any read of the vault.
    override val vaultHolder: VaultHolder by lazy {
        VaultHolder(Vault(this, onErased = eraseGestureAfterVaultErase(entryGestureHolder))) { session ->
            WalletStore(session).dropIncomplete()
            SessionReads(session)
        }
    }
    override val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    /** The Parser Sandbox: every parser of scanned input runs in its isolated process, which holds no keys and no files. */
    val requestParser: RequestParser by lazy { SandboxedParser(BinderTransport(this)) }

    override fun onCreate() {
        super.onCreate()
        logBackupExclusion()
        // A video that an interrupted recording left in the cache is plain, so it goes at the start, whether or not a vault exists.
        VaultLayout.of(this).shredRecording()
    }
}

/** The app-wide parser of scanned input, which runs in the Parser Sandbox. */
fun Context.requestParser(): RequestParser = (applicationContext as LenslyApp).requestParser
