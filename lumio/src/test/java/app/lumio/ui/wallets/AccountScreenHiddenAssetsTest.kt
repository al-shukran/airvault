package app.lumio.ui.wallets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.airvault.core.model.AccountId
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.Networks
import app.lumio.ui.home.AssetIconSpec
import app.lumio.wallets.WalletAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** An account whose every asset Hide Small Balances leaves out: the line counts them and Show lists them, instead of saying there are no assets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountScreenHiddenAssetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val account = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(Networks.evm(1L), "0x" + "%040x".format(1)),
        index = 0,
        networkName = "Ethereum",
        coin = null,
        balance = null,
    )

    private val dust = AssetRowModel(
        assetKey = "dust",
        symbol = "DUST",
        name = "Dust Token",
        network = "Ethereum",
        amountText = "3",
        usd = 0.3,
        icon = AssetIconSpec.Bitcoin,
        assetId = null,
        isNative = false,
        testnet = false,
        zero = false,
    )

    private fun render(assets: List<AssetRowModel>) {
        compose.setContent {
            ClearAirTheme {
                AccountScreen(
                    walletName = "Air 1",
                    account = account,
                    showBalance = true,
                    onSend = {},
                    onReceive = {},
                    hidesSmall = { usd -> usd != null && usd < 1.0 },
                    assets = assets,
                )
            }
        }
    }

    @Test
    fun anAccountWhoseOnlyAssetIsHiddenSaysHowManyAreHiddenNotThatItHasNone() {
        render(listOf(dust))

        compose.onNodeWithText("No assets yet.").assertDoesNotExist()
        compose.onNodeWithText("1 small balance hidden").assertIsDisplayed()
    }

    @Test
    fun showListsTheHiddenAssetsAndTheLineGoes() {
        render(listOf(dust))

        compose.onNodeWithText("Show").performClick()

        compose.onNodeWithText("DUST").assertIsDisplayed()
        compose.onNodeWithText("1 small balance hidden").assertDoesNotExist()
    }
}
