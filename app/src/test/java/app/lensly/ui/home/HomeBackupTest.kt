package app.lensly.ui.home

import app.lensly.app.WalletMeta
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The backup panel's rule: a wallet needs a backup until its seed is backed up, and the panel lists those wallets in order. */
class HomeBackupTest {

    private fun wallet(id: String, backedUp: Boolean): WalletMeta = WalletMeta(
        id = id,
        name = id,
        createdAtMs = 0L,
        deviceGenerated = true,
        accountCount = 1,
        masterFingerprintHex = null,
        accountXpub = null,
        seedBackedUp = backedUp,
    )

    @Test
    fun noWalletNeedsABackupWhenEverySeedIsBackedUp() {
        assertTrue(walletsNeedingBackup(listOf(wallet("a", backedUp = true), wallet("b", backedUp = true))).isEmpty())
    }

    @Test
    fun theWalletsWhoseSeedIsNotBackedUpAreListedInOrder() {
        val needing = walletsNeedingBackup(
            listOf(wallet("a", backedUp = false), wallet("b", backedUp = true), wallet("c", backedUp = false)),
        )

        assertEquals(listOf("a", "c"), needing.map { it.id })
    }
}
