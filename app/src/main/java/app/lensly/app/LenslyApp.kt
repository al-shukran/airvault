package app.lensly.app

import android.app.Application
import android.content.Context
import app.lensly.core.vault.Vault

/**
 * Application object. It owns the single [VaultHolder], so an open vault is shared by every screen. Screens
 * never close the vault themselves; the lock rules in the navigation host do that.
 */
class LenslyApp : Application() {
    val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this)) }
}

/** The app-wide [VaultHolder]. */
fun Context.vaultHolder(): VaultHolder = (applicationContext as LenslyApp).vaultHolder
