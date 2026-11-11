package app.lensly.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.registry.WhitelistParam
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.sign.groupAddress
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The approved contracts: the empty state that names the signed update, and each approved call with its contract in full. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ApprovedContractsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = NetworkEntry(
        chainId = 1L,
        name = "Ethereum",
        nativeSymbol = "ETH",
        feeModel = FeeModel.L1,
        explorerName = "Etherscan",
        testnet = false,
    )
    private val contract = "0x" + "5".repeat(40)
    private val swap = WhitelistEntry(
        chainId = 1L,
        contract = contract,
        selector = "0x6e17e9f6",
        signature = "swapExactTokensForTokens(uint256,uint256,address)",
        meaning = "Swap an exact amount along the route",
        params = listOf(
            WhitelistParam("amountIn", "uint256"),
            WhitelistParam("amountOutMin", "uint256"),
            WhitelistParam("to", "address"),
        ),
        upgradeable = false,
    )
    private val proxy = WhitelistEntry(
        chainId = 1L,
        contract = "0x" + "6".repeat(40),
        selector = "0x7acb7757",
        signature = "stake(uint256,address)",
        meaning = "Stake for a recipient",
        proxy = ProxyBinding(implementationAddress = "0x" + "7".repeat(40)),
        params = listOf(WhitelistParam("amount", "uint256"), WhitelistParam("recipient", "address")),
        upgradeable = true,
    )

    private fun show(entries: List<WhitelistEntry>, onUpdateCatalog: () -> Unit = {}) {
        compose.setContent {
            LenslyTheme {
                ApprovedContractsScreen(
                    entries = entries,
                    networks = listOf(ethereum),
                    onUpdateCatalog = onUpdateCatalog,
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun anEmptyListSaysContractsComeOnlyFromASignedUpdate() {
        show(emptyList())

        compose.onNodeWithText("NO APPROVED CONTRACTS").assertExists()
        compose.onNodeWithText(
            "Contracts are added only through a signed offline update (Update catalog). This prevents anyone from " +
                "approving a contract on this phone.",
        ).assertExists()
    }

    @Test
    fun theEmptyStateLinksToUpdateCatalog() {
        var opened = false
        show(emptyList(), onUpdateCatalog = { opened = true })

        compose.onNodeWithText("UPDATE CATALOG").performClick()

        assertTrue(opened)
    }

    @Test
    fun eachApprovedCallShowsItsContractInFullUnderItsNetwork() {
        show(listOf(swap))

        compose.onNodeWithText("Ethereum").assertExists()
        compose.onNodeWithText("swapExactTokensForTokens(uint256,uint256,address)").assertExists()
        compose.onNodeWithText("0x6e17e9f6").assertExists()
        compose.onNodeWithText("Swap an exact amount along the route").assertExists()
        compose.onNodeWithText(groupAddress(contract)).assertExists()
    }

    @Test
    fun aProxyShowsItsChipAndTheImplementationItIsBoundTo() {
        show(listOf(proxy))

        compose.onNodeWithText("PROXY").assertExists()
        compose.onNodeWithText(groupAddress("0x" + "7".repeat(40))).assertExists()
    }

    @Test
    fun aCallOnAChainOutsideTheCatalogIsNamedByItsChainId() {
        show(listOf(proxy.copy(chainId = 10L)))

        compose.onNodeWithText("Chain 10").assertExists()
    }
}
