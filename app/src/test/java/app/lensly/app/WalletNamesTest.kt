package app.lensly.app

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.web3j.utils.Numeric

/**
 * Display Names and Auto-Names of wallets and accounts: the names a wallet gives its accounts at creation, the names the
 * owner gives, and their survival across a lock. Reopening the vault with the same key is the unlock that follows a lock.
 */
class WalletNamesTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val key: ByteArray =
        Numeric.hexStringToByteArray("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318")
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    /** A fresh copy each time, because a closed session zeroes the key it was given. */
    private fun vaultKey(): ByteArray = ByteArray(32) { (it + 7).toByte() }

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, vaultKey())
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    /** Locks the vault and unlocks it again, and returns a store on the new session. */
    private fun reopen(): WalletStore {
        session.close()
        session = VaultSession(VaultLayout(tmp.root), keys, vaultKey())
        store = WalletStore(session)
        return store
    }

    private fun newWallet(): String =
        store.create(name = "Seed", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)

    @Test
    fun aNewWalletNamesItsAccountsFromItsNameAtCreation() {
        val id = newWallet()
        val meta = assertNotNull(store.meta(id))

        assertEquals("Seed", meta.name)
        assertEquals("Seed", meta.autoNameBase)
        assertEquals("Seed 0", autoAccountName(meta.autoNameBase, 0))
        assertNull(store.accountName(id, 0))
        assertEquals(emptyMap(), store.accountNames(id, 1))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(0)))
    }

    @Test
    fun aSingleKeyWalletNamesItsAccountFromItsNameAtCreation() {
        val id = store.createSingleKey(name = "Imported", privateKey = key, nowMs = 5L)

        assertEquals("Imported", assertNotNull(store.meta(id)).autoNameBase)
        assertEquals("Imported 0", autoAccountName(assertNotNull(store.meta(id)).autoNameBase, 0))
    }

    @Test
    fun aRenamedWalletKeepsItsNameAfterALock() {
        val id = newWallet()
        val before = assertNotNull(store.meta(id))

        store.renameWallet(id, "  Savings  ")
        val after = assertNotNull(reopen().meta(id))

        assertEquals("Savings", after.name)
        assertEquals(before.masterFingerprintHex, after.masterFingerprintHex)
        assertEquals(before.accountCount, after.accountCount)
    }

    @Test
    fun renamingTheWalletKeepsTheAutoNamesAfterALock() {
        val id = newWallet()
        store.addAccount(id)

        store.renameWallet(id, "Vault")
        val meta = assertNotNull(reopen().meta(id))

        assertEquals("Vault", meta.name)
        assertEquals("Seed", meta.autoNameBase)
        assertEquals("Seed 1", autoAccountName(meta.autoNameBase, 1))
    }

    @Test
    fun aWalletWithoutABaseNameUsesItsCurrentNameUntilItIsRenamed() {
        val id = newWallet()
        session.deleteRecord(id, WalletRecords.BASE_NAME)
        assertEquals("Seed", assertNotNull(store.meta(id)).autoNameBase)

        store.renameWallet(id, "Vault")
        val meta = assertNotNull(reopen().meta(id))

        assertEquals("Vault", meta.name)
        assertEquals("Seed", meta.autoNameBase)
    }

    @Test
    fun aRenamedAccountKeepsItsNameAfterALockAndTheOtherAccountsKeepTheirAutoName() {
        val id = newWallet()
        store.addAccount(id)

        store.renameAccount(id, 1, "Rent")
        val reopened = reopen()

        assertEquals("Rent", reopened.accountName(id, 1))
        assertNull(reopened.accountName(id, 0))
        assertEquals(mapOf(1 to "Rent"), reopened.accountNames(id, 2))
    }

    @Test
    fun renamingAnAccountKeepsItsAddressAndTheWalletNameStays() {
        val id = newWallet()
        store.addAccount(id)
        val addresses = store.addresses(id, 2)

        store.renameAccount(id, 0, "Main")

        assertEquals(addresses, store.addresses(id, 2))
        assertEquals("Seed", store.meta(id)?.name)
    }

    @Test
    fun renamingTheWalletNeverRenamesItsAccounts() {
        val id = newWallet()
        store.addAccount(id)
        store.renameAccount(id, 0, "Main")

        store.renameWallet(id, "Vault")

        assertEquals("Vault", store.meta(id)?.name)
        assertEquals(mapOf(0 to "Main"), store.accountNames(id, 2))
    }

    @Test
    fun resetReturnsAnAccountToItsAutoNameAfterALock() {
        val id = newWallet()
        store.renameAccount(id, 0, "Main")

        store.resetAccountName(id, 0)

        assertNull(reopen().accountName(id, 0))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(0)))
    }

    @Test
    fun invalidNamesAreRefusedAndThePreviousNameStays() {
        val id = newWallet()
        store.renameAccount(id, 0, "Main")

        assertFailsWith<IllegalArgumentException> { store.renameAccount(id, 0, "   ") }
        assertFailsWith<IllegalArgumentException> { store.renameAccount(id, 0, "x".repeat(DISPLAY_NAME_MAX_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { store.renameWallet(id, "a\nb") }

        assertEquals("Main", store.accountName(id, 0))
        assertEquals("Seed", store.meta(id)?.name)
    }

    @Test
    fun anAccountThatDoesNotExistCannotBeNamedOrReset() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.renameAccount(id, 1, "Rent") }
        assertFailsWith<IllegalArgumentException> { store.resetAccountName(id, 1) }
    }

    @Test
    fun aDamagedNameRecordShowsTheAutoName() {
        val id = newWallet()
        session.putRecord(id, WalletRecords.accountRecord(0), byteArrayOf(9, 0, 1, 'x'.code.toByte()))

        assertNull(store.accountName(id, 0))
    }

    @Test
    fun aNameRecordRoundTrips() {
        assertEquals("Rent", WalletRecords.decodeName(WalletRecords.encodeName("Rent")))
    }
}
