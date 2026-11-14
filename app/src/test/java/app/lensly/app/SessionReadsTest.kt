package app.lensly.app

import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reads that Home and the Wallets list keep for one session: kept while no write has changed them, and dropped on lock. */
class SessionReadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val key = ByteArray(32) { (it + 5).toByte() }
    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, key.copyOf())
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    @Test
    fun aKeptListingHoldsUntilAWalletChanges() {
        val id = newWallet("FIRST")
        val reads = SessionReads(session)

        val kept = reads.listing()
        assertSame(kept, reads.cachedListing(), "Nothing has been written since the read, so it is kept.")

        store.renameWallet(id, "RENAMED")
        assertNull(reads.cachedListing(), "A rename drops the kept listing.")
        assertEquals("RENAMED", reads.listing().wallets.single().name)
    }

    @Test
    fun aNewWalletDropsTheKeptListing() {
        newWallet("FIRST")
        val reads = SessionReads(session)
        reads.listing()

        newWallet("SECOND")
        assertNull(reads.cachedListing(), "A new wallet drops the kept listing.")
        assertEquals(2, reads.listing().wallets.size)
    }

    @Test
    fun lockingTheHolderDropsTheKeptReads() {
        val holder = VaultHolder(Vault(VaultLayout(tmp.root), keys, FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))) { SessionReads(it) }
        val epoch = holder.unlockEpoch()
        assertTrue(holder.adopt(VaultSession(VaultLayout(tmp.root), keys, key.copyOf()), epoch))
        assertNotNull(holder.sessionReads(), "An open session has its reads.")

        holder.lock()
        assertNull(holder.sessionReads(), "Locking drops the reads with the session.")
        assertNull(holder.openSession())
    }

    private fun newWallet(name: String): String = store.create(
        name = name,
        deviceGenerated = false,
        words = words,
        passphrase = name,
        nowMs = 1L,
    )
}
