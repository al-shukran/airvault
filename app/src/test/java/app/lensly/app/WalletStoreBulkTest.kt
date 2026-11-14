package app.lensly.app

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WalletStoreBulkTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val cap = BulkCap.DEFAULT.accounts
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(): String = store.create(
        name = "Test",
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    @Test
    fun aRunRaisesTheAccountCountByItsCount() {
        val id = newWallet()

        val updated = assertNotNull(store.addAccounts(id, 10, cap))

        assertEquals(11, updated.accountCount)
        assertEquals(11, store.meta(id)?.accountCount)
    }

    @Test
    fun theSavedAccountsEqualSingleAccountDerivation() {
        val id = newWallet()
        store.addAccounts(id, 20, cap)

        val single = HdWallet(Mnemonic.toSeed(words))
        assertEquals((0..20).map { single.address(it) }, store.addresses(id, 21))
    }

    @Test
    fun aRunContinuesAfterSingleAccounts() {
        val id = newWallet()
        store.addAccount(id)
        store.addAccount(id)

        // One account from creation, two single accounts, then a run of three: six accounts in all.
        val updated = assertNotNull(store.addAccounts(id, 3, cap))

        assertEquals(6, updated.accountCount)
        val single = HdWallet(Mnemonic.toSeed(words))
        assertEquals((0..5).map { single.address(it) }, store.addresses(id, 6))
    }

    @Test
    fun theProgressCountsEveryDerivedAccount() {
        val id = newWallet()
        val reported = mutableListOf<Int>()

        store.addAccounts(id, 4, cap) { reported += it }

        assertEquals(listOf(1, 2, 3, 4), reported)
    }

    @Test
    fun aCountAboveTheCapSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addAccounts(id, cap + 1, cap) }

        assertEquals(1, store.meta(id)?.accountCount)
    }

    @Test
    fun aRunAboveAHundredIsRefusedWhileTheCapIsAHundred() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addAccounts(id, 101, cap = 100) }

        assertEquals(1, store.meta(id)?.accountCount)
    }

    @Test
    fun aRunOfAHundredIsSavedUnderTheHundredCap() {
        val id = newWallet()

        val updated = assertNotNull(store.addAccounts(id, 100, cap = BulkCap.HUNDRED.accounts))

        assertEquals(101, updated.accountCount)
        val single = HdWallet(Mnemonic.toSeed(words))
        assertEquals((0..100).map { single.address(it) }, store.addresses(id, 101))
    }

    @Test
    fun aCountBelowOneSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addAccounts(id, 0, cap) }

        assertEquals(1, store.meta(id)?.accountCount)
    }

    @Test
    fun aRunForAWalletThatDoesNotExistIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            store.addAccounts("11111111-2222-3333-4444-555555555555", 1, cap)
        }
    }

    @Test
    fun aRunSavesNothingWhenTheAccountCountChangesWhileItDerives() {
        val id = newWallet()

        // A single account is added in the middle of the run, so the run's start index is stale.
        val saved = store.addAccounts(id, 5, cap) { derived -> if (derived == 2) store.addAccount(id) }

        assertNull(saved)
        assertEquals(2, store.meta(id)?.accountCount)
    }
}
