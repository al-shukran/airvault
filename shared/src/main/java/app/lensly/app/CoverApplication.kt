package app.lensly.app

import android.app.Application
import android.content.Context
import android.util.Log
import app.lensly.core.vault.BackupExclusion

/**
 * The application object of a cover app, Lensly or Lumio. It owns the holders that the shared cover and gate use, so an open vault is
 * shared by every screen. The entry gesture is kept outside the vault, because the camera reads it before the vault is unlocked.
 */
interface CoverApplication {
    val vaultHolder: VaultHolder
    val entryGestureHolder: EntryGestureHolder
}

/** The app-wide [VaultHolder]. */
fun Context.vaultHolder(): VaultHolder = (applicationContext as CoverApplication).vaultHolder

/** The app-wide [EntryGestureHolder]. */
fun Context.entryGestureHolder(): EntryGestureHolder = (applicationContext as CoverApplication).entryGestureHolder

/**
 * Backup Exclusion, in debug and release alike: a build that allows backup logs a fixed line, so no secret can reach the log. The vault
 * itself refuses to create a vault in such a build, see Vault.refusal.
 */
fun Application.logBackupExclusion() {
    if (BackupExclusion.allowsBackup(applicationInfo.flags)) Log.e(BackupExclusion.LOG_TAG, BackupExclusion.REFUSAL)
}
