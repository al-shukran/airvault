package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.ProxyBinding
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review and refusal states of the sign flow. The reviews use [snapshotSettled], because whether APPROVE is on depends on
 * measured sizes that take a few frames to settle. The native and outside-registry reviews open at the top, so APPROVE is off there.
 */
private const val SWAP_CONTRACT = "0x5555555555555555555555555555555555555555"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val SWAP_SIGNATURE = "swapExactTokensForTokens(uint256,uint256,address)"

/** A call Lensly cannot decode that the whitelist approves, on the sample network. [proxy] is set for a proxy under Warn and confirm. */
private fun whitelistedSample(proxy: ProxyBinding? = null) = sampleNativeReview.copy(
    function = "swapExactTokensForTokens",
    toAddress = SWAP_CONTRACT,
    amount = "",
    symbol = "",
    params = listOf(
        "amountIn (uint256)" to "1",
        "amountOutMin (uint256)" to "10",
        "to (address)" to "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
    ),
    warnings = emptyList(),
    whitelisted = WhitelistedCall(
        signature = SWAP_SIGNATURE,
        meaning = "Swap an exact amount of one token for another, along the route in the call.",
        call = UndecodedCall(
            target = SWAP_CONTRACT,
            networkName = "Monad Testnet",
            chainId = 10143L,
            value = "0 MON",
            selector = "6e17e9f6",
            words = listOf(
                "0".repeat(63) + "1",
                "0".repeat(63) + "a",
                "0".repeat(24) + "f278cf59f82edcf871d630f28ecc8056f25c1cdb",
            ),
            proxy = proxy,
        ),
    ),
)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewNativeTransfer() = compose.snapshotSettled("tx_review_native") {
        TxReviewScreen(review = sampleNativeReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedCall() = compose.snapshotSettled("tx_review_whitelisted") {
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedProxyUnderWarnAndConfirm() = compose.snapshotSettled("tx_review_whitelisted_proxy") {
        TxReviewScreen(
            review = whitelistedSample(ProxyBinding(implementationAddress = IMPLEMENTATION)),
            onApprove = {},
            onReject = {},
        )
    }

    @Test
    fun refusedProxyUnderBindToImplementation() = compose.snapshot("sign_refused_proxy") {
        SignRefusedScreen(
            reason = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the approved " +
                "version, so your Proxy Policy refuses it.",
            onDone = {},
            call = UndecodedCall(
                target = SWAP_CONTRACT,
                networkName = "Monad Testnet",
                chainId = 10143L,
                value = "0 MON",
                selector = "7acb7757",
                words = listOf("0".repeat(63) + "a"),
                proxy = ProxyBinding(implementationAddress = IMPLEMENTATION),
            ),
        )
    }

    @Test
    fun reviewOutsideRegistry() = compose.snapshotSettled("tx_review_outside_registry") {
        TxReviewScreen(review = sampleUnknownChainReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewPersonalMessage() = compose.snapshotSettled("tx_review_message") {
        TxReviewScreen(review = sampleMessageReview, onApprove = {}, onReject = {})
    }

    @Test
    fun refused() = compose.snapshot("sign_refused") {
        SignRefusedScreen(
            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
            onDone = {},
        )
    }
}
