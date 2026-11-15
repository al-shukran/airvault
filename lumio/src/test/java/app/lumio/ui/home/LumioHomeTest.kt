package app.lumio.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.theme.LenslyTheme
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lumio's Home: the empty state, the pairing action, the network switch, each wallet's Apps, and the delete that asks first. */
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
    fun homeHasNoBackButtonAndTheLockLocks() {
        var locked = false
        setHome(wallets = emptyList(), onLock = { locked = true })

        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lock the vault").performClick()

        assertTrue(locked)
    }

    @Test
    fun mainnetIsTheSideOnShowWhenTheHomeOpens() {
        setHome(wallets = listOf(wallet(name = "Demo", bitcoin = bothSides())))

        compose.onNodeWithText("Mainnet", ignoreCase = true).assertIsSelected()
        text("1 account on Mainnet").assertExists()
        text("Balances on mainnet networks").assertExists()
        text("4 accounts on Testnet").assertDoesNotExist()
    }

    @Test
    fun theSwitchShowsOnlyTheChosenSide() {
        val testnetAccounts = BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.TESTNET4).size
        setHome(wallets = listOf(wallet(name = "Demo", bitcoin = bothSides())))

        compose.onNodeWithText("Testnet", ignoreCase = true).performClick()

        compose.onNodeWithText("Testnet", ignoreCase = true).assertIsSelected()
        text("$testnetAccounts accounts on Testnet").assertExists()
        text("Balances on testnet networks").assertExists()
        text("1 account on Mainnet").assertDoesNotExist()
        text("Balances on mainnet networks").assertDoesNotExist()

        compose.onNodeWithText("Mainnet", ignoreCase = true).performClick()

        text("1 account on Mainnet").assertExists()
        text("$testnetAccounts accounts on Testnet").assertDoesNotExist()
    }

    @Test
    fun aWalletWithOnlyTestnetBitcoinHasNoBitcoinAppOnMainnet() {
        val demo = wallet(name = "Demo", bitcoin = BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.TESTNET4), evm = false)
        setHome(wallets = listOf(demo))

        text("No accounts on Mainnet in this wallet").assertExists()
        compose.onNodeWithTag(BITCOIN_APP_TAG).assertDoesNotExist()

        compose.onNodeWithText("Testnet", ignoreCase = true).performClick()

        compose.onNodeWithTag(BITCOIN_APP_TAG).assertExists()
    }

    @Test
    fun tappingAnAppOpensThatWalletsPortfolio() {
        var bitcoinOf: PairedWallet? = null
        var balancesOf: PairedWallet? = null
        val demo = wallet(name = "Demo", bitcoin = bothSides())
        setHome(wallets = listOf(demo), onBitcoin = { bitcoinOf = it }, onBalances = { balancesOf = it })

        compose.onNodeWithTag(BITCOIN_APP_TAG).performClick()
        compose.onNodeWithTag(ETHEREUM_APP_TAG).performClick()

        assertEquals(demo, bitcoinOf)
        assertEquals(demo, balancesOf)
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

    /** The text of a row. The rows are clickable, so their texts stay in the unmerged tree. */
    private fun text(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    /** One Bitcoin account of each script type on testnet4, and the Taproot account on mainnet. */
    private fun bothSides(): List<BtcWatchOnlyAccount> =
        BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.TESTNET4) +
            PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.TAPROOT)

    private fun wallet(name: String, bitcoin: List<BtcWatchOnlyAccount> = emptyList(), evm: Boolean = true): PairedWallet =
        PairedWallet(
            id = "wallet-$name",
            name = name,
            fingerprint = 0x1a2b3c4dL,
            createdAtMs = 1L,
            evm = if (evm) HdWallet(PairFixtures.seed).accountXpub() else null,
            bitcoin = bitcoin,
        )

    private fun setHome(
        wallets: List<PairedWallet>,
        initial: NetworkMode = NetworkMode.MAINNET,
        onLock: () -> Unit = {},
        onPair: () -> Unit = {},
        onDelete: (PairedWallet) -> Unit = {},
        onBitcoin: (PairedWallet) -> Unit = {},
        onBalances: (PairedWallet) -> Unit = {},
    ) {
        compose.setContent {
            LenslyTheme {
                var mode by remember { mutableStateOf(initial) }
                LumioHome(
                    onLock = onLock,
                    onPair = onPair,
                    wallets = wallets,
                    onDelete = onDelete,
                    mode = mode,
                    onMode = { mode = it },
                    onBitcoin = onBitcoin,
                    onBalances = onBalances,
                )
            }
        }
    }
}
