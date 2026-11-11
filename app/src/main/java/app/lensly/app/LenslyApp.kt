package app.lensly.app

import android.app.Application
import android.content.Context
import android.util.Log
import app.lensly.core.vault.BackupExclusion
import app.lensly.core.vault.Vault

/**
 * Application object. It owns the single [VaultHolder], so an open vault is shared by every screen. Screens
 * never close the vault themselves; the lock rules in the navigation host do that. It also owns the [EntryGestureHolder],
 * which is kept outside the vault, because the camera reads the gesture before the vault is unlocked.
 */
class LenslyApp : Application() {
    val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this)) }
    val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    override fun onCreate() {
        super.onCreate()
        // Backup Exclusion, in debug and release alike. The line is a fixed message, so no secret can reach the log. The vault
        // itself refuses to create a vault in such a build, see Vault.refusal.
        if (BackupExclusion.allowsBackup(applicationInfo.flags)) Log.e(BackupExclusion.LOG_TAG, BackupExclusion.REFUSAL)
    }
}

/** The app-wide [VaultHolder]. */
fun Context.vaultHolder(): VaultHolder = (applicationContext as LenslyApp).vaultHolder

/** The app-wide [EntryGestureHolder]. */
fun Context.entryGestureHolder(): EntryGestureHolder = (applicationContext as LenslyApp).entryGestureHolder
