package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.Mnemonic
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's secret read for Show Secret, on real storage. The words of a Mnemonic wallet come back in a [Mnemonic] that the
 * secret owns, and clearing the secret zeroes them.
 */
class VaultRevealPortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"

    @Test
    fun aMnemonicWalletsWordsAreReadAsASecretThatWipesThem() = runBlocking {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it + 9).toByte() })
        val walletId = WalletStore(session).create(
            name = "Seed",
            deviceGenerated = false,
            words = words,
            passphrase = null,
            nowMs = 1L,
        )
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        val holder = VaultHolder(vault).also { it.adopt(session, it.unlockEpoch()) }

        val secret = assertIs<SecretLoad.Loaded>(VaultRevealPorts(holder, walletId).loadSecret()).secret
        assertEquals(Mnemonic.WORD_COUNT, secret.pageCount(RevealPart.WORDS))
        assertEquals("art", secret.page(RevealPart.WORDS, Mnemonic.WORD_COUNT - 1))

        secret.wipe()
        assertEquals(0, secret.pageCount(RevealPart.WORDS))
    }
}
