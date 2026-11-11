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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.UNKNOWN_CHAIN_NAME
import app.lensly.ui.sign.UNKNOWN_UNIT
import app.lensly.ui.sign.assumedAmountNote
import app.lensly.ui.sign.unknownFeeNote
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.math.BigInteger

/** A single-part UR, the kind a pairing or signature code carries. */
internal const val SAMPLE_UR =
    "UR:CRYPTO-ACCOUNT/1-1/LPADAXCFAXHDCLAOEYWTJSTYLKSEHDIEUTVDHEKNUECSLNCXLTBBUTZTHDLEHTCMOHGUMWDMCPTDOSVTSSFA"

/** [count] different UR fragments, for an animated code. */
internal fun sampleUrParts(count: Int): List<String> {
    val alphabet = "ABCDEF0123456789"
    return (0 until count).map { part ->
        val body = (0 until 200).map { i -> alphabet[(i * 7 + part * 13) % alphabet.length] }
        "UR:CRYPTO-PSBT/${part + 1}-$count/" + body.joinToString("")
    }
}

private const val SAMPLE_FROM = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
private const val SAMPLE_TO = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"

private val monadTestnet = NetworkEntry(
    chainId = 10143L,
    name = "Monad Testnet",
    nativeSymbol = "MON",
    feeModel = FeeModel.L1,
    explorerName = "MonadVision",
    testnet = true,
    icon = "chain_monad",
)

/** A native transfer on a registry chain, with one warning. The review opens at the top, so APPROVE is still off. */
internal val sampleNativeReview = TxReview(
    networkName = "Monad Testnet",
    chainId = 10143L,
    fromAccountLabel = "Main account",
    walletName = "Main wallet",
    origin = "metamask",
    fromAddress = SAMPLE_FROM,
    toAddress = SAMPLE_TO,
    amount = "0.0100",
    symbol = "MON",
    tokenContract = null,
    function = "Native transfer",
    params = emptyList(),
    fee = listOf(
        "Gas limit" to "21000",
        "Max fee" to "52.5 gwei",
        "Priority fee" to "2 gwei",
        "Max total" to "0.0011 MON",
    ),
    feeNote = "Fee is paid in MON from the sender's balance.",
    warnings = listOf("This address has never received a transaction on this network."),
    nonce = 42L,
    txType = "EIP-1559 (type 2)",
    network = monadTestnet,
)

/** A native transfer on a chain outside the registry. The registry block and the unchecked check both show. */
internal val sampleUnknownChainReview = TxReview(
    networkName = UNKNOWN_CHAIN_NAME,
    chainId = 999_999L,
    fromAccountLabel = "ACCOUNT 1",
    walletName = "Main wallet",
    origin = "metamask",
    fromAddress = SAMPLE_FROM,
    toAddress = SAMPLE_TO,
    amount = "1000000000000000000",
    symbol = UNKNOWN_UNIT,
    tokenContract = null,
    function = "Native transfer",
    params = emptyList(),
    fee = listOf(
        "Gas limit" to "21000",
        "Max fee per gas" to "30 gwei",
        "Max priority fee" to "1.5 gwei",
        "Max fee" to "630000000000000 base units",
    ),
    feeNote = unknownFeeNote(BigInteger.valueOf(630_000_000_000_000L)),
    warnings = emptyList(),
    nonce = 42L,
    txType = "EIP-1559",
    network = null,
    amountNote = assumedAmountNote(BigInteger.TEN.pow(18)),
)

/** A personal message. It has no recipient, amount, nonce or fee, so the review shows the message text. */
internal val sampleMessageReview = TxReview(
    networkName = "Base",
    chainId = 8453L,
    fromAccountLabel = "Main account",
    walletName = "Main wallet",
    origin = "metamask",
    fromAddress = SAMPLE_FROM,
    toAddress = SAMPLE_FROM,
    amount = "",
    symbol = "",
    tokenContract = null,
    function = SIGN_MESSAGE_FUNCTION,
    params = listOf("message" to "Sign in to Lensly on 11 Nov 2026"),
    fee = emptyList(),
    feeNote = null,
    warnings = emptyList(),
    nonce = 0L,
    txType = "Personal message",
    network = NetworkEntry(
        chainId = 8453L,
        name = "Base",
        nativeSymbol = "ETH",
        feeModel = FeeModel.L2_ROLLUP,
        explorerName = "Basescan",
        testnet = false,
        icon = "chain_base",
    ),
)

private const val SETTLED_TAG = "lensly-settled-screen"
private const val SETTLED_DENSITY = 1.75f

/** Frames to run before a settled capture. A measured size reaches the state on a later frame, one step per frame. */
private const val SETTLE_FRAMES = 6

/**
 * [snapshot] for a screen whose state depends on what layout measured, such as a review that checks whether its list
 * fits on screen. The harness stops the test clock, so those states would be captured one step early. This renders at
 * the same 720 x 1600 px and 280 dpi, then runs a few frames before the capture.
 */
internal fun ComposeContentTestRule.snapshotSettled(name: String, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(SETTLED_DENSITY)) {
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .testTag(SETTLED_TAG)
                        .requiredSize(
                            width = (720f / SETTLED_DENSITY).dp,
                            height = (1600f / SETTLED_DENSITY).dp,
                        )
                        .background(LenslyBlack),
                ) {
                    content()
                }
            }
        }
    }
    repeat(SETTLE_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
    waitForIdle()
    onNodeWithTag(SETTLED_TAG).captureRoboImage(filePath = "$SCREENSHOT_DIR/$name.png")
}
