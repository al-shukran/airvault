package app.lensly.app

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wallet is listed only once its first accounts are written. A crash between its metadata and its first accounts leaves an incomplete
 * wallet: it is not listed, and it is removed when the vault next opens.
 */
class WalletCompletionTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 21).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    @Test
    fun aCreatedWalletIsListedWithItsFirstAccounts() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)

        val listed = store.list().single()
        assertEquals(id, listed.id)
        assertEquals(true, listed.complete)
        assertEquals(1, listed.accountCount)
    }

    @Test
    fun aWalletCrashedBetweenItsMetaAndItsFirstAccountsIsNotListed() {
        val id = crashedWallet()

        assertFalse(store.list().any { it.id == id })
        assertEquals(false, store.meta(id)?.complete)
    }

    @Test
    fun openingTheVaultRemovesACrashedWalletAndKeepsTheOthers() {
        val crashed = crashedWallet()
        val created = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 2L)

        store.dropIncomplete()

        assertEquals(listOf(created), session.listWallets())
        assertNull(store.meta(crashed))
    }

    @Test
    fun openingTheVaultKeepsAWalletWhoseMetaCannotBeRead() {
        val id = session.createWallet()
        session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName("Earlier"))
        // Version 4 of the meta record, before the complete flag: it no longer reads, so it is unreadable, not incomplete.
        session.putRecord(id, WalletRecords.META, byteArrayOf(4))

        store.dropIncomplete()

        assertEquals(listOf(id), session.listWallets())
    }

    @Test
    fun openingTheVaultKeepsACompleteWalletWhoseBaseNameNoLongerReads() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)
        // A name the Display Name rule refuses, as a '?' name saved before the rule was enforced: its base name no longer reads.
        session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName("Bad?"))

        store.dropIncomplete()

        assertEquals(listOf(id), session.listWallets())
        assertNull(store.meta(id))
    }

    /** The state a crash leaves behind: the secret, the name and an incomplete meta record, and no first accounts. */
    private fun crashedWallet(): String {
        val id = session.createWallet()
        session.putRecord(id, WalletRecords.SECRET, WalletRecords.encodeSecret(DEMO_WORDS, null))
        session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName("Crashed"))
        val meta = WalletMeta(
            id = id,
            name = "Crashed",
            createdAtMs = 1L,
            deviceGenerated = false,
            accountCount = 0,
            masterFingerprintHex = "00000000",
            accountKeys = emptyList(),
            complete = false,
        )
        session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(meta))
        return id
    }

    private companion object {
        val DEMO_WORDS = (
            "supply excess unit online visa snow include grief sketch " +
                "grace rural near leaf finish jewel embark elite make venture enroll vacuum worth over pioneer"
            ).split(" ")
    }
}
