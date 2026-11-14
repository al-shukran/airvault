package app.lumio.ui.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.theme.LenslyTheme
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lumio's Home: the empty state, the pairing action, the paired wallets, and the delete that asks first. */
@RunWith(RobolectricTestRunner::class)
class LumioHomeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saysThatNoWalletIsPairedYet() {
        setHome(wallets = emptyList())

        compose.onNodeWithText("No wallets paired yet", ignoreCase = true).assertExists()
    }

    @Test
    fun thePairActionIsEnabledAndOpensTheScan() {
        var paired = false
        setHome(wallets = emptyList(), onPair = { paired = true })

        compose.onNodeWithText("Pair from Lensly", ignoreCase = true).assertIsEnabled().performClick()

        assertTrue(paired)
    }

    @Test
    fun theBackArrowLocksAndLeavesTheHome() {
        var locked = false
        setHome(wallets = emptyList(), onLock = { locked = true })

        compose.onNodeWithContentDescription("Back").performClick()

        assertTrue(locked)
    }

    @Test
    fun aPairedWalletShowsItsNameFingerprintFamilyAndNetworks() {
        setHome(wallets = listOf(wallet(name = "Demo", withBitcoin = true)))

        compose.onNodeWithText("Demo").assertExists()
        compose.onNodeWithText("Fingerprint 1a2b3c4d").assertExists()
        compose.onNodeWithText("EVM").assertExists()
        compose.onNodeWithText("BITCOIN").assertExists()
        compose.onNodeWithText("ALL EVM NETWORKS").assertExists()
        compose.onNodeWithText("MAINNET").assertExists()
        compose.onNodeWithText("TESTNET").assertExists()
    }

    @Test
    fun deletingAWalletAsksFirstAndDeletesOnConfirm() {
        var deleted: PairedWallet? = null
        val demo = wallet(name = "Demo")
        setHome(wallets = listOf(demo), onDelete = { deleted = it })

        compose.onNodeWithText("Delete", ignoreCase = true).performClick()
        compose.onNodeWithText("Delete Demo?").assertExists()
        assertNull(deleted)

        compose.onNodeWithTag(DELETE_CONFIRM_TAG).performClick()

        assertEquals(demo, deleted)
    }

    @Test
    fun cancellingADeletionKeepsTheWallet() {
        var deleted: PairedWallet? = null
        setHome(wallets = listOf(wallet(name = "Demo")), onDelete = { deleted = it })

        compose.onNodeWithText("Delete", ignoreCase = true).performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertNull(deleted)
        compose.onNodeWithText("Demo").assertExists()
    }

    private fun wallet(name: String, withBitcoin: Boolean = false): PairedWallet {
        val hd = HdWallet(PairFixtures.seed)
        val accounts = if (withBitcoin) {
            BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.TESTNET4) + listOf(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.TAPROOT))
        } else {
            emptyList()
        }
        return PairedWallet(
            id = "wallet-$name",
            name = name,
            fingerprint = 0x1a2b3c4dL,
            createdAtMs = 1L,
            evm = hd.accountXpub(),
            bitcoin = accounts,
        )
    }

    private fun setHome(
        wallets: List<PairedWallet>,
        onLock: () -> Unit = {},
        onPair: () -> Unit = {},
        onDelete: (PairedWallet) -> Unit = {},
    ) {
        compose.setContent {
            LenslyTheme {
                LumioHome(onLock = onLock, onPair = onPair, wallets = wallets, onDelete = onDelete)
            }
        }
    }
}
