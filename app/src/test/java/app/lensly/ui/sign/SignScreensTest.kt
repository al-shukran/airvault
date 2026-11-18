package app.lensly.ui.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.VisualIdentity
import app.lensly.app.addressKeyOf
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WhitelistedCall
import app.lensly.core.evm.AccessListEntry
import app.lensly.core.registry.DenylistCondition
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.ui.components.groupAddress
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
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
private const val BASE_USDC = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
private const val MAX_UINT256_DECIMAL = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val APPROVED_MEANING = "Swap an exact amount along the route in the call"
private const val UPGRADE_WARNING = "The implementation could have been upgraded since it was approved, and Lensly cannot check " +
    "it offline. The implementation it was approved with is in the details."

/** Three calldata words of a whitelisted swap: two uint words, and the recipient word. */
private val SWAP_WORDS = listOf(
    "0".repeat(63) + "1",
    "0".repeat(63) + "a",
    "0".repeat(24) + "f278cf59f82edcf871d630f28ecc8056f25c1cdb",
)

/** A call Lensly cannot decode that the whitelist approves: the approved meaning, the whole call, and the proxy if it has one. */
private fun whitelistedReview(proxy: ProxyBinding? = null) = approvalReview(origin = null).copy(
    function = "swapExactTokensForTokens",
    toAddress = BASE_USDC,
    amount = "",
    symbol = "",
    tokenContract = null,
    token = null,
    params = listOf(
        "amountIn (uint256)" to "1",
        "amountOutMin (uint256)" to "10",
        "to (address)" to "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
    ),
    signature = null,
    warnings = emptyList(),
    whitelisted = WhitelistedCall(
        signature = "swapExactTokensForTokens(uint256,uint256,address)",
        meaning = APPROVED_MEANING,
        call = UndecodedCall(
            target = BASE_USDC,
            networkName = BASE.name,
            chainId = BASE.chainId,
            value = "0 ETH",
            selector = "6e17e9f6",
            words = SWAP_WORDS,
            proxy = proxy,
        ),
    ),
)

private const val DENYLIST_REASON = "Unlimited approval: the spender can take every token."

/**
 * A multicall with its calls listed level by level: an approval, and a Multicall3 batch whose whitelisted sweep sits one level down
 * and names its contract.
 */
private fun multicallReview() = approvalReview(origin = null).copy(
    function = "multicall",
    signature = "multicall(uint256,bytes[])",
    toAddress = TO,
    amount = "",
    symbol = "",
    tokenContract = null,
    token = null,
    params = listOf("deadline (uint256)" to "1700000000"),
    warnings = emptyList(),
    nested = listOf(
        NestedCall(
            depth = 1,
            target = null,
            function = "approve",
            params = listOf("spender" to TO, "value" to "10"),
            warnings = listOf(UNLIMITED),
            note = UNKNOWN_DECIMALS_NOTE,
            unregistered = true,
            denylisted = listOf(
                DenylistEntry(
                    chainId = BASE.chainId,
                    selector = "0x095ea7b3",
                    condition = DenylistCondition.UNLIMITED,
                    severity = DenylistSeverity.WARN,
                    reason = DENYLIST_REASON,
                ),
            ),
        ),
        NestedCall(depth = 1, target = null, function = "aggregate3", params = listOf("allowFailure (bool)" to "false")),
        NestedCall(
            depth = 2,
            target = BASE_USDC,
            function = "sweepToken",
            params = listOf("token (address)" to BASE_USDC, "amountMinimum (uint256)" to "10"),
            whitelisted = WhitelistedCall(
                signature = "sweepToken(address,uint256,address)",
                meaning = APPROVED_MEANING,
                call = UndecodedCall(
                    target = BASE_USDC,
                    networkName = BASE.name,
                    chainId = BASE.chainId,
                    value = "0 ETH",
                    selector = "df2ab5bb",
                    words = SWAP_WORDS,
                    proxy = null,
                ),
            ),
        ),
    ),
)

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
    tokenContract = BASE_USDC,
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

/** A Permit of the Base USDC token from [FROM] to [TO], with the notes the Address Book and the device's accounts give its addresses. */
private fun permitReview(notes: Map<String, AddressNotes>) = TxReview(
    networkName = BASE.name,
    chainId = BASE.chainId,
    fromAccountLabel = "MAIN ACCOUNT",
    fromAddress = FROM,
    toAddress = "",
    amount = "",
    symbol = "",
    tokenContract = null,
    function = "Permit",
    params = emptyList(),
    fee = emptyList(),
    feeNote = null,
    warnings = emptyList(),
    nonce = 0L,
    txType = "Typed data (EIP-712)",
    network = BASE,
    walletName = "Main wallet",
    typed = TypedReview(
        domain = listOf(
            "Name" to "USD Coin",
            "Version" to "2",
            "Chain ID" to "${BASE.chainId} (Base)",
            "Verifying contract" to BASE_USDC,
        ),
        summary = listOf("Owner" to FROM, "Spender" to TO, "Amount" to "100 USDC", "Deadline" to "1 Jan 2030, 00:00 UTC"),
        fields = listOf(
            TypedRow(0, "owner", "address", FROM),
            TypedRow(0, "spender", "address", TO),
            TypedRow(0, "value", "uint256", "100000000"),
            TypedRow(0, "nonce", "uint256", "0"),
            TypedRow(0, "deadline", "uint256", "1893456000"),
        ),
    ),
    addressNotes = notes,
)

/** The warning on a recipient that has never received on its network. It is a plain warning, with no check to tick. */
private const val NEW_RECIPIENT = "This address has never received a transaction on this network."

/** A native transfer of a known coin with no warning and no check. It is short, so it fits on screen. */
private fun plainTransfer() = approvalReview(origin = null).copy(
    fromAccountLabel = "MAIN ACCOUNT",
    walletName = "Main wallet",
    amount = "0.01",
    symbol = "ETH",
    tokenContract = null,
    token = null,
    function = "Native transfer",
    params = emptyList(),
    signature = null,
    warnings = emptyList(),
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
    fun theRequestNamesTheAccountOriginAndNetworkInTheHeaderAndTheWalletInItsDetails() {
        compose.setContent { LumenTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("ACCOUNT WITH A LONG DISPLAY NAME 12").assertExists()
        compose.onNodeWithText("Base").assertExists()
        compose.onNodeWithText("Claimed by request (not verified)").assertExists()
        compose.onNodeWithText(LONG_ORIGIN).assertExists()
        compose.onNodeWithText(LONG_WALLET).assertExists()
        // The header and the From row both show the signing address, so it is found among the matches.
        assertTrue(compose.onAllNodesWithText(groupAddress(FROM)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aRequestThatNamesNoOriginSaysSoInsteadOfShowingNothing() {
        compose.setContent {
            LumenTheme { TxReviewScreen(review = approvalReview(origin = null), onApprove = {}, onReject = {}) }
        }

        compose.onNodeWithText("Not stated").assertExists()
    }

    /** An unknown call is read, and still waits for its tick: the sign control opens only once both are done. */
    @Test
    fun anUnknownCallSignsOnlyAfterItsTickAndItsRead() {
        val unknown = plainTransfer().copy(warnings = listOf(UNKNOWN_CALL_WARNING))
        compose.setContent {
            LumenTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TxReviewScreen(review = unknown, onApprove = {}, onReject = {})
                }
            }
        }

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
        // The row is toggled through its click action, so the tick does not depend on the banner being above the bottom action in this window.
        compose.onNodeWithText("I understand Lensly cannot say what this does.").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action?.invoke()
        compose.waitForIdle()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun anUnknownCallWithoutItsTickStaysUnsignableOnceReadToTheEnd() {
        val unknown = plainTransfer().copy(warnings = listOf(UNKNOWN_CALL_WARNING))
        compose.setContent {
            LumenTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TxReviewScreen(review = unknown, onApprove = {}, onReject = {})
                }
            }
        }

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun theAccessListIsCountedAndEachEntryIsShownInTheDetails() {
        val warmed = plainTransfer().copy(accessList = listOf(AccessListEntry(TO, listOf("0x" + "22".repeat(32)))))
        compose.setContent { LumenTheme { TxReviewScreen(review = warmed, onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("Access list").assertExists()
        compose.onNodeWithText("1 address, 1 storage key").assertExists()
        compose.onNodeWithText("Address 1").assertExists()
        compose.onNodeWithText("Storage key 1").assertExists()
    }

    /** An unlimited approval is read, and still waits for its tick: the sign control opens only once both are done. */
    @Test
    fun anUnlimitedApprovalSignsOnlyAfterItsTickAndItsRead() {
        val unlimited = plainTransfer().copy(warnings = listOf(unlimitedApprovalWarning("USDC")))
        compose.setContent {
            LumenTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TxReviewScreen(review = unlimited, onApprove = {}, onReject = {})
                }
            }
        }

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
        // The row is toggled through its click action, as the unknown-call test does, so the banner's position does not matter.
        compose.onNodeWithText("I understand this lets the spender take all of this token.").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action?.invoke()
        compose.waitForIdle()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun anUnlimitedApprovalWithoutItsTickStaysUnsignableOnceReadToTheEnd() {
        val unlimited = plainTransfer().copy(warnings = listOf(unlimitedApprovalWarning("USDC")))
        compose.setContent { LumenTheme { TxReviewScreen(review = unlimited, onApprove = {}, onReject = {}) } }

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun theHoldControlIsDisabledAndSaysWhyUntilTheReviewIsReadToTheEnd() {
        // A message this long is taller than the screen, so the review has to be scrolled to its end before it can be signed.
        val tall = messageReview().copy(params = listOf("message" to "Sign in to Lensly. ".repeat(400), "size" to "7600 characters"))
        compose.setContent { LumenTheme { TxReviewScreen(review = tall, onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aShortReviewWithNoWarningCanBeSignedAtOnce() {
        // The review fits on screen, so the fit counts as read and nothing else is owed.
        compose.setContent { LumenTheme { TxReviewScreen(review = plainTransfer(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("Scroll to the end to review everything").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun everyDetailIsShownOnFirstRenderWithoutOpeningAnySection() {
        compose.setContent { LumenTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("Transaction").assertExists()
        compose.onNodeWithText("Chain ID").assertExists()
        compose.onNodeWithText("Nonce").assertExists()
    }

    @Test
    fun theHeaderNamesTheNetworkAndTheSigningAccountWithItsWallet() {
        val review = plainTransfer()
        compose.setContent { LumenTheme { TxReviewScreen(review = review, onApprove = {}, onReject = {}) } }

        assertTrue(compose.onAllNodesWithText(review.networkName).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(review.fromAccountLabel).assertExists()
        compose.onNodeWithText("Main wallet").assertExists()
    }

    @Test
    fun aCounterpartyInTheAddressBookShowsItsEntryAndOneNotInItSaysSo() {
        val saved = plainTransfer().copy(
            addressNotes = mapOf(addressKeyOf(TO) to AddressNotes(internal = null, entry = AddressBookEntry("1", TO, "Alice", "Exchange"))),
        )
        compose.setContent { LumenTheme { TxReviewScreen(review = saved, onApprove = {}, onReject = {}) } }
        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText("Address label").assertExists()
        // The sender is not saved, so its row says so, while the saved recipient shows no such line.
        assertTrue(compose.onAllNodesWithText("Not in address book").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aCounterpartyNotInTheAddressBookIsSaidSoAsPlainText() {
        compose.setContent { LumenTheme { TxReviewScreen(review = plainTransfer(), onApprove = {}, onReject = {}) } }
        compose.onAllNodesWithText("Not in address book").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun aShortReviewWithAWarningShowsItsDetailsAndCanBeSignedOnceItFits() {
        // The review fits on screen. The warning has no check to tick, so the fit is the whole read.
        compose.setContent {
            LumenTheme {
                TxReviewScreen(review = plainTransfer().copy(warnings = listOf(NEW_RECIPIENT)), onApprove = {}, onReject = {})
            }
        }

        compose.onNodeWithText(NEW_RECIPIENT).assertExists()
        compose.onNodeWithText("Transaction").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aReviewWithAWarningSignsOnceItsListIsScrolledToTheEnd() {
        // In a window this short the details run past the bottom, so the review must be scrolled to its end.
        compose.setContent {
            LumenTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TxReviewScreen(review = plainTransfer().copy(warnings = listOf(NEW_RECIPIENT)), onApprove = {}, onReject = {})
                }
            }
        }

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Scroll to the end to review everything").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aWarningReviewStillOwesItsChecksOnceRead() {
        // An approval of an unregistered token owes two checks, the registry and the unlimited tick. Reading the review to its end
        // does not tick either, so both stay owed.
        compose.setContent { LumenTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkboxes above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWhitelistedCallOpensWithItsApprovedMeaningAndShowsEveryFactAndWord() {
        compose.setContent { LumenTheme { TxReviewScreen(review = whitelistedReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(APPROVED_MEANING).assertExists()
        compose.onNodeWithText(
            "Lensly cannot decode this call itself. It is on your signed whitelist with the meaning above. " +
                "Sign only if this is what you intend.",
        ).assertExists()
        compose.onNodeWithText("Transaction").performScrollTo().performClick()
        compose.onNodeWithText("swapExactTokensForTokens(uint256,uint256,address)").assertExists()
        compose.onNodeWithText(groupAddress(BASE_USDC)).assertExists()
        compose.onNodeWithText("Parameters").performScrollTo().performClick()
        compose.onNodeWithText("amountIn (uint256)").assertExists()
        compose.onNodeWithText("10").assertExists()
        // The raw words live in the folded section, so they are shown only when it opens.
        compose.onNodeWithText("Raw data").performScrollTo().performClick()
        compose.onNodeWithText("0x6e17e9f6").assertExists()
        compose.onNodeWithText("0 ETH").assertExists()
        SWAP_WORDS.forEachIndexed { index, word ->
            compose.onNodeWithText("Word ${index + 1}").assertExists()
            compose.onNodeWithText("0x" + groupHex(word)).assertExists()
        }
        compose.onNodeWithText("I checked this is what I intend").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWhitelistedCallShowsAToRowNamedFromTheAddressBookAndItsKnownContract() {
        val base = whitelistedReview()
        val review = base.copy(
            whitelisted = base.whitelisted?.copy(meaning = "Aave V3: deposit an asset"),
            addressNotes = mapOf(
                addressKeyOf(BASE_USDC) to AddressNotes(internal = null, entry = AddressBookEntry("2", BASE_USDC, "Base lending pool", null)),
            ),
        )
        compose.setContent { LumenTheme { TxReviewScreen(review = review, onApprove = {}, onReject = {}) } }

        compose.onNodeWithText("Transaction").performScrollTo().performClick()
        compose.onNodeWithText("To").assertExists()
        compose.onNodeWithText("Base lending pool").assertExists()
        compose.onNodeWithText("Known contract").assertExists()
        compose.onNodeWithText("Aave V3").assertExists()
        // The To row names the contract, so the approved-call block does not repeat it under a Contract label.
        compose.onAllNodesWithText("Contract").assertCountEquals(0)
    }

    @Test
    fun aPermitSummaryNamesItsSpenderFromTheAddressBook() {
        val spender = AddressNotes(internal = null, entry = AddressBookEntry("1", TO, "Uniswap Router", null))
        compose.setContent {
            LumenTheme { TxReviewScreen(review = permitReview(mapOf(addressKeyOf(TO) to spender)), onApprove = {}, onReject = {}) }
        }

        // The summary names the spender, and the Fields section names it again above its address.
        compose.onAllNodesWithText("Uniswap Router").assertCountEquals(2)
    }

    @Test
    fun anAccountOfThisDeviceAsAPermitSpenderShowsTheInternalCueAndItsWallet() {
        val spender = AddressNotes(
            internal = InternalAccount(displayName = null, autoName = "Savings 0", label = null, walletName = "Savings"),
            entry = null,
        )
        compose.setContent {
            LumenTheme { TxReviewScreen(review = permitReview(mapOf(addressKeyOf(TO) to spender)), onApprove = {}, onReject = {}) }
        }

        // The summary names the account, and the Fields section shows it as the bold line of its address, with its wallet under it.
        compose.onAllNodesWithText("Savings 0").assertCountEquals(1)
        compose.onAllNodesWithText("Your account · Savings 0").assertCountEquals(1)
        compose.onAllNodesWithText("Savings").assertCountEquals(1)
    }

    @Test
    fun anAccountOfAnotherWalletNamesThatWallet() {
        val account = InternalAccount(displayName = null, autoName = "Savings 0", label = null, walletName = "Savings")
        compose.setContent { LumenTheme { ReviewAddress(address = TO, notes = AddressNotes(internal = account, entry = null)) } }

        compose.onNodeWithText("Your account · Savings 0").assertExists()
        compose.onNodeWithText("Wallet").assertExists()
        compose.onNodeWithText("Savings").assertExists()
    }

    @Test
    fun aMulticallListsEachCallLevelByLevelWithItsTargetFunctionAndArguments() {
        compose.setContent { LumenTheme { TxReviewScreen(review = multicallReview(), onApprove = {}, onReject = {}) } }

        // A call outside the registry earns the same warnings a lone call does, and each sits in the banner with its check.
        compose.onNodeWithText("approve: $UNLIMITED").assertExists()
        compose.onNodeWithText("approve: outside Lensly's registry. Check its chain ID and contract.").assertExists()
        // A call that matches the denylist shows its reason and needs the risky-request check.
        compose.onNodeWithText("approve: known risky request. $DENYLIST_REASON").assertExists()
        compose.onNodeWithText("I understand this is a risky request").assertExists()
        compose.onNodeWithText("Calls").performScrollTo().performClick()
        compose.onNodeWithText("approve").assertExists()
        compose.onNodeWithText(UNKNOWN_DECIMALS_NOTE).assertExists()
        compose.onNodeWithText("aggregate3").assertExists()
        compose.onNodeWithText("allowFailure (bool)").assertExists()
        compose.onNodeWithText("sweepToken").assertExists()
        compose.onNodeWithText("Target").assertExists()
        // The sweep's target and its token argument are the same contract, so it shows under each.
        compose.onAllNodesWithText(groupAddress(BASE_USDC)).assertCountEquals(2)
        compose.onNodeWithText("token (address)").assertExists()
        compose.onNodeWithText("amountMinimum (uint256)").assertExists()
        // The whitelisted call inside the batch carries its warning in the banner, and the owner must tick its check.
        compose.onNodeWithText(
            "sweepToken: Lensly cannot decode this call itself. It is on your signed whitelist with the meaning above. " +
                "Sign only if this is what you intend.",
        ).assertExists()
        compose.onAllNodesWithText("I checked this is what I intend").assertCountEquals(1)
        compose.assertNoTextIsCutShort()
    }

    @Test
    fun aProxyUnderWarnAndConfirmShowsItsRedWarningAndASecondCheck() {
        val proxy = ProxyBinding(implementationAddress = IMPLEMENTATION)
        compose.setContent {
            LumenTheme { TxReviewScreen(review = whitelistedReview(proxy), onApprove = {}, onReject = {}) }
        }

        compose.onNodeWithText(UPGRADE_WARNING).assertExists()
        compose.onNodeWithText("Transaction").performScrollTo().performClick()
        compose.onNodeWithText("Approved implementation").assertExists()
        compose.onNodeWithText(groupAddress(IMPLEMENTATION)).assertExists()
        compose.onNodeWithText("I checked this is what I intend").assertExists()
        compose.onNodeWithText("I accept the implementation cannot be checked").assertExists()
    }

    @Test
    fun anUnlimitedApprovalWarningIsShownInFull() {
        compose.setContent { LumenTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(UNLIMITED).assertExists()
    }

    @Test
    fun aLongMessageIsShownWholeWithItsSize() {
        compose.setContent { LumenTheme { TxReviewScreen(review = messageReview(), onApprove = {}, onReject = {}) } }

        compose.onNodeWithText(LONG_MESSAGE).assertExists()
        compose.onNodeWithText("${LONG_MESSAGE.length} characters, ${LONG_MESSAGE.length} bytes").assertExists()
    }

    @Test
    fun noTextOnAnApprovalReviewIsCutShort() {
        compose.setContent { LumenTheme { TxReviewScreen(review = approvalReview(), onApprove = {}, onReject = {}) } }

        compose.assertNoTextIsCutShort()
    }

    @Test
    fun noTextOnAMessageReviewIsCutShort() {
        compose.setContent { LumenTheme { TxReviewScreen(review = messageReview(), onApprove = {}, onReject = {}) } }

        compose.assertNoTextIsCutShort()
    }

    /** The check must fail on a text that a line limit cuts, or it would pass every review. */
    @Test
    fun theCutCheckCatchesATextThatALineLimitCuts() {
        compose.setContent {
            LumenTheme {
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
            LumenTheme { SignRefusedScreen(reason = "Unknown contract call", onDone = {}, call = call) }
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
    fun aRefusedProxyShowsTheImplementationItIsBoundTo() {
        val call = UndecodedCall(
            target = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            networkName = "Ethereum",
            chainId = 1L,
            value = "0 ETH",
            selector = "7acb7757",
            words = words,
            proxy = ProxyBinding(implementationAddress = IMPLEMENTATION),
        )
        compose.setContent {
            LumenTheme {
                SignRefusedScreen(reason = "This contract is an upgradeable proxy.", onDone = {}, call = call)
            }
        }

        compose.onNodeWithText("Approved implementation").assertExists()
        compose.onNodeWithText(groupAddress(IMPLEMENTATION)).assertExists()
    }
}
