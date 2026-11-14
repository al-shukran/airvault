package app.lensly.app

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecoyVaultTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun decoy(folder: String, seed: Int): VaultSession =
        VaultSession(VaultLayout(File(tmp.root, folder)), keys, ByteArray(32) { (it * seed + 5).toByte() }, isDecoy = true)

    /** The seed words of the one wallet [session] holds after the populate step ran. */
    private fun populatedWords(session: VaultSession): List<String> {
        DecoyVault.populate(session)
        val store = WalletStore(session)
        val wallet = store.list().single()
        assertEquals(DecoyVault.WALLET_NAME, wallet.name)
        return (store.secret(wallet.id) as WalletSecret.Mnemonic).words
    }

    @Test
    fun thePopulateStepLeavesOneNamedWalletWithAWordSeed() {
        val session = decoy("first", seed = 11)

        val words = populatedWords(session)

        assertEquals(24, words.size)
        assertEquals(1, WalletStore(session).list().size)
        session.close()
    }

    @Test
    fun eachDecoyGetsItsOwnRandomSeed() {
        val first = decoy("first", seed = 11)
        val second = decoy("second", seed = 13)

        assertNotEquals(populatedWords(first), populatedWords(second))
        first.close()
        second.close()
    }

    @Test
    fun theSeedIsNotEmpty() {
        val session = decoy("seed", seed = 17)

        assertTrue(populatedWords(session).all { it.isNotBlank() })
        session.close()
    }
}
