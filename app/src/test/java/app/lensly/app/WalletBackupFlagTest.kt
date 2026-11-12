package app.lensly.app

import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The seed backed-up flag: what a create, a restore and an import store, how a record from before the flag reads, and how the draft
 * tracks the flag through the create ceremony.
 */
class WalletBackupFlagTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    @Test
    fun aWalletIsBackedUpUnlessItsCreateSaysOtherwise() {
        val backedUp = store.create(name = "A", deviceGenerated = false, words = words, passphrase = null, nowMs = 1L)
        val notYet = store.create(
            name = "B",
            deviceGenerated = true,
            words = words,
            passphrase = null,
            nowMs = 2L,
            seedBackedUp = false,
        )

        assertTrue(store.meta(backedUp)!!.seedBackedUp)
        assertFalse(store.meta(notYet)!!.seedBackedUp)
        assertEquals(listOf(notYet), store.list().filterNot { it.seedBackedUp }.map { it.id })
    }

    @Test
    fun markingABackupKeepsTheRestOfTheWalletAndAFurtherMarkChangesNothing() {
        val id = store.create(name = "B", deviceGenerated = true, words = words, passphrase = null, nowMs = 2L, seedBackedUp = false)
        val before = store.meta(id)!!

        val marked = store.markSeedBackedUp(id)

        assertTrue(marked.seedBackedUp)
        assertTrue(store.meta(id)!!.seedBackedUp)
        assertEquals(before.name, marked.name)
        assertEquals(before.accountCount, marked.accountCount)
        assertEquals(before.masterFingerprintHex, marked.masterFingerprintHex)
        assertEquals(before.createdAtMs, marked.createdAtMs)
        val again = store.markSeedBackedUp(id)
        assertTrue(again.seedBackedUp)
        assertEquals(marked.masterFingerprintHex, again.masterFingerprintHex)
    }

    @Test
    fun aSingleKeyImportCountsAsBackedUp() {
        val id = store.createSingleKey(name = "K", privateKey = ByteArray(32) { 1 }, nowMs = 3L)

        assertTrue(store.meta(id)!!.seedBackedUp)
    }

    @Test
    fun theFlagRoundTripsThroughTheMetaRecord() {
        val id = store.create(name = "R", deviceGenerated = true, words = words, passphrase = null, nowMs = 4L, seedBackedUp = false)
        val meta = store.meta(id)!!

        assertFalse(WalletRecords.decodeMeta(id, WalletRecords.encodeMeta(meta)).seedBackedUp)
        assertTrue(WalletRecords.decodeMeta(id, WalletRecords.encodeMeta(meta.copy(seedBackedUp = true))).seedBackedUp)
    }

    @Test
    fun aVersionTwoMetaRecordWithoutTheFlagReadsAsBackedUp() {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeByte(2)
                out.writeByte(WalletKind.SINGLE_KEY.wire)
                out.writeUTF("OLD")
                out.writeLong(5L)
                out.writeBoolean(false)
                out.writeInt(1)
            }
        }.toByteArray()

        val meta = WalletRecords.decodeMeta("old", bytes)

        assertEquals(WalletKind.SINGLE_KEY, meta.kind)
        assertTrue(meta.seedBackedUp)
    }

    @Test
    fun aRestoredWordListIsBackedUpAndACreatedOneIsNotUntilItsQuizPasses() {
        val draft = WalletDraft()

        draft.setTypedWords(words)
        assertTrue(draft.backedUp)

        draft.startCeremony()
        draft.skipCeremony()
        assertFalse(draft.backedUp)
        draft.markBackedUp()
        assertTrue(draft.backedUp)

        draft.wipe()
        assertFalse(draft.backedUp)
    }
}
