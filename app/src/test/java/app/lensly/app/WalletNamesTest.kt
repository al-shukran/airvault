package app.lensly.app

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

/**
 * Display Names of wallets and accounts: the default names, the names the owner gives, and their survival across a lock.
 * Reopening the vault with the same key is the unlock that follows a lock.
 */
class WalletNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    /** A fresh copy each time, because a closed session zeroes the key it was given. */
    private fun vaultKey(): ByteArray = ByteArray(32) { (it + 7).toByte() }

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), vaultKey())
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    /** Locks the vault and unlocks it again, and returns a store on the new session. */
    private fun reopen(): WalletStore {
        session.close()
        session = VaultSession(VaultLayout(tmp.root), vaultKey())
        store = WalletStore(session)
        return store
    }

    private fun newWallet(): String =
        store.create(name = "Seed", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)

    @Test
    fun aNewWalletHasDefaultAccountNamesAndNoAccountRecords() {
        val id = newWallet()

        assertEquals("Seed", store.meta(id)?.name)
        assertNull(store.accountName(id, 0))
        assertEquals(emptyMap(), store.accountNames(id, 1))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(0)))
        assertEquals("Account 1", defaultAccountLabel(0))
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
    fun aRenamedAccountKeepsItsNameAfterALockAndTheOtherAccountsKeepTheirDefault() {
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
    fun resetReturnsAnAccountToItsDefaultNameAfterALock() {
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
    fun aDamagedNameRecordShowsTheDefaultName() {
        val id = newWallet()
        session.putRecord(id, WalletRecords.accountRecord(0), byteArrayOf(9, 0, 1, 'x'.code.toByte()))

        assertNull(store.accountName(id, 0))
    }

    @Test
    fun anAccountNameRecordRoundTrips() {
        assertEquals("Rent", WalletRecords.decodeAccountName(WalletRecords.encodeAccountName("Rent")))
    }
}
