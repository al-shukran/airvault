package app.lensly.core.vault

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The batched record read: the same bytes as one [VaultSession.getRecord] per name, with the absent names left out. */
class VaultSessionGetRecordsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 5 + 2).toByte() })

    @Test
    fun aBatchReadsEachRecordAsASingleReadWould() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "meta", byteArrayOf(1, 2, 3))
        session.putRecord(wallet, "account-0", byteArrayOf(4))
        session.putRecord(wallet, "account-2", byteArrayOf(5, 6))

        val batch = session.getRecords(wallet, listOf("meta", "account-0", "account-1", "account-2"))

        assertEquals(setOf("meta", "account-0", "account-2"), batch.keys, "A record that is absent is left out.")
        for ((name, bytes) in batch) assertContentEquals(session.getRecord(wallet, name), bytes, name)
        session.close()
    }

    @Test
    fun aNameNoWalletFolderHoldsIsLeftOut() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "meta", byteArrayOf(9))

        val batch = session.getRecords(wallet, listOf("meta", "not-a-record", "pwk"))

        assertEquals(setOf("meta"), batch.keys)
        session.close()
    }

    @Test
    fun aMissingWalletGivesNothing() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "meta", byteArrayOf(9))
        session.deleteWallet(wallet)

        assertTrue(session.getRecords(wallet, listOf("meta")).isEmpty())
        assertNull(session.getRecord(wallet, "meta"))
        session.close()
    }

    @Test
    fun aBatchIsARead() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "meta", byteArrayOf(1))
        val before = session.writeCount

        session.getRecords(wallet, listOf("meta", "account-0"))

        assertEquals(before, session.writeCount, "Reading records is not a write.")
        session.close()
    }
}
