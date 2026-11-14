package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's erase for Delete Wallet, on real storage. A Keystore failure still removes the wallet, so the erase reports
 * [ShredOutcome.ERASED_KEY_PENDING] rather than an unavailable vault.
 */
class DeleteWalletPortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"

    /** A session on [keys], and a holder that has it open, as the app has after an unlock. */
    private fun openHolder(keys: MemoryKeystore): Pair<VaultSession, VaultHolder> {
        val session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 3).toByte() })
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        return session to VaultHolder(vault).also { it.adopt(session, it.unlockEpoch()) }
    }

    @Test
    fun aDeleteThatFinishesReportsErased() = runBlocking {
        val keys = MemoryKeystore()
        val (session, holder) = openHolder(keys)
        val store = WalletStore(session)
        val walletId = newWallet(store, "GONE")

        assertEquals(ShredOutcome.ERASED, DeleteWalletPorts(holder, walletId).erase())
        assertNull(store.meta(walletId))
        session.close()
    }

    @Test
    fun aKeystoreFailureReportsErasedWithTheKeyPending() = runBlocking {
        val keys = MemoryKeystore()
        val (session, holder) = openHolder(keys)
        val store = WalletStore(session)
        val walletId = newWallet(store, "GONE")
        val kept = newWallet(store, "KEPT")
        keys.failFolderKeyDeletes = true

        assertEquals(ShredOutcome.ERASED_KEY_PENDING, DeleteWalletPorts(holder, walletId).erase())
        assertNull(store.meta(walletId))
        assertNotNull(store.meta(kept))
        session.close()
    }

    private fun newWallet(store: WalletStore, name: String): String = store.create(
        name = name,
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )
}
