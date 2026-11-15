package app.lensly.app

import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
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

    @Test
    fun aKeptEthereumViewHoldsUntilAnAccountIsAdded() {
        val id = newWallet("FIRST")
        val reads = SessionReads(session)

        val kept = requireNotNull(reads.ethereumAccounts(id))
        assertSame(kept, reads.cachedEthereumAccounts(id), "Nothing has been written since the read, so the view is kept.")
        assertEquals((0 until kept.meta.accountCount).map { store.accountAddress(id, it) }, kept.addresses)

        store.addAccount(id)
        assertNull(reads.cachedEthereumAccounts(id), "An added account drops the kept view.")
        val after = requireNotNull(reads.ethereumAccounts(id))
        assertEquals(2, after.addresses.size)
        assertEquals(store.accountAddress(id, 1), after.addresses[1])
        assertEquals(AccountAddresses.addressAt(requireNotNull(after.meta.accountXpub), 1), after.addresses[1])
    }

    @Test
    fun aRenamedAccountDropsTheKeptViewAndAResetOneDropsItAgain() {
        val id = newWallet("FIRST")
        val reads = SessionReads(session)
        reads.ethereumAccounts(id)

        store.renameAccount(id, 0, "PAYROLL")
        assertNull(reads.cachedEthereumAccounts(id), "A renamed account drops the kept view.")
        assertEquals("PAYROLL", requireNotNull(reads.ethereumAccounts(id)).profiles[0]?.name)

        store.resetAccountNames(id, listOf(0))
        assertNull(reads.cachedEthereumAccounts(id), "A reset name drops the kept view.")
        assertNull(requireNotNull(reads.ethereumAccounts(id)).profiles[0], "The account shows its Auto-Name again.")
    }

    @Test
    fun aDeletedWalletDropsTheKeptView() {
        val id = newWallet("FIRST")
        val reads = SessionReads(session)
        reads.ethereumAccounts(id)

        store.delete(id)
        assertNull(reads.cachedEthereumAccounts(id), "A deleted wallet drops the kept view.")
        assertNull(reads.ethereumAccounts(id), "A deleted wallet has no view.")
    }

    @Test
    fun aSingleKeyViewHoldsTheAddressOfItsKey() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val id = store.createSingleKey("KEY", privateKey.copyOf(), nowMs = 1L)

        val view = requireNotNull(SessionReads(session).ethereumAccounts(id))

        assertEquals(WalletKind.SINGLE_KEY, view.meta.kind)
        assertEquals(listOf(AccountAddresses.addressOfPrivateKey(privateKey)), view.addresses)
    }

    @Test
    fun lockingWipesTheKeptEthereumView() {
        val id = newWallet("FIRST")
        val holder = VaultHolder(Vault(VaultLayout(tmp.root), keys, FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))) { SessionReads(it) }
        val epoch = holder.unlockEpoch()
        assertTrue(holder.adopt(VaultSession(VaultLayout(tmp.root), keys, key.copyOf()), epoch))
        val reads = requireNotNull(holder.sessionReads())
        reads.ethereumAccounts(id)
        assertNotNull(reads.cachedEthereumAccounts(id))

        holder.lock()
        assertNull(reads.cachedEthereumAccounts(id), "Locking wipes the view the old reads kept.")
        assertNull(holder.sessionReads(), "The next unlock starts with no reads.")
    }

    private fun newWallet(name: String): String = store.create(
        name = name,
        deviceGenerated = false,
        words = words,
        passphrase = name,
        nowMs = 1L,
    )
}
