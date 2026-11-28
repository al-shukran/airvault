package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A wallet made after a Duress unlock, before the promotion cleanup has run, must not grow the wallet store. */
class VaultPromotionWalletStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private val layout by lazy { VaultLayout(java.io.File(tmp.root, "vault"), java.io.File(tmp.root, "vault-photos")) }
    private val vault by lazy { Vault(layout, keys, clock, KDF) }

    @Test
    fun aWalletMadeBeforeThePromotionCleanupKeepsTheFolderCount() {
        val folders = promoteWithCleanupHeld(realWallets = 2)
        assertEquals(WALLET_SLOT_STEP, folders)

        val promoted = unlock(P2)
        val decoyWallets = promoted.listWallets()
        val made = List(3) { promoted.createWallet() }

        assertEquals(WALLET_SLOT_STEP, layout.wallets.list().orEmpty().size)
        assertEquals((decoyWallets + made).sorted(), promoted.listWallets())
        promoted.close()
    }

    @Test
    fun theCleanupAfterThoseWalletsKeepsTheCountAndTheNewWallets() {
        promoteWithCleanupHeld(realWallets = 2)
        val promoted = unlock(P2)
        val made = promoted.createWallet()
        promoted.close()

        vault.skipCleanupForTesting = false
        val finished = unlock(P2)
        runBlocking { vault.awaitCleanup() }

        assertFalse(layout.promotionMarker.exists())
        assertEquals(WALLET_SLOT_STEP, layout.wallets.list().orEmpty().size)
        assertTrue(made in finished.listWallets())
        finished.close()
    }

    /** A vault with [realWallets] wallets and a Duress Password, unlocked with that password and its cleanup held back (it stays held until a test lets it run). Returns the folder count. */
    private fun promoteWithCleanupHeld(realWallets: Int): Int {
        assertEquals(CreateResult.Created, runBlocking { vault.create(P1.toCharArray()) })
        val real = unlock(P1)
        repeat(realWallets) { real.createWallet() }
        val result = runBlocking { vault.setDuress(real, P2.toCharArray()) { it.createWallet() } }
        assertEquals(DuressResult.Enabled, result)
        real.close()
        vault.skipCleanupForTesting = true
        unlock(P2).close()
        assertTrue(layout.promotionMarker.exists())
        return layout.wallets.list().orEmpty().size
    }

    private fun unlock(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private companion object {
        const val P1 = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val P2 = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
