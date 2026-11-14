package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.StaticAbiDecoder
import app.lensly.core.registry.StaticDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.web3j.utils.Numeric

/**
 * Review and refusal states of the sign flow. The reviews use [snapshotSettled], because whether APPROVE is on depends on
 * measured sizes that take a few frames to settle. The native and outside-registry reviews open at the top, so APPROVE is off there.
 */
private const val SWAP_CONTRACT = "0x5555555555555555555555555555555555555555"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val SWAP_SIGNATURE = "swapExactTokensForTokens(uint256,uint256,address)"
private const val SWAP_RECIPIENT = "0xf278cf59f82edcf871d630f28ecc8056f25c1cdb"

/** The arguments of [SWAP_SIGNATURE], as the whitelist entry declares them. */
private val SWAP_PARAMS = listOf(
    WhitelistParam("amountIn", "uint256", tokenRef = true),
    WhitelistParam("amountOutMin", "uint256"),
    WhitelistParam("to", "address"),
)

/** A real calldata for the swap: the selector, then one full word per argument. */
private val SWAP_CALLDATA = Numeric.hexStringToByteArray(
    "0x6e17e9f6" +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger.ONE, 64) +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger.TEN, 64) +
        Numeric.toHexStringNoPrefixZeroPadded(BigInteger(SWAP_RECIPIENT.removePrefix("0x"), 16), 64),
)

/** What the decoder reads from [SWAP_CALLDATA], so the review lists the values the bytes hold and not typed-in ones. */
private val SWAP_DECODED = (StaticAbiDecoder.decode(SWAP_CALLDATA, SWAP_PARAMS) as StaticDecode.Ok).params

/** A call Lensly cannot decode that the whitelist approves, on the sample network. [proxy] is set for a proxy under Warn and confirm. */
private fun whitelistedSample(proxy: ProxyBinding? = null) = sampleNativeReview.copy(
    function = "swapExactTokensForTokens",
    toAddress = SWAP_CONTRACT,
    amount = "",
    symbol = "",
    params = SWAP_DECODED.map { "${it.param.name} (${it.param.type})" to it.value },
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
            words = SWAP_CALLDATA.drop(4).toByteArray().toHex().chunked(64),
            proxy = proxy,
        ),
    ),
)

private const val REVIEW_TAG = "lensly-scrolled-review"
private const val REVIEW_DENSITY = 1.75f

/** Frames to run before a settled capture, the same count [snapshotSettled] runs. */
private const val REVIEW_FRAMES = 6

/**
 * [snapshotSettled] for a review that scrolls first: the same 720 x 1600 px window at 280 dpi, then a few frames so
 * measured sizes settle, then the review scrolls to the node whose text is [label]. The review is a plain vertical
 * scroll, which Compose's performScrollToNode does not support, so this scrolls the node itself into view.
 */
private fun ComposeContentTestRule.snapshotScrolled(
    name: String,
    label: String,
    toggle: String? = null,
    content: @Composable () -> Unit,
) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(REVIEW_DENSITY)) {
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .testTag(REVIEW_TAG)
                        .requiredSize(
                            width = (720f / REVIEW_DENSITY).dp,
                            height = (1600f / REVIEW_DENSITY).dp,
                        )
                        .background(LenslyBlack),
                ) {
                    content()
                }
            }
        }
    }
    repeat(REVIEW_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    // performScrollTo applies the scroll through the frame clock, so the clock must advance while it runs.
    mainClock.autoAdvance = true
    // A folded section named by [toggle] is scrolled to and clicked open before the scroll to [label], so its contents are laid out.
    toggle?.let {
        onNodeWithText(it).performScrollTo().performClick()
        waitForIdle()
    }
    onNodeWithText(label).performScrollTo()
    waitForIdle()
    mainClock.autoAdvance = false
    repeat(REVIEW_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    waitForIdle()
    onNodeWithTag(REVIEW_TAG).captureRoboImage(filePath = "$SCREENSHOT_DIR/$name.png")
}

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
    fun reviewWhitelistedParams() = compose.snapshotScrolled("tx_review_whitelisted_params", "FEE BREAKDOWN") {
        // The fact after Parameters: scrolling to it leaves the whole Parameters section in view above it.
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewWhitelistedRawCalldata() = compose.snapshotScrolled(
        "tx_review_whitelisted_raw",
        "FUNCTION",
        toggle = "Raw calldata",
    ) {
        // The raw calldata section is opened first. The first fact after the whitelisted block is then scrolled to, which
        // leaves the expanded words in view above it.
        TxReviewScreen(review = whitelistedSample(), onApprove = {}, onReject = {})
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
