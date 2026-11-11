package app.lensly.app

import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultRecordPool
import app.lensly.core.vault.VaultSession
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Account and wallet profiles on real storage. A rename or a reset keeps the style of an account, a batch styles the selected
 * accounts in one write and nothing else, and the profiles are encrypted on disk and purged with their wallet.
 */
class ProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    /** A fresh copy each time, because a closed session zeroes the key it was given. */
    private fun vaultKey(): ByteArray = ByteArray(32) { (it + 9).toByte() }

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

    /** The profile of each account 0 until [count] of wallet [id]. */
    private fun profiles(id: String, count: Int): List<AccountProfile> = (0 until count).map { store.accountProfile(id, it) }

    /** Every field set, so a test can see each one survive. */
    private val full = AccountProfile(
        name = "Main",
        colour = ProfileColour.BLUE,
        icon = ProfileIcon.PIN,
        label = "Quarterly rent",
        group = "Home",
    )

    @Test
    fun aSavedProfileReadsBackWholeAfterALockAndAReopen() {
        val id = newWallet(2)

        store.saveAccountProfile(id, 1, full)

        reopen()
        assertEquals(full, store.accountProfile(id, 1))
    }

    @Test
    fun aSavedProfileTrimsItsNamesAndAnInvalidOneSavesNothing() {
        val id = newWallet(1)
        store.saveAccountProfile(id, 0, AccountProfile(name = "  Main  ", label = " Rent "))

        assertFailsWith<IllegalArgumentException> { store.saveAccountProfile(id, 0, AccountProfile(label = "x".repeat(17))) }
        assertFailsWith<IllegalArgumentException> { store.saveAccountProfile(id, 0, AccountProfile(group = "  ")) }

        assertEquals(AccountProfile(name = "Main", label = "Rent"), store.accountProfile(id, 0))
    }

    @Test
    fun aProfileForAnAccountThatDoesNotExistIsRefused() {
        val id = newWallet(1)

        assertFailsWith<IllegalArgumentException> { store.saveAccountProfile(id, 3, full) }

        assertNull(session.getRecord(id, WalletRecords.accountRecord(3)))
    }

    @Test
    fun anEmptyProfileRemovesTheRecord() {
        val id = newWallet(1)
        store.saveAccountProfile(id, 0, full)

        store.saveAccountProfile(id, 0, AccountProfile())

        assertNull(session.getRecord(id, WalletRecords.accountRecord(0)))
    }

    @Test
    fun aLegacyNameRecordKeepsItsNameWhenItsStyleChanges() {
        val id = newWallet(2)
        session.putRecord(id, WalletRecords.accountRecord(1), LegacyWalletRecords.accountName("Savings"))
        assertEquals("Savings", store.accountName(id, 1))

        store.changeAccountStyles(id, listOf(1), listOf(StyleChange.Colour(ProfileColour.RED)))

        assertEquals(AccountProfile(name = "Savings", colour = ProfileColour.RED), store.accountProfile(id, 1))
    }

    @Test
    fun renamingAnAccountKeepsItsStyle() {
        val id = newWallet(1)
        store.saveAccountProfile(id, 0, full)

        store.renameAccount(id, 0, "Savings")

        assertEquals(full.copy(name = "Savings"), store.accountProfile(id, 0))
    }

    @Test
    fun resettingANameKeepsTheStyleAndRemovesTheRecordOnlyWhenNothingElseIsLeft() {
        val id = newWallet(2)
        store.saveAccountProfile(id, 0, full)
        store.saveAccountProfile(id, 1, AccountProfile(name = "Only"))

        store.resetAccountName(id, 0)
        store.resetAccountName(id, 1)

        assertEquals(full.copy(name = null), store.accountProfile(id, 0))
        assertNull(session.getRecord(id, WalletRecords.accountRecord(1)))
    }

    @Test
    fun aBatchRenameAndABatchNameResetKeepTheStyleOfEveryAccount() {
        val id = newWallet(3)
        store.saveAccountProfile(id, 0, full.copy(name = null))
        store.saveAccountProfile(id, 2, AccountProfile(colour = ProfileColour.PINK))

        store.renameAccounts(id, listOf(0, 1, 2), "Rent")

        assertEquals(full.copy(name = "Rent 0"), store.accountProfile(id, 0))
        assertEquals(AccountProfile(name = "Rent 2", colour = ProfileColour.PINK), store.accountProfile(id, 2))

        store.resetAccountNames(id, listOf(0, 2))

        assertEquals(full.copy(name = null), store.accountProfile(id, 0))
        assertEquals(AccountProfile(colour = ProfileColour.PINK), store.accountProfile(id, 2))
    }

    @Test
    fun aColourAppliesToTheSelectedAccountsOnly() {
        val id = newWallet(4)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Main"))

        store.changeAccountStyles(id, listOf(1, 3), listOf(StyleChange.Colour(ProfileColour.GREEN)))

        assertEquals(
            listOf(
                AccountProfile(name = "Main"),
                AccountProfile(colour = ProfileColour.GREEN),
                AccountProfile(),
                AccountProfile(colour = ProfileColour.GREEN),
            ),
            profiles(id, 4),
        )
    }

    @Test
    fun theChangesOfABatchApplyInOrderAndNeverTouchTheDisplayName() {
        val id = newWallet(2)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Main", colour = ProfileColour.RED))

        store.changeAccountStyles(
            id,
            listOf(0, 1),
            listOf(
                StyleChange.Colour(ProfileColour.TEAL),
                StyleChange.Icon(ProfileIcon.BELL),
                StyleChange.Label("Rent"),
                StyleChange.Group("Home"),
                // A later change to the same field wins.
                StyleChange.Colour(ProfileColour.PINK),
            ),
        )

        assertEquals(
            AccountProfile(name = "Main", colour = ProfileColour.PINK, icon = ProfileIcon.BELL, label = "Rent", group = "Home"),
            store.accountProfile(id, 0),
        )
        assertEquals(
            AccountProfile(colour = ProfileColour.PINK, icon = ProfileIcon.BELL, label = "Rent", group = "Home"),
            store.accountProfile(id, 1),
        )
    }

    @Test
    fun aLabelAndAGroupCanBeClearedAndAResetReturnsTheStyleToItsDefaults() {
        val id = newWallet(2)
        store.saveAccountProfile(id, 0, full)
        store.saveAccountProfile(id, 1, full)

        store.changeAccountStyles(id, listOf(0), listOf(StyleChange.Label(null), StyleChange.Group(null)))
        assertEquals(full.copy(label = null, group = null), store.accountProfile(id, 0))

        store.changeAccountStyles(id, listOf(0, 1), listOf(StyleChange.Reset))
        // The Display Name stays, and the other fields are back to their defaults.
        assertEquals(AccountProfile(name = "Main"), store.accountProfile(id, 0))
        assertEquals(AccountProfile(name = "Main"), store.accountProfile(id, 1))
    }

    @Test
    fun aBatchWithAnInvalidChangeSavesNothing() {
        val id = newWallet(2)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Main"))

        assertFailsWith<IllegalArgumentException> {
            store.changeAccountStyles(
                id,
                listOf(0, 1),
                listOf(StyleChange.Colour(ProfileColour.RED), StyleChange.Label("  ")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.changeAccountStyles(id, listOf(0, 1), listOf(StyleChange.Group("g".repeat(17))))
        }

        assertEquals(listOf(AccountProfile(name = "Main"), AccountProfile()), profiles(id, 2))
    }

    @Test
    fun aBatchWithAnEmptySelectionNoChangeOrAMissingAccountSavesNothing() {
        val id = newWallet(3)
        store.saveAccountProfile(id, 0, AccountProfile(name = "Main"))

        assertFailsWith<IllegalArgumentException> { store.changeAccountStyles(id, emptyList(), listOf(StyleChange.Reset)) }
        assertFailsWith<IllegalArgumentException> { store.changeAccountStyles(id, listOf(0), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            store.changeAccountStyles(id, listOf(0, 7), listOf(StyleChange.Colour(ProfileColour.RED)))
        }

        assertEquals(listOf(AccountProfile(name = "Main"), AccountProfile(), AccountProfile()), profiles(id, 3))
    }

    @Test
    fun aBatchWriteThatFailsPartWayPutsBackEveryAccountItChanged() {
        val id = newWallet(4)
        store.saveAccountProfile(id, 1, AccountProfile(name = "Old"))
        // The record of account 3 is a non-empty directory, so its write fails after account 1 has been written.
        val blocker = VaultRecordPool.file(VaultLayout(tmp.root), id, "account-3").apply { mkdirs() }
        File(blocker, "keep").writeText("x")

        assertFailsWith<IOException> {
            store.changeAccountStyles(id, listOf(1, 3), listOf(StyleChange.Colour(ProfileColour.RED)))
        }

        assertEquals(listOf(AccountProfile(), AccountProfile(name = "Old"), AccountProfile(), AccountProfile()), profiles(id, 4))
    }

    @Test
    fun theProfilesAndNamesOfAWalletListOnlyTheAccountsThatHaveThem() {
        val id = newWallet(4)
        store.saveAccountProfile(id, 1, AccountProfile(colour = ProfileColour.RED))
        store.saveAccountProfile(id, 2, AccountProfile(name = "Savings", group = "Home"))

        assertEquals(
            mapOf(
                1 to AccountProfile(colour = ProfileColour.RED),
                2 to AccountProfile(name = "Savings", group = "Home"),
            ),
            store.accountProfiles(id, 4),
        )
        assertEquals(mapOf(2 to "Savings"), store.accountNames(id, 4))
    }

    @Test
    fun theProfilesOnDiskAreEncrypted() {
        val id = newWallet(1)
        store.saveAccountProfile(id, 0, full)
        store.saveWalletProfile(id, WalletProfile(label = "Family money", groups = listOf("Quarterly")))
        val layout = VaultLayout(tmp.root)

        for (file in listOf(VaultRecordPool.file(layout, id, "account-0"), VaultRecordPool.file(layout, id, "profile"))) {
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            for (plain in listOf("Quarterly rent", "Family money", "Quarterly", "Home")) {
                assertFalse(text.contains(plain), "${file.name} holds '$plain' in plain text")
            }
        }
    }

    @Test
    fun aWalletProfileReadsBackAfterALockAndAReopen() {
        val id = newWallet(1)
        val profile = WalletProfile(
            colour = ProfileColour.ORANGE,
            icon = ProfileIcon.HOME,
            label = "Everyday",
            groups = listOf("Work", "Family"),
        )

        store.saveWalletProfile(id, profile)

        reopen()
        assertEquals(profile, store.walletProfile(id))
    }

    @Test
    fun anEmptyWalletProfileRemovesItsRecordAndANewWalletHasNone() {
        val id = newWallet(1)
        assertEquals(WalletProfile(), store.walletProfile(id))

        store.saveWalletProfile(id, WalletProfile(label = "Temporary"))
        store.saveWalletProfile(id, WalletProfile())

        assertNull(session.getRecord(id, WalletRecords.PROFILE))
    }

    @Test
    fun aWalletProfileForAMissingWalletOrWithTooManyGroupsIsRefused() {
        val id = newWallet(1)

        assertFailsWith<IllegalArgumentException> {
            store.saveWalletProfile("00000000-0000-0000-0000-000000000000", WalletProfile(label = "Ghost"))
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveWalletProfile(id, WalletProfile(groups = List(WALLET_MAX_GROUPS + 1) { "g$it" }))
        }

        assertEquals(WalletProfile(), store.walletProfile(id))
    }

    @Test
    fun renamingAWalletKeepsItsProfileAndTheAccountsKeepTheirs() {
        val id = newWallet(1)
        store.saveWalletProfile(id, WalletProfile(colour = ProfileColour.GREEN, groups = listOf("Work")))
        store.saveAccountProfile(id, 0, full)

        store.renameWallet(id, "Renamed")

        assertEquals(WalletProfile(colour = ProfileColour.GREEN, groups = listOf("Work")), store.walletProfile(id))
        assertEquals(full, store.accountProfile(id, 0))
    }

    @Test
    fun deletingAWalletPurgesItsProfilesWithItsKey() {
        val kept = newWallet(1)
        val gone = newWallet(2)
        store.saveWalletProfile(gone, WalletProfile(label = "Doomed", groups = listOf("Work")))
        store.saveAccountProfile(gone, 1, full)
        store.saveWalletProfile(kept, WalletProfile(label = "Kept"))

        store.delete(gone)

        assertNull(session.getRecord(gone, WalletRecords.PROFILE))
        assertNull(session.getRecord(gone, WalletRecords.accountRecord(1)))
        assertFalse(VaultLayout(tmp.root).walletDir(gone).exists())
        reopen()
        assertEquals(WalletProfile(label = "Kept"), store.walletProfile(kept))
        assertEquals(WalletProfile(), store.walletProfile(gone))
    }
}
