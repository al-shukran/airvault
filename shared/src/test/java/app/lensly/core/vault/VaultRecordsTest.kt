package app.lensly.core.vault

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultRecordsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultKey = ByteArray(32) { (it * 3 + 1).toByte() }

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private val keys = MemoryKeystore()

    private fun open(key: ByteArray = vaultKey.copyOf()): VaultSession = VaultSession(VaultLayout(tmp.root), keys, key)

    @Test
    fun aMissingVaultRecordReadsAsNull() {
        val session = open()

        assertNull(session.getVaultRecord("settings"))
        session.close()
    }

    @Test
    fun aVaultRecordReadsBackAfterTheSessionReopens() {
        val first = open()
        first.putVaultRecord("settings", byteArrayOf(1, 2, 3))
        first.close()

        val reopened = open()
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.getVaultRecord("settings"))
        reopened.close()
    }

    @Test
    fun aVaultRecordReplacesItsEarlierValue() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(1))
        session.putVaultRecord("settings", byteArrayOf(2, 2))

        assertContentEquals(byteArrayOf(2, 2), session.getVaultRecord("settings"))
        session.close()
    }

    @Test
    fun aVaultRecordIsNotReadableWithAnotherVaultKey() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(7))
        val file = session.vaultRecordFileForTesting("settings")
        session.close()

        val other = open(ByteArray(32) { 9 })
        // The file name is keyed, so another vault key does not find the record at all.
        assertNull(other.getVaultRecord("settings"))
        // A copy planted under the other key's name fails authentication.
        other.vaultRecordFileForTesting("settings").writeBytes(file.readBytes())
        assertFailsWith<VaultException> { other.getVaultRecord("settings") }
        other.close()
    }

    @Test
    fun aVaultRecordCannotBeMovedUnderAnotherName() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(4, 4))
        session.vaultRecordFileForTesting("other")
            .writeBytes(session.vaultRecordFileForTesting("settings").readBytes())

        // The AAD names the record, so a copy under another name fails authentication.
        assertFailsWith<VaultException> { session.getVaultRecord("other") }
        session.close()
    }

    @Test
    fun aDamagedVaultRecordFailsAuthentication() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(5, 6, 7))
        val file = session.vaultRecordFileForTesting("settings")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertFailsWith<VaultException> { session.getVaultRecord("settings") }
        session.close()
    }

    @Test
    fun aVaultRecordIsNotAWallet() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(1))

        assertEquals(emptyList<String>(), session.listWallets())
        session.close()
    }

    @Test
    fun vaultRecordNamesAreValidated() {
        val session = open()

        assertFailsWith<IllegalArgumentException> { session.putVaultRecord("../escape", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { session.getVaultRecord("") }
        session.close()
    }

    @Test
    fun aClosedSessionRefusesVaultRecords() {
        val session = open()
        session.close()

        assertFailsWith<IllegalStateException> { session.putVaultRecord("settings", byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { session.getVaultRecord("settings") }
    }

    @Test
    fun theFileNameOfARecordHidesItsName() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(1))

        val name = session.vaultRecordFileForTesting("settings").name
        // A keyed hash: 64 hex characters, then ".bin". The record name does not appear in it.
        assertEquals(64 + ".bin".length, name.length)
        assertFalse(name.contains("settings"))
        session.close()
    }

    @Test
    fun theSameRecordNameIsAnotherFileUnderAnotherVaultKey() {
        val first = open()
        val second = open(ByteArray(32) { 9 })

        assertNotEquals(first.vaultRecordFileForTesting("settings"), second.vaultRecordFileForTesting("settings"))
        first.close()
        second.close()
    }

    @Test
    fun aRecordWrittenUnderItsPlainNameIsRenamedAndStillReads() {
        // The layout before keyed names: the file is named by the record, and the AAD is the same as now.
        val folder = File(tmp.root, "vault-records").apply { mkdirs() }
        val sealed = Aead.seal(vaultKey, "vault-record\u0000settings".toByteArray(Charsets.UTF_8), byteArrayOf(3, 3))
        File(folder, "settings.bin").writeBytes(byteArrayOf(1) + sealed)

        val session = open()
        session.migrateLegacyRecordNames()

        assertContentEquals(byteArrayOf(3, 3), session.getVaultRecord("settings"))
        assertFalse(File(folder, "settings.bin").exists())
        session.close()
    }

    @Test
    fun aWalletRecordIsOneOfTheNamesAWalletFolderHolds() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "meta", byteArrayOf(3))
        session.putRecord(wallet, "account-7", byteArrayOf(4))

        assertContentEquals(byteArrayOf(3), session.getRecord(wallet, "meta"))
        assertContentEquals(byteArrayOf(4), session.getRecord(wallet, "account-7"))
        assertNull(session.getRecord(wallet, "account-8"))
        // Any other name would add a file that the other folders do not have, so it is refused.
        assertFailsWith<IllegalArgumentException> { session.putRecord(wallet, "review-b", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { session.putRecord(wallet, "pwk", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { session.putRecord(wallet, "account-100", byteArrayOf(1)) }
        session.close()
    }

    @Test
    fun aRecordLongerThanItsCapacityIsRefused() {
        val session = open()
        val wallet = session.createWallet()

        assertFailsWith<IllegalArgumentException> { session.putRecord(wallet, "base-name", ByteArray(129)) }
        session.putRecord(wallet, "base-name", ByteArray(128))
        assertEquals(128, session.getRecord(wallet, "base-name")?.size)
        session.close()
    }

    @Test
    fun aDeletedRecordIsOverwrittenSoItsFileStays() {
        val session = open()
        val wallet = session.createWallet()
        session.putRecord(wallet, "profile", byteArrayOf(1, 2))
        val file = File(VaultLayout(tmp.root).walletDir(wallet), "profile.bin")
        val size = file.length()

        session.deleteRecord(wallet, "profile")

        assertNull(session.getRecord(wallet, "profile"))
        assertEquals(size, file.length())
        session.close()
    }
}
