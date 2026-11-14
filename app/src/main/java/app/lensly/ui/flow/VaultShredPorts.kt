package app.lensly.ui.flow

import app.lensly.app.EntryGestureHolder
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
        } catch (e: VaultException) {
            // The wallet is already gone. Only its Keystore key is left, and the next unlock deletes it. Listed before
            // IllegalStateException, which VaultException extends.
            ShredOutcome.ERASED_KEY_PENDING
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
 * The app's [ShredPorts] for Wipe. The App Password goes through [checkAppPassword]. A real vault wipe removes the whole
 * vault and resets the Hidden Entry Gesture; a wipe from the Decoy Vault removes only that decoy and leaves the gesture
 * and the real vault alone.
 */
internal class WipeVaultPorts(
    private val holder: VaultHolder,
    private val gestures: EntryGestureHolder? = null,
) : ShredPorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /**
     * A decoy session wipes only its own vault, while its key is still open. A real session locks first, so the Vault Key
     * is zeroed before any file goes, and then resets the entry gesture. NonCancellable, so a wipe that has started runs
     * to its end.
     */
    override suspend fun erase(): ShredOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val session = holder.openSession()
        if (session != null && session.isDecoy) {
            val outcome = try {
                holder.vault.wipeDecoy(session)
                ShredOutcome.ERASED
            } catch (e: VaultException) {
                ShredOutcome.UNAVAILABLE
            } catch (e: GeneralSecurityException) {
                ShredOutcome.UNAVAILABLE
            } catch (e: ProviderException) {
                ShredOutcome.UNAVAILABLE
            }
            holder.lock()
            outcome
        } else {
            holder.lock()
            try {
                holder.vault.wipe()
                // Only a real wipe reaches the real store, so only it may reset the gesture. A decoy never changes it.
                val gestureReset = gestures?.reset() ?: true
                // A reset that failed leaves the old gesture in place, so the wipe is not reported as complete.
                if (gestureReset) ShredOutcome.ERASED else ShredOutcome.UNAVAILABLE
            } catch (e: VaultException) {
                ShredOutcome.UNAVAILABLE
            } catch (e: GeneralSecurityException) {
                ShredOutcome.UNAVAILABLE
            } catch (e: ProviderException) {
                ShredOutcome.UNAVAILABLE
            }
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
