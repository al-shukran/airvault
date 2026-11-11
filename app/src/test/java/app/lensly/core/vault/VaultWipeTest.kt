package app.lensly.core.vault

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A vault with every kind of file it writes: header, wrapped key, failure state, a wallet, and a vault record. */
    private fun populated(): VaultLayout {
        val layout = VaultLayout(File(tmp.root, "vault"))
        layout.root.mkdirs()
        layout.header.writeBytes(byteArrayOf(1, 2, 3))
        layout.vaultKey.writeBytes(byteArrayOf(4, 5, 6))
        File(layout.root, "failures.bin").writeBytes(byteArrayOf(7))
        File(layout.root, "failures.bak").writeBytes(byteArrayOf(8))
        val session = VaultSession(layout, ByteArray(32) { 9 })
        val wallet = session.createWallet()
        session.putRecord(wallet, "secret", byteArrayOf(10))
        session.putVaultRecord("settings", byteArrayOf(11))
        session.close()
        return layout
    }

    @Test
    fun aWipeLeavesNoVaultFiles() {
        val layout = populated()
        assertTrue(layout.exists())

        wipeVault(layout) {}

        assertFalse(layout.exists())
        assertFalse(layout.root.exists())
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun theKeysGoBeforeAnyFile() {
        val layout = populated()
        var vaultStillThereWhenKeysWentAway = false

        wipeVault(layout) { vaultStillThereWhenKeysWentAway = layout.exists() }

        assertTrue(vaultStillThereWhenKeysWentAway)
        assertFalse(layout.root.exists())
    }
}
