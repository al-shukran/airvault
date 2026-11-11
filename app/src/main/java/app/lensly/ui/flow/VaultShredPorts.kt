package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.core.vault.VaultException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The app's [ShredPorts] for Delete Wallet. The App Password goes through [checkAppPassword], and the erase crypto-shreds
 * one wallet through the open session. The other wallets are not touched.
 */
internal class DeleteWalletPorts(
    private val holder: VaultHolder,
    private val walletId: String,
) : ShredPorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /** NonCancellable, so leaving the screen cannot stop the shred half way. */
    override suspend fun erase(): ShredOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val session = holder.openSession() ?: return@withContext ShredOutcome.LOCKED
        try {
            WalletStore(session).delete(walletId)
            ShredOutcome.ERASED
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // A closed session. The vault was locked while the erase was starting.
            if (holder.openSession() == null) ShredOutcome.LOCKED else ShredOutcome.UNAVAILABLE
        } catch (e: IOException) {
            ShredOutcome.UNAVAILABLE
        } catch (e: IllegalArgumentException) {
            ShredOutcome.UNAVAILABLE
        }
    }
}

/**
 * The app's [ShredPorts] for Wipe. The App Password goes through [checkAppPassword], and the erase removes the whole vault.
 */
internal class WipeVaultPorts(private val holder: VaultHolder) : ShredPorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /**
     * The session closes first, so the Vault Key is zeroed before any file goes. NonCancellable, so a wipe that has started
     * runs to its end.
     */
    override suspend fun erase(): ShredOutcome = withContext(Dispatchers.IO + NonCancellable) {
        holder.lock()
        try {
            holder.vault.wipe()
            ShredOutcome.ERASED
        } catch (e: VaultException) {
            ShredOutcome.UNAVAILABLE
        } catch (e: GeneralSecurityException) {
            ShredOutcome.UNAVAILABLE
        } catch (e: ProviderException) {
            ShredOutcome.UNAVAILABLE
        }
    }
}

/**
 * The app's [ShredPorts] for Remove Duress Password. The App Password goes through [checkAppPassword]. The erase removes the
 * Duress Password, through the open session, so on a decoy session it removes that decoy only. NonCancellable, so a removal
 * that has started runs to its end.
 */
internal class RemoveDuressPorts(private val holder: VaultHolder) : ShredPorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    override suspend fun erase(): ShredOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val session = holder.openSession() ?: return@withContext ShredOutcome.LOCKED
        try {
            holder.vault.removeDuress(session)
            ShredOutcome.ERASED
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            ShredOutcome.UNAVAILABLE
        } catch (e: IllegalStateException) {
            // A closed session. The vault was locked while the removal was starting.
            if (holder.openSession() == null) ShredOutcome.LOCKED else ShredOutcome.UNAVAILABLE
        } catch (e: IOException) {
            ShredOutcome.UNAVAILABLE
        }
    }
}
