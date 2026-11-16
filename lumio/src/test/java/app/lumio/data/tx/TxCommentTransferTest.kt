package app.lumio.data.tx

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The comment export and restore: a round trip into another database, the newer-wins rule, and refusal of a damaged or foreign export. */
@RunWith(RobolectricTestRunner::class)
class TxCommentTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val opened = ArrayList<LumioDatabase>()
    private val first = "EVM:1:0x" + "1a".repeat(32) + ":native"
    private val second = "EVM:1:0x" + "2b".repeat(32) + ":native"

    @After
    fun closeAll() {
        opened.forEach { it.close() }
    }

    private fun keys(seed: Int = 1): TxKeys =
        TxKeys.from(VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + seed).toByte() }))

    private fun store(name: String, seed: Int = 1): TxStore {
        val database = LumioDatabase.open(context, File(tmp.root, name)).also { opened += it }
        return TxStore(database.txs(), keys(seed))
    }

    @Test
    fun aCommentSurvivesAnExportAndARestoreIntoAnotherDatabase() = runBlocking {
        val source = store("source.db")
        source.setComment(first, "Rent for March", 5_000L)
        source.setComment(second, "Gift", 6_000L)
        val sealed = TxCommentTransfer.export(source, keys())

        val target = store("target.db")

        assertEquals(2, TxCommentTransfer.restore(target, keys(), sealed))
        assertEquals(TxNote(first, "Rent for March", 5_000L), target.comment(first))
        assertEquals(setOf(first, second), target.comments().map { it.txId }.toSet())
    }

    @Test
    fun restoringTheSameExportTwiceChangesNothingTheSecondTime() = runBlocking {
        val source = store("source.db")
        source.setComment(first, "a note", 1L)
        val sealed = TxCommentTransfer.export(source, keys())
        val target = store("target.db")

        assertEquals(1, TxCommentTransfer.restore(target, keys(), sealed))
        assertEquals(0, TxCommentTransfer.restore(target, keys(), sealed))
    }

    @Test
    fun aNewerLocalCommentIsKeptAndAnOlderOneIsReplaced() = runBlocking {
        val source = store("source.db")
        source.setComment(first, "from the export", 5_000L)
        source.setComment(second, "from the export", 5_000L)
        val sealed = TxCommentTransfer.export(source, keys())
        val target = store("target.db")
        target.setComment(first, "newer local", 9_000L)
        target.setComment(second, "older local", 1_000L)

        assertEquals(1, TxCommentTransfer.restore(target, keys(), sealed))

        assertEquals("newer local", target.comment(first)?.text)
        assertEquals("from the export", target.comment(second)?.text)
    }

    @Test
    fun aTamperedExportIsRefusedAndNothingIsRestored() = runBlocking {
        val source = store("source.db")
        source.setComment(first, "a note", 1L)
        val sealed = TxCommentTransfer.export(source, keys())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        val target = store("target.db")

        assertFailsWith<CommentImportException> { TxCommentTransfer.restore(target, keys(), sealed) }
        assertEquals(emptyList(), target.comments())
    }

    @Test
    fun anExportUnderAnotherVaultKeyIsRefused() = runBlocking {
        val source = store("source.db")
        source.setComment(first, "a note", 1L)
        val sealed = TxCommentTransfer.export(source, keys(seed = 1))
        val target = store("target.db")

        assertFailsWith<CommentImportException> { TxCommentTransfer.restore(target, keys(seed = 2), sealed) }
        assertNull(target.comment(first))
    }

    @Test
    fun aBlankCommentClearsTheNoteRatherThanStoringAnEmptyOne() = runBlocking {
        val s = store("blank.db")
        s.setComment(first, "a note", 1L)
        s.setComment(first, "   ", 2L)

        assertNull(s.comment(first))
    }
}
