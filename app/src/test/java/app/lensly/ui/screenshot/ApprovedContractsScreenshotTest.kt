package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.WhitelistEntry
import app.lensly.ui.settings.ApprovedContractsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Approved contracts: the empty state that names the signed update, and approved calls grouped by network, one of them a proxy. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedContractsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val ethereum = NetworkEntry(
        chainId = 1L,
        name = "Ethereum",
        nativeSymbol = "ETH",
        feeModel = FeeModel.L1,
        explorerName = "Etherscan",
        testnet = false,
        icon = "chain_ethereum",
    )

    private val approvals = listOf(
        WhitelistEntry(
            chainId = 1L,
            contract = "0x" + "5".repeat(40),
            selector = "0x38ed1739",
            signature = "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)",
            meaning = "Swap an exact amount of one token for another, along the route in the call.",
        ),
        WhitelistEntry(
            chainId = 1L,
            contract = "0x" + "6".repeat(40),
            selector = "0x7acb7757",
            signature = "stake(uint256,address)",
            meaning = "Stake an amount for a recipient in a proxy-fronted pool.",
            proxy = ProxyBinding(implementationAddress = "0x" + "7".repeat(40)),
        ),
    )

    @Test
    fun empty() = compose.snapshot("approved_contracts_empty") {
        ApprovedContractsScreen(entries = emptyList(), networks = emptyList(), onUpdateCatalog = {}, onBack = {}, onLock = {})
    }

    @Test
    fun populated() = compose.snapshot("approved_contracts_populated") {
        ApprovedContractsScreen(entries = approvals, networks = listOf(ethereum), onUpdateCatalog = {}, onBack = {}, onLock = {})
    }
}
