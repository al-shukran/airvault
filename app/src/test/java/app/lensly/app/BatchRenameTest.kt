package app.lensly.app

import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Batch Rename and batch reset of account names. The selected accounts change, the others keep what they had, and the names
 * survive a lock. A batch is one store write, so a refused or failed batch saves nothing.
 */
class BatchRenameTest {

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

    /** A wallet named "Seed" with [accounts] accounts, indices 0 until [accounts]. */
    private fun newWallet(accounts: Int): String {
        val id = store.create(name = "Seed", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        repeat(accounts - 1) { store.addAccount(id) }
        return id
    }

    /** The Display Name of each account 0 until [count], or null where the account keeps its Auto-Name. */
    private fun names(id: String, count: Int): List<String?> = (0 until count).map { store.accountName(id, it) }

    @Test
    fun aBatchRenameNamesEverySelectedAccountFromTheBaseAndItsIndex() {
        val id = newWallet(5)

        store.renameAccounts(id, listOf(1, 3, 4), "Rent")

        assertEquals(listOf(null, "Rent 1", null, "Rent 3", "Rent 4"), names(id, 5))
    }

    @Test
    fun aBatchRenameTrimsTheBaseAndTakesTheSelectionInAnyOrder() {
        val id = newWallet(4)

        store.renameAccounts(id, listOf(3, 0, 3), "  Rent  ")

        assertEquals(listOf("Rent 0", null, null, "Rent 3"), names(id, 4))
    }

    @Test
    fun aBatchRenameLeavesTheUnselectedAccountsAsTheyWere() {
        val id = newWallet(5)
        store.renameAccount(id, 0, "Main")
        store.renameAccount(id, 2, "Savings")

        store.renameAccounts(id, listOf(1, 3), "Rent")

        assertEquals(listOf("Main", "Rent 1", "Savings", "Rent 3", null), names(id, 5))
    }

    @Test
    fun aBatchRenameReplacesTheNameOfASelectedAccountThatHadOne() {
        val id = newWallet(3)
        store.renameAccount(id, 1, "Old")

        store.renameAccounts(id, listOf(1), "New")

        assertEquals(listOf(null, "New 1", null), names(id, 3))
    }

    @Test
    fun aBatchRenameKeepsTheWalletNameAndEveryAddress() {
        val id = newWallet(3)
        val addresses = store.addresses(id, 3)

        store.renameAccounts(id, listOf(0, 2), "Rent")

        assertEquals("Seed", store.meta(id)?.name)
        assertEquals(addresses, store.addresses(id, 3))
    }

    @Test
    fun aBatchResetReturnsTheSelectedAccountsToTheirAutoNames() {
        val id = newWallet(4)
        store.renameAccounts(id, listOf(0, 1, 2, 3), "Rent")

        store.resetAccountNames(id, listOf(1, 2))

        assertEquals(listOf("Rent 0", null, null, "Rent 3"), names(id, 4))
        assertEquals("Seed 1", autoAccountName(assertNotNull(store.meta(id)).autoNameBase, 1))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(1)))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(2)))
    }

    @Test
    fun aBatchResetLeavesTheUnselectedAccountsAsTheyWere() {
        val id = newWallet(4)
        store.renameAccounts(id, listOf(0, 1, 2, 3), "Rent")

        store.resetAccountNames(id, listOf(2))

        assertEquals(listOf("Rent 0", "Rent 1", null, "Rent 3"), names(id, 4))
    }

    @Test
    fun theBatchNamesSurviveALockAndAReopen() {
        val id = newWallet(4)
        store.renameAccounts(id, listOf(0, 2), "Rent")

        reopen()

        assertEquals(listOf("Rent 0", null, "Rent 2", null), names(id, 4))
    }

    @Test
    fun aBatchResetSurvivesALockAndAReopen() {
        val id = newWallet(3)
        store.renameAccounts(id, listOf(0, 1, 2), "Rent")
        store.resetAccountNames(id, listOf(0, 2))

        reopen()

        assertEquals(listOf(null, "Rent 1", null), names(id, 3))
    }

    @Test
    fun anEmptyBaseNameIsRefusedAndNothingIsRenamed() {
        val id = newWallet(3)
        store.renameAccount(id, 0, "Main")

        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, listOf(0, 1), "") }
        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, listOf(0, 1), "   ") }

        assertEquals(listOf("Main", null, null), names(id, 3))
    }

    @Test
    fun aBaseNameWhoseGeneratedNameWouldBeTooLongIsRefused() {
        val id = newWallet(13)

        // The largest selected index is 12, two digits, so the base may take 29 characters and no more.
        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, listOf(1, 12), "x".repeat(30)) }
        assertNull(store.accountName(id, 1))
        assertNull(store.accountName(id, 12))

        store.renameAccounts(id, listOf(1, 12), "x".repeat(29))
        val longest = assertNotNull(store.accountName(id, 12))
        assertEquals("x".repeat(29) + " 12", longest)
        assertEquals(DISPLAY_NAME_MAX_LENGTH, longest.length)
    }

    @Test
    fun aBaseNameWithAControlCharacterIsRefused() {
        val id = newWallet(2)

        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, listOf(0), "a\nb") }

        assertEquals(listOf(null, null), names(id, 2))
    }

    @Test
    fun theBatchBaseLimitLeavesRoomForTheIndex() {
        assertEquals(30, batchBaseMaxLength(listOf(0, 4)))
        assertEquals(29, batchBaseMaxLength(listOf(12)))
        assertTrue(isValidBatchBase("x".repeat(30), listOf(4)))
        assertFalse(isValidBatchBase("x".repeat(31), listOf(4)))
        assertFalse(isValidBatchBase("a\u0000b", listOf(4)))
    }

    @Test
    fun anEmptySelectionIsRefused() {
        val id = newWallet(2)

        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, emptyList(), "Rent") }
        assertFailsWith<IllegalArgumentException> { store.resetAccountNames(id, emptyList()) }
    }

    @Test
    fun aSelectionWithAnAccountThatDoesNotExistSavesNothing() {
        val id = newWallet(3)
        store.renameAccount(id, 0, "Main")

        assertFailsWith<IllegalArgumentException> { store.renameAccounts(id, listOf(0, 1, 7), "Rent") }
        assertFailsWith<IllegalArgumentException> { store.resetAccountNames(id, listOf(0, 7)) }

        assertEquals(listOf("Main", null, null), names(id, 3))
    }

    @Test
    fun aWriteThatFailsPartWayPutsBackEveryAccountItChanged() {
        val id = newWallet(4)
        store.renameAccount(id, 1, "Old")
        // The record of account 3 is a non-empty directory, so its write fails after account 1 has been written.
        val blocker = File(VaultLayout(tmp.root).walletDir(id), "account-3.bin").apply { mkdirs() }
        File(blocker, "keep").writeText("x")

        assertFailsWith<IOException> { store.renameAccounts(id, listOf(1, 3), "Rent") }

        assertEquals(listOf(null, "Old", null, null), names(id, 4))
    }
}
