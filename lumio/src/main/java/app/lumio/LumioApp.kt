package app.lumio

import android.app.Application
import app.lensly.app.CoverApplication
import app.lensly.app.EntryGestureHolder
import app.lensly.app.EntryGestureStore
import app.lensly.app.VaultHolder
import app.lensly.app.logBackupExclusion
import app.lensly.core.vault.Vault

/**
 * Lumio's application object. It owns the holders that the shared cover and gate use. Lumio holds no key material. Its vault is
 * the App Password gate, and it keeps no wallet secret.
 */
class LumioApp : Application(), CoverApplication {
    override val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this)) }
    override val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    override fun onCreate() {
        super.onCreate()
        logBackupExclusion()
    }
}
