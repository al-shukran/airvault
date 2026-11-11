package app.lensly.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    private val approvals = listOf(
        WhitelistEntry(1L, "0x" + "5".repeat(40), "0x38ed1739", "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)", "Swap"),
        WhitelistEntry(1L, "0x" + "6".repeat(40), "0x7acb7757", "stake(uint256,address)", "Stake", ProxyBinding("0x" + "7".repeat(40))),
    )

    private fun show(
        onNetworks: () -> Unit = {},
        onTokens: () -> Unit = {},
        proxyPolicy: ProxyPolicy? = ProxyPolicy.DEFAULT,
        onProxyPolicy: () -> Unit = {},
    ) {
        compose.setContent {
            LenslyTheme {
                SettingsScreen(
                    bulkCap = BulkCap.HUNDRED,
                    onBulkCap = {},
                    onChangePassword = {},
                    failureDelayCap = FailureCapChoice.ONE_MINUTE,
                    onFailureDelayCap = {},
                    catalog = Catalog(
                        networks = listOf(network),
                        tokens = listOf(token, token.copy(symbol = "BBB")),
                        whitelist = approvals,
                    ),
                    onNetworks = onNetworks,
                    onTokens = onTokens,
                    proxyPolicy = proxyPolicy,
                    onProxyPolicy = onProxyPolicy,
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
    fun theApprovedContractsRowCountsTheWhitelistUnderItsOwnSection() {
        show()

        compose.onNodeWithText("WHITELIST").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 approved calls").assertExists()
    }

    @Test
    fun theProxyPolicyRowNamesTheSavedPolicyAndMarksTheDefault() {
        show(proxyPolicy = ProxyPolicy.DEFAULT)

        compose.onNodeWithText("Bind to implementation (default)").assertExists()
    }

    @Test
    fun theProxyPolicyRowShowsAChangedPolicyWithoutTheDefaultMark() {
        show(proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM)

        compose.onNodeWithText("Warn and confirm").assertExists()
    }

    @Test
    fun theProxyPolicyRowOpensTheChoice() {
        var opened = false
        show(onProxyPolicy = { opened = true })

        compose.onNodeWithText("Proxy policy").performScrollTo().performClick()

        assertTrue(opened)
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
