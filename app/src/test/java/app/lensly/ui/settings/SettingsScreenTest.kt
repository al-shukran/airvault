package app.lensly.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings rows that open the lockout, the Networks list and the Tokens list, and what each row says. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val network = NetworkEntry(chainId = 1L, name = "Alpha Chain", nativeSymbol = "ETH", feeModel = FeeModel.L1, explorerName = null, testnet = false)
    private val token = TokenEntry(chainId = 1L, contract = "0x" + "a".repeat(40), symbol = "AAA", name = "Alpha Token", decimals = 18)

    private fun show(onNetworks: () -> Unit = {}, onTokens: () -> Unit = {}) {
        compose.setContent {
            LenslyTheme {
                SettingsScreen(
                    bulkCap = BulkCap.HUNDRED,
                    onBulkCap = {},
                    onChangePassword = {},
                    failureDelayCap = FailureCapChoice.ONE_MINUTE,
                    onFailureDelayCap = {},
                    catalog = Catalog(networks = listOf(network), tokens = listOf(token, token.copy(symbol = "BBB"))),
                    onNetworks = onNetworks,
                    onTokens = onTokens,
                    onUpdateCatalog = {},
                    onWipe = {},
                    versionName = "1.0",
                    versionCode = 1,
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun theLockoutRowNamesTheLongestWait() {
        show()

        compose.onNodeWithText("Wrong password lockout").assertExists()
        compose.onNodeWithText("Up to 1 min. The longest wait after a wrong App Password.").assertExists()
    }

    @Test
    fun theNetworksAndTokensRowsCountTheCatalog() {
        show()

        compose.onNodeWithText("1 network").assertExists()
        compose.onNodeWithText("2 tokens").assertExists()
    }

    @Test
    fun theNetworksRowOpensTheNetworksList() {
        var opened = ""
        show(onNetworks = { opened = "networks" }, onTokens = { opened = "tokens" })

        compose.onNodeWithText("Networks").performScrollTo().performClick()

        assertEquals("networks", opened)
    }

    @Test
    fun theTokensRowOpensTheTokensList() {
        var opened = ""
        show(onNetworks = { opened = "networks" }, onTokens = { opened = "tokens" })

        compose.onNodeWithText("Tokens").performScrollTo().performClick()

        assertEquals("tokens", opened)
    }

    @Test
    fun theLockoutSectionIsLabelledLockout() {
        show()

        compose.onNodeWithText("LOCKOUT").performScrollTo().assertIsDisplayed()
    }
}
