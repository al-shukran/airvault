package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.app.WalletSecret
import app.lensly.app.WalletStore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's [RevealPorts] for one wallet. The App Password is checked through the same unlock as the gate, so the Failure
 * Delay applies. The secret is read from the open session, and only after the owner has asked to see it.
 */
internal class VaultRevealPorts(
    private val holder: VaultHolder,
    private val walletId: String,
) : RevealPorts {

    /** Opens a second session only to prove the password, then closes it at once. The open session is left alone. */
    override suspend fun checkPassword(password: CharArray): PasswordCheck = try {
        when (val result = holder.vault.unlock(password)) {
            is UnlockResult.Unlocked -> {
                result.session.close()
                PasswordCheck.Accepted
            }
            UnlockResult.Rejected -> PasswordCheck.Rejected
        }
    } catch (e: VaultException) {
        PasswordCheck.Unavailable
    }

    /** Reads the secret from the open session. A Single Key's bytes are zeroed by [RevealedSecret.ofKey]. */
    override suspend fun loadSecret(): SecretLoad = withContext(Dispatchers.IO) {
        val session = holder.openSession() ?: return@withContext SecretLoad.Locked
        try {
            when (val secret = WalletStore(session).secret(walletId)) {
                is WalletSecret.Mnemonic -> SecretLoad.Loaded(RevealedSecret.ofMnemonic(secret.words, secret.passphrase))
                is WalletSecret.SingleKey -> SecretLoad.Loaded(RevealedSecret.ofKey(secret.privateKey))
                null -> SecretLoad.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // A closed session or a damaged vault. Only a session that was closed means the vault was locked meanwhile.
            if (holder.openSession() == null) SecretLoad.Locked else SecretLoad.Unavailable
        } catch (e: IOException) {
            // A record that cannot be read or decoded. The screen says so, and nothing is shown.
            SecretLoad.Unavailable
        } catch (e: IllegalArgumentException) {
            SecretLoad.Unavailable
        }
    }
}
