package app.lensly.ui.sign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.VisualIdentity
import app.lensly.app.sign.UndecodedCall
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FROM = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
private const val TO = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"
private const val LONG_WALLET = "A wallet with a deliberately long name that must not be cut short"
private const val LONG_ORIGIN = "dapp.example.invalid/a/very/long/origin/path/that/keeps/going/and/going/on"
private const val UNLIMITED = "Unlimited approval: this contract can spend all your USDC"
private const val MAX_UINT256_DECIMAL = "115792089237316195423570985008687907853269984665640564039457584007913129639935"

private val BASE = NetworkEntry(
    chainId = 8453L,
    name = "Base",
    nativeSymbol = "ETH",
    feeModel = FeeModel.L2_ROLLUP,
    explorerName = "Basescan",
    testnet = false,
    icon = "chain_base",
)

/** An unlimited approval of a known token, with a long wallet name and origin, so every header value is long. */
private fun approvalReview(origin: String? = LONG_ORIGIN) = TxReview(
    networkName = BASE.name,
    chainId = BASE.chainId,
    fromAccountLabel = "ACCOUNT WITH A LONG DISPLAY NAME 12",
    fromAddress = FROM,
    toAddress = TO,
    amount = "115792089237316195423570985008687907853269984665640564039457.584007913129639935",
    symbol = "USDC",
    tokenContract = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913",
    function = "approve",
    params = listOf("spender" to TO, "value" to MAX_UINT256_DECIMAL),
    fee = listOf("Gas limit" to "46000", "Max fee" to "0.0001 ETH"),
    feeNote = null,
    warnings = listOf(UNLIMITED),
    nonce = 42L,
    txType = "EIP-1559",
    network = BASE,
    signature = "approve(address,uint256)",
    walletName = LONG_WALLET,
    walletIdentity = VisualIdentity(ProfileColour.TEAL, ProfileIcon.STAR),
    accountIdentity = VisualIdentity(ProfileColour.PINK, ProfileIcon.HEART),
    origin = origin,
)

/** A personal message that is long, wraps over several lines, and has a size. */
private val LONG_MESSAGE =
    "Sign in to Lensly. " + "This message is long and keeps going so that it wraps over many lines. ".repeat(12)

private fun messageReview() = TxReview(
    networkName = BASE.name,
    chainId = BASE.chainId,
    fromAccountLabel = "MAIN ACCOUNT",
    fromAddress = FROM,
    toAddress = "",
    amount = "",
    symbol = "",
    tokenContract = null,
    function = SIGN_MESSAGE_FUNCTION,
    params = listOf(
        "message" to LONG_MESSAGE,
        "size" to "${LONG_MESSAGE.length} characters, ${LONG_MESSAGE.length} bytes",
    ),
    fee = emptyList(),
    feeNote = null,
    warnings = emptyList(),
    nonce = 0L,
    txType = "Personal message",
    network = BASE,
    walletName = "Main wallet",
)

/**
 * Fails when any text on the screen is drawn cut short, by an ellipsis or by a line limit. A line limit drops lines, so the
 * text needs no more lines than it was given. An ellipsis ends the last visible line before the end of the text. Compose's
 * width-overflow flag is not used: it is set for any text narrower than its layout width, so it cannot tell a cut from a short line.
 */
private fun ComposeContentTestRule.assertNoTextIsCutShort() {
    val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
    assertFalse(nodes.isEmpty(), "the screen should show some text")
    nodes.forEach { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        layouts.forEach { layout ->
            // Each layout lays out its own text, which a merged node may only share with others.
            val own = layout.layoutInput.text.text.trimEnd()
            assertFalse(layout.didOverflowHeight, "a text has more lines than it can show: $own")
            val shownEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
            assertTrue(shownEnd >= own.length, "a text is cut short: $own (shown to $shownEnd of ${own.length})")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
// Native graphics, so text is measured with real font metrics. The legacy mode measures words at a few pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TxReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSigningHeaderShowsTheWalletAccountAddressNetworkAndOriginInFull() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(LONG_WALLET).assertExists()
        compose.onNodeWithText("ACCOUNT WITH A LONG DISPLAY NAME 12").assertExists()
        compose.onNodeWithText(groupAddress(FROM)).assertExists()
        compose.onNodeWithText("REQUESTED BY").assertExists()
        compose.onNodeWithText(LONG_ORIGIN).assertExists()
        compose.onNodeWithText("Base").assertExists()
    }

    @Test
    fun aRequestThatNamesNoOriginSaysSoInsteadOfShowingNothing() {
        compose.setContent {
            LenslyTheme { TxReviewScreen(review = approvalReview(origin = null), onApprove = {}, onReject = {}) }
        }

        compose.onNodeWithText("Not stated").assertExists()
    }

    @Test
    fun theHoldControlIsDisabledAndSaysWhyUntilTheReviewIsReadToTheEnd() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("SCROLL TO THE END TO REVIEW EVERYTHING").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun anUnlimitedApprovalWarningIsShownInFull() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(UNLIMITED).assertExists()
    }

    @Test
    fun aLongMessageIsShownWholeWithItsSize() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = messageReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(LONG_MESSAGE).assertExists()
        compose.onNodeWithText("${LONG_MESSAGE.length} characters, ${LONG_MESSAGE.length} bytes").assertExists()
    }

    @Test
    fun noTextOnAnApprovalReviewIsCutShort() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.assertNoTextIsCutShort()
    }

    @Test
    fun noTextOnAMessageReviewIsCutShort() {
        compose.setContent { LenslyTheme { TxReviewScreen(review = messageReview(), onApprove = {}, onReject = {}) } }

        compose.assertNoTextIsCutShort()
    }

    /** The check must fail on a text that a line limit cuts, or it would pass every review. */
    @Test
    fun theCutCheckCatchesATextThatALineLimitCuts() {
        compose.setContent {
            LenslyTheme {
                Column(Modifier.width(120.dp)) {
                    Text(
                        text = "A long line of text that needs several lines to be shown in full",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        assertFailsWith<AssertionError> { compose.assertNoTextIsCutShort() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class RefusalScreensTest {

    @get:Rule
    val compose = createComposeRule()

    /** Three calldata words: an address word, a uint word, and a last word that is only partly there. */
    private val words = listOf(
        "0".repeat(24) + "f278cf59f82edcf871d630f28ecc8056f25c1cdb",
        "0".repeat(63) + "1",
        "ab",
    )

    @Test
    fun anUndecodableCallShowsItsContractNetworkValueSelectorAndEveryWordInFull() {
        val call = UndecodedCall(
            target = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            networkName = "Ethereum",
            chainId = 1L,
            value = "0.5 ETH",
            selector = "70a08231",
            words = words,
        )
        compose.setContent {
            LenslyTheme { SignRefusedScreen(reason = "Unknown contract call", onDone = {}, call = call) }
        }

        compose.onNodeWithText(
            "Lensly cannot decode this call, so it will not sign it. The details below are the whole call.",
        ).assertExists()
        compose.onNodeWithText(groupAddress("0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb")).assertExists()
        compose.onNodeWithText("0.5 ETH").assertExists()
        compose.onNodeWithText("0x70a08231").assertExists()
        words.forEachIndexed { index, word ->
            compose.onNodeWithText("WORD ${index + 1}").assertExists()
            compose.onNodeWithText("0x" + groupHex(word)).assertExists()
        }
    }

    @Test
    fun aRequestThatMatchesNoWalletNamesItsFingerprintAndPathAndCanScanAgain() {
        val request = EthSignRequest(
            requestId = ByteArray(16) { 1 },
            signData = byteArrayOf(1),
            dataType = DataType.PERSONAL_MESSAGE,
            chainId = 1L,
            derivationPath = listOf(
                PathStep(44, true),
                PathStep(60, true),
                PathStep(0, true),
                PathStep(0, false),
                PathStep(0, false),
            ),
            sourceFingerprint = 0x1a2b3c4d,
            address = null,
            origin = null,
        )
        var scannedAgain = 0
        compose.setContent {
            LenslyTheme {
                NoMatchingWalletScreen(
                    request = request,
                    several = false,
                    onScanAgain = { scannedAgain++ },
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("No wallet in this vault matches this request").assertExists()
        compose.onNodeWithText("1A2B3C4D").assertExists()
        compose.onNodeWithText("m/44'/60'/0'/0/0").assertExists()
        compose.onNodeWithText("SCAN AGAIN").performClick()
        assertEquals(1, scannedAgain)
    }
}
