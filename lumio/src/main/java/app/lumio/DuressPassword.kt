package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.checkAppPassword
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetupStore
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.firstrun.newPasswordProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Sets the Duress Password of the open vault, in the order the page reads: the new password and its repeat first, then [current] through the shared
 * unlock path when the step asks for it (Settings does, the first run does not, since the owner has just made the password), and only then the set.
 * The result is [PasswordChange.Changed] once the password opens a Decoy Vault, which holds no paired wallet, so Lumio opens empty under it. A password
 * that opens the real vault is refused as [NewPasswordProblem.SameAsLumio], and a step that fails leaves the vault as it was. The checklist tick is written before the set and taken back when the set does not
 * happen, and a tick that cannot be written stops the set as Unavailable. NonCancellable from the tick on, so a set that has started runs to its outcome. Setting it again replaces the Duress Password and its decoy. In a Decoy Vault session the
 * same call changes the decoy's own password, so that session shows no difference.
 */
suspend fun setLumioDuressPassword(holder: VaultHolder, current: String?, password: String, repeat: String): PasswordChange {
    newPasswordProblem(password, repeat)?.let { return PasswordChange.NewPassword(it) }
    if (current != null) {
        when (checkAppPassword(holder, current.toCharArray())) {
            PasswordCheck.Accepted -> Unit
            PasswordCheck.Rejected -> return PasswordChange.WrongCurrent
            PasswordCheck.Unavailable -> return PasswordChange.Unavailable
        }
    }
    return withContext(NonCancellable) {
        val session = holder.openSession() ?: return@withContext PasswordChange.Locked
        // The vault cannot say whether a Duress Password exists, so the Security row reads this tick. It is written before the set, and taken back if
        // the set does not happen, so the row never disagrees with the vault. A tick that cannot be written stops the set.
        val ticked = withContext(Dispatchers.IO) {
            runCatching {
                val before = FinishItem.DURESS in FinishSetupStore.read(session).done
                FinishSetupStore.tick(session, FinishItem.DURESS)
                !before
            }
        }.getOrElse { return@withContext PasswordChange.Unavailable }
        var enabled = false
        try {
            when (val result = holder.vault.setDuress(session, password.toCharArray(), ::populateDecoy)) {
                DuressResult.Enabled -> {
                    enabled = true
                    PasswordChange.Changed
                }
                is DuressResult.PolicyRejected -> PasswordChange.NewPassword(
                    if (result.reason == PolicyReason.TOO_SHORT) NewPasswordProblem.TooShort else NewPasswordProblem.TooWeak,
                )
                DuressResult.SameAsAppPassword -> PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio)
                DuressResult.DecoyEmpty, DuressResult.Failed -> PasswordChange.Unavailable
            }
        } catch (e: VaultException) {
            PasswordChange.Unavailable
        } catch (e: IllegalStateException) {
            // The session was closed by a lock while the set ran.
            PasswordChange.Locked
        } finally {
            if (ticked && !enabled) withContext(Dispatchers.IO) { runCatching { FinishSetupStore.untick(session, FinishItem.DURESS) } }
        }
    }
}

/**
 * The first wallet folder of a new Decoy Vault. The vault needs a decoy to hold one folder before the Duress Password takes effect, and Lumio's
 * decoy holds no pairing: a folder with no META record is no paired wallet (see [app.lumio.pair.PairedWallets.list]), so Lumio opens empty.
 */
private fun populateDecoy(session: VaultSession) {
    session.createWallet()
}
