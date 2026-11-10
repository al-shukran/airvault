package app.lensly.core.vault

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultRecordsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultKey = ByteArray(32) { (it * 3 + 1).toByte() }

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private fun open(key: ByteArray = vaultKey.copyOf()): VaultSession = VaultSession(VaultLayout(tmp.root), key)

    private fun recordFile(name: String): File = File(tmp.root, "vault-records/$name.bin")

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
        session.close()

        val other = open(ByteArray(32) { 9 })
        assertFailsWith<VaultException> { other.getVaultRecord("settings") }
        other.close()
    }

    @Test
    fun aVaultRecordCannotBeMovedUnderAnotherName() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(4, 4))
        recordFile("other").writeBytes(recordFile("settings").readBytes())

        // The AAD names the record, so a copy under another name fails authentication.
        assertFailsWith<VaultException> { session.getVaultRecord("other") }
        session.close()
    }

    @Test
    fun aDamagedVaultRecordFailsAuthentication() {
        val session = open()
        session.putVaultRecord("settings", byteArrayOf(5, 6, 7))
        val bytes = recordFile("settings").readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        recordFile("settings").writeBytes(bytes)

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
}
