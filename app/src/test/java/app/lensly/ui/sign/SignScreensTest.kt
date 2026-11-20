package app.lensly.ui.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.EVM_FAMILY
import app.lensly.app.InternalAccount
import app.lensly.app.SigningAccount
import app.lensly.app.addressKeyOf
import app.lensly.core.registry.ProxyBinding
import app.lensly.ui.components.groupAddress
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import java.math.BigInteger
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
private const val LONG_ACCOUNT = "ACCOUNT WITH A LONG DISPLAY NAME 12"
private const val LONG_ORIGIN = "dapp.example.invalid/a/very/long/origin/path/that/keeps/going/and/going/on"
private const val BASE_USDC = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
private const val MAX_UINT256_DECIMAL = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
private const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
private const val APPROVED_MEANING = "Swap an exact amount along the route in the call"
private const val SWAP_ARGUMENT_TO = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"

/** The warning the builder gives a whitelisted proxy under Warn and confirm. */
private const val UPGRADE_WARNING = "This contract is an upgradeable proxy. Lensly cannot confirm offline that its code is still the " +
    "version the signed list approves."
private const val UNLIMITED_TEXT = "Unlimited approval: this contract can spend all your USDC"
private const val UNKNOWN_CALL_TEXT = "Unknown contract call. Lensly does not read this call, so it cannot say what it does. " +
    "Check the contract and the calldata before you sign."
private const val DENYLIST_REASON = "Unlimited approval: the spender can take every token."
private const val NEW_RECIPIENT = "This address has never received a transaction on this network."
private const val UNKNOWN_DECIMALS_NOTE = "Decimals unknown: raw integer amount, not verified"
private const val TOKEN_REGISTRY_TEXT = "This contract is not in Lensly's token registry, so Lensly cannot check its name or decimals."
private const val PERSONAL_MESSAGE_TEXT = "The signature proves you hold this account to whoever checks it, and a site can use it " +
    "to act for you. Sign only a message from a site you trust."

/** The checks the builder asks the owner to tick, by the label each one shows. */
private const val CHECK_REGISTRY = "I have checked the chain ID and the contract."
private const val CHECK_UNLIMITED = "I understand this approval is unlimited."
private const val CHECK_UNKNOWN_CALL = "I understand Lensly cannot say what this call does."
private const val CHECK_WHITELIST = "I have read what this call is approved to do."
private const val CHECK_UPGRADE = "I accept that this proxy's code cannot be checked offline."
private const val CHECK_DENYLIST = "I understand this request matches a known-dangerous request."

private val BASE_CHAIN = ChainId("eip155:8453")
private val BASE_NETWORK = NetworkLabel(name = "Base", testnet = false, detail = "Chain ID 8453")
private val PLAIN_SIGNER = SigningAccount(name = "MAIN ACCOUNT", walletName = "Main wallet", address = FROM)
private val LONG_SIGNER = SigningAccount(name = LONG_ACCOUNT, walletName = LONG_WALLET, address = FROM)

/** A message that is long, wraps over several lines, and has a size. */
private val LONG_MESSAGE = "Sign in to Lensly. " + "This message is long and keeps going so that it wraps over many lines. ".repeat(12)

private val unlimitedWarning = Warning(Danger.DANGER, "evm.unlimited-approval", UNLIMITED_TEXT, acknowledge = CHECK_UNLIMITED)
private val unknownCallWarning = Warning(Danger.DANGER, "evm.unknown-call", UNKNOWN_CALL_TEXT, acknowledge = CHECK_UNKNOWN_CALL)
private val newRecipientWarning = Warning(Danger.CAUTION, "evm.first-receipt", NEW_RECIPIENT)

private fun coverage() = Coverage(decoded = emptySet(), excluded = emptyMap())

/** The Gas Limit and the Max Fee of a Base transaction, which every sample below shows under its sentence. */
private fun baseFacts() = listOf(
    ReviewRow("Gas Limit", DisplayValue.Number(BigInteger.valueOf(46_000)), null),
    ReviewRow("Max Fee", DisplayValue.Text("0.0001 ETH"), null),
)

/** A native transfer of a known coin on Base, with the warnings given. It is short, so it fits on screen unless a warning is long. */
private fun plainTransfer(warnings: List<Warning> = emptyList()) = Review(
    title = "Native Transfer",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(SummaryPart.Send("0.01", "ETH", FROM, TO)),
    facts = listOf(
        ReviewRow("Gas Limit", DisplayValue.Number(BigInteger.valueOf(21_000)), null),
        ReviewRow("Max Fee", DisplayValue.Text("0.00063 ETH"), null),
    ),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("To", DisplayValue.Address(BASE_CHAIN, TO), null),
                ReviewRow("Amount", DisplayValue.Text("0.01 ETH"), null),
                ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null),
                ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null),
            ),
        ),
    ),
    warnings = warnings,
    coverage = coverage(),
)

/**
 * An unlimited approval of a token outside the registry, with the origin given, so the header and the rows carry as long a value
 * as a request can.
 */
private fun approvalReview(origin: String? = LONG_ORIGIN) = Review(
    title = "approve",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(
        SummaryPart.Text("Approves "),
        SummaryPart.Counterparty(TO),
        SummaryPart.Text(" to spend "),
        SummaryPart.Text("an unlimited amount"),
        SummaryPart.Text("."),
    ),
    facts = baseFacts(),
    sections = listOf(
        ReviewSection(
            "Transaction",
            buildList {
                add(ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null))
                origin?.let { add(ReviewRow("Claimed by Request (Not Verified)", DisplayValue.Text(it), null)) }
                add(ReviewRow("Contract", DisplayValue.Address(BASE_CHAIN, BASE_USDC), null))
                add(ReviewRow("Function", DisplayValue.Text("approve(address,uint256)"), null))
                add(ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null))
                add(ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null))
            },
        ),
        ReviewSection(
            "Arguments",
            listOf(
                ReviewRow("spender", DisplayValue.Address(BASE_CHAIN, TO), null),
                ReviewRow("value", DisplayValue.Text("$MAX_UINT256_DECIMAL base units (unknown token, decimals unknown)"), null),
            ),
        ),
    ),
    warnings = listOf(
        unlimitedWarning,
        Warning(Danger.CAUTION, "evm.registry", TOKEN_REGISTRY_TEXT, acknowledge = CHECK_REGISTRY),
    ),
    coverage = coverage(),
)

/** A call Lensly cannot decode that the whitelist approves: its approved meaning, its arguments, and the proxy when it has one. */
private fun whitelistedReview(proxy: ProxyBinding? = null) = Review(
    title = "swapExactTokensForTokens",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(
        SummaryPart.Text("Calls swapExactTokensForTokens on "),
        SummaryPart.Counterparty(BASE_USDC),
        SummaryPart.Text(", which the signed list approves."),
    ),
    facts = baseFacts(),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("Contract", DisplayValue.Address(BASE_CHAIN, BASE_USDC), null),
                ReviewRow("Function", DisplayValue.Text("swapExactTokensForTokens(uint256,uint256,address)"), null),
                ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null),
                ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null),
            ),
        ),
        ReviewSection(
            "Arguments",
            buildList {
                add(ReviewRow("Approved Meaning", DisplayValue.Text(APPROVED_MEANING), null))
                add(ReviewRow("amountIn (uint256)", DisplayValue.Text("1"), null))
                add(ReviewRow("amountOutMin (uint256)", DisplayValue.Text("10"), null))
                add(ReviewRow("to (address)", DisplayValue.Address(BASE_CHAIN, SWAP_ARGUMENT_TO), null))
                proxy?.implementationAddress?.let { add(ReviewRow("Implementation", DisplayValue.Address(BASE_CHAIN, it), null)) }
            },
        ),
    ),
    warnings = buildList {
        add(
            Warning(
                Danger.CAUTION,
                "evm.whitelisted-call",
                "Lensly cannot read this call. The signed list approves it: $APPROVED_MEANING",
                acknowledge = CHECK_WHITELIST,
            ),
        )
        if (proxy != null) {
            add(Warning(Danger.DANGER, "evm.proxy", UPGRADE_WARNING, acknowledge = CHECK_UPGRADE))
        }
    },
    coverage = coverage(),
)

/** A multicall: an unlimited approval that matches the denylist, a whitelisted sweep one level down, and the calls between them. */
private fun multicallReview() = Review(
    title = "multicall",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(SummaryPart.Text("Runs 3 calls in one transaction.")),
    facts = baseFacts(),
    sections = listOf(
        ReviewSection(
            "Transaction",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("Contract", DisplayValue.Address(BASE_CHAIN, TO), null),
                ReviewRow("Function", DisplayValue.Text("multicall(uint256,bytes[])"), null),
                ReviewRow("deadline (uint256)", DisplayValue.Number(BigInteger.valueOf(1_700_000_000L)), null),
                ReviewRow("Transaction Type", DisplayValue.Text("EIP-1559"), null),
                ReviewRow("Nonce", DisplayValue.Number(BigInteger.valueOf(42)), null),
            ),
        ),
        ReviewSection(
            "Calls",
            listOf(
                ReviewRow("Call 1", DisplayValue.Text("approve"), null),
                ReviewRow("spender", DisplayValue.Address(BASE_CHAIN, TO), null, depth = 1),
                ReviewRow("value", DisplayValue.Text("10"), null, depth = 1),
                ReviewRow("Amount Note", DisplayValue.Text(UNKNOWN_DECIMALS_NOTE), null, depth = 1),
                ReviewRow("Call 2", DisplayValue.Text("aggregate3"), null),
                ReviewRow("allowFailure (bool)", DisplayValue.Text("false"), null, depth = 1),
                ReviewRow("Call 1", DisplayValue.Text("sweepToken"), null, depth = 1),
                ReviewRow("Contract", DisplayValue.Address(BASE_CHAIN, BASE_USDC), null, depth = 2),
                ReviewRow("token (address)", DisplayValue.Address(BASE_CHAIN, BASE_USDC), null, depth = 2),
                ReviewRow("amountMinimum (uint256)", DisplayValue.Text("10"), null, depth = 2),
                ReviewRow("Approved Meaning", DisplayValue.Text(APPROVED_MEANING), null, depth = 2),
            ),
        ),
    ),
    warnings = listOf(
        unlimitedWarning,
        Warning(Danger.DANGER, "evm.denylist", DENYLIST_REASON, acknowledge = CHECK_DENYLIST),
        Warning(Danger.CAUTION, "evm.registry", TOKEN_REGISTRY_TEXT, acknowledge = CHECK_REGISTRY),
        Warning(
            Danger.CAUTION,
            "evm.whitelisted-call",
            "Lensly cannot read this call. The signed list approves it: $APPROVED_MEANING",
            acknowledge = CHECK_WHITELIST,
        ),
    ),
    coverage = coverage(),
)

/** A Permit of the Base USDC token from [FROM] to [TO], as the typed-data review shows it. */
private fun permitReview() = Review(
    title = "Permit",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(SummaryPart.Text("Signs typed data of type Permit.")),
    facts = emptyList(),
    sections = listOf(
        ReviewSection(
            "Request",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("Type", DisplayValue.Text("Typed data (EIP-712)"), null),
            ),
        ),
        ReviewSection(
            "Domain",
            listOf(
                ReviewRow("Name", DisplayValue.Text("USD Coin"), null),
                ReviewRow("Version", DisplayValue.Text("2"), null),
                ReviewRow("Chain ID", DisplayValue.Text("8453 (Base)"), null),
                ReviewRow("Verifying Contract", DisplayValue.Address(BASE_CHAIN, BASE_USDC), null),
            ),
        ),
        ReviewSection(
            "Summary",
            listOf(
                ReviewRow("Owner", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("Spender", DisplayValue.Address(BASE_CHAIN, TO), null),
                ReviewRow("Amount", DisplayValue.Text("100 USDC"), null),
                ReviewRow("Deadline", DisplayValue.Text("1 Jan 2030, 00:00 UTC"), null),
            ),
        ),
        ReviewSection(
            "Message",
            listOf(
                ReviewRow("owner (address)", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("spender (address)", DisplayValue.Address(BASE_CHAIN, TO), null),
                ReviewRow("value (uint256)", DisplayValue.Text("100000000"), null),
                ReviewRow("nonce (uint256)", DisplayValue.Text("0"), null),
                ReviewRow("deadline (uint256)", DisplayValue.Text("1893456000"), null),
            ),
        ),
    ),
    warnings = emptyList(),
    coverage = coverage(),
)

/** A personal message, shown whole with its size. */
private fun messageReview(message: String = LONG_MESSAGE) = Review(
    title = "Sign Message",
    chain = BASE_CHAIN,
    network = BASE_NETWORK,
    summary = listOf(SummaryPart.Text("Signs a message with this account.")),
    facts = emptyList(),
    sections = listOf(
        ReviewSection(
            "Request",
            listOf(
                ReviewRow("From", DisplayValue.Address(BASE_CHAIN, FROM), null),
                ReviewRow("Type", DisplayValue.Text("Personal Message"), null),
            ),
        ),
        ReviewSection(
            "Message",
            listOf(
                ReviewRow("Message", DisplayValue.Message(message), null),
                ReviewRow("Size", DisplayValue.Text("${message.length} characters, ${message.length} bytes"), null),
            ),
        ),
    ),
    warnings = listOf(Warning(Danger.CAUTION, "evm.personal-message", PERSONAL_MESSAGE_TEXT)),
    coverage = coverage(),
)

/**
 * Shows [review] as the sign route does: the signer, the Address Book names in [notes], and no icons. A [shortWindow] is too short for
 * the review, so its details run past the bottom.
 */
private fun ComposeContentTestRule.setEvm(
    review: Review,
    signer: SigningAccount = PLAIN_SIGNER,
    notes: Map<String, AddressNotes> = emptyMap(),
    shortWindow: Boolean = false,
) {
    setContent {
        LumenTheme {
            var ticked by remember { mutableStateOf(emptySet<String>()) }
            val screen: @Composable () -> Unit = {
                ReviewScreen(
                    review = review,
                    signer = signer,
                    addressNotes = notes,
                    addressChain = EVM_FAMILY,
                    ticked = ticked,
                    onTick = { label, on -> ticked = if (on) ticked + label else ticked - label },
                    onApprove = {},
                    onReject = {},
                )
            }
            if (shortWindow) {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) { screen() }
            } else {
                screen()
            }
        }
    }
}

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
class EvmReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRequestNamesTheAccountOriginAndNetworkInTheHeaderAndTheWalletInItsDetails() {
        compose.setEvm(approvalReview(), signer = LONG_SIGNER)

        compose.onNodeWithText(LONG_ACCOUNT).assertExists()
        compose.onNodeWithText("Base").assertExists()
        compose.onNodeWithText("Claimed by Request (Not Verified)").assertExists()
        compose.onNodeWithText(LONG_ORIGIN).assertExists()
        compose.onNodeWithText(LONG_WALLET).assertExists()
        // The header and the From row both show the signing address, so it is found among the matches.
        assertTrue(compose.onAllNodesWithText(groupAddress(FROM)).fetchSemanticsNodes().isNotEmpty())
    }

    /** An unknown call is read, and still waits for its tick: the sign control opens only once both are done. */
    @Test
    fun anUnknownCallSignsOnlyAfterItsTickAndItsRead() {
        compose.setEvm(plainTransfer(listOf(unknownCallWarning)), shortWindow = true)

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
        // The row is toggled through its click action, so the tick does not depend on the banner being above the bottom action in this window.
        compose.onNodeWithText(CHECK_UNKNOWN_CALL).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action?.invoke()
        compose.waitForIdle()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun anUnknownCallWithoutItsTickStaysUnsignableOnceReadToTheEnd() {
        compose.setEvm(plainTransfer(listOf(unknownCallWarning)), shortWindow = true)

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun theAccessListIsItsOwnSectionWithEachAddressAndStorageKey() {
        val warmed = plainTransfer().copy(
            sections = plainTransfer().sections + ReviewSection(
                "Access List",
                listOf(
                    ReviewRow("Address", DisplayValue.Address(BASE_CHAIN, TO), null),
                    ReviewRow("Storage Key", DisplayValue.Text("0x" + "22".repeat(32), mono = true), null, depth = 1),
                ),
            ),
        )
        compose.setEvm(warmed)

        compose.onNodeWithText("Access List").assertExists()
        // The label "Address" is shown by more than one row of the review, so the test looks for any of them.
        assertTrue(compose.onAllNodesWithText("Address").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Storage Key").assertExists()
    }

    /** An unlimited approval is read, and still waits for its tick: the sign control opens only once both are done. */
    @Test
    fun anUnlimitedApprovalSignsOnlyAfterItsTickAndItsRead() {
        compose.setEvm(plainTransfer(listOf(unlimitedWarning)), shortWindow = true)

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
        // The row is toggled through its click action, as the unknown-call test does, so the banner's position does not matter.
        compose.onNodeWithText(CHECK_UNLIMITED).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action?.invoke()
        compose.waitForIdle()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun anUnlimitedApprovalWithoutItsTickStaysUnsignableOnceReadToTheEnd() {
        compose.setEvm(plainTransfer(listOf(unlimitedWarning)))

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun theHoldControlIsDisabledAndSaysWhyUntilTheReviewIsReadToTheEnd() {
        // A message this long is taller than the screen, so the review has to be scrolled to its end before it can be signed.
        compose.setEvm(messageReview("Sign in to Lensly. ".repeat(400)))

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aShortReviewWithNoWarningCanBeSignedAtOnce() {
        // The review fits on screen, so the fit counts as read and nothing else is owed.
        compose.setEvm(plainTransfer())

        compose.onNodeWithText("Scroll to the end to review everything").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun everyDetailIsShownOnFirstRenderWithoutOpeningAnySection() {
        compose.setEvm(approvalReview())

        compose.onNodeWithText("Transaction").assertExists()
        compose.onNodeWithText("Nonce").assertExists()
        compose.onNodeWithText("Chain ID 8453").assertExists()
    }

    @Test
    fun theHeaderNamesTheNetworkAndTheSigningAccountWithItsWallet() {
        compose.setEvm(plainTransfer())

        assertTrue(compose.onAllNodesWithText("Base").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("MAIN ACCOUNT").assertExists()
        compose.onNodeWithText("Main wallet").assertExists()
    }

    @Test
    fun aCounterpartyInTheAddressBookShowsItsEntryAndOneNotInItSaysSo() {
        val notes = mapOf(addressKeyOf(TO) to AddressNotes(internal = null, entry = AddressBookEntry("1", TO, "Alice", "Exchange")))
        compose.setEvm(plainTransfer(), notes = notes)

        compose.onNodeWithText("Alice").assertExists()
        compose.onNodeWithText("Address Label").assertExists()
        // The sender is not saved, so its row says so, while the saved recipient shows no such line.
        assertTrue(compose.onAllNodesWithText("Not in address book").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aCounterpartyNotInTheAddressBookIsSaidSoAsPlainText() {
        compose.setEvm(plainTransfer())

        assertTrue(compose.onAllNodesWithText("Not in address book").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aShortReviewWithAWarningShowsItsDetailsAndCanBeSignedOnceItFits() {
        // The review fits on screen. The warning has no check to tick, so the fit is the whole read.
        compose.setEvm(plainTransfer(listOf(newRecipientWarning)))

        compose.onNodeWithText(NEW_RECIPIENT).assertExists()
        compose.onNodeWithText("Transaction").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aReviewWithAWarningSignsOnceItsListIsScrolledToTheEnd() {
        // In a window this short the details run past the bottom, so the review must be scrolled to its end.
        compose.setEvm(plainTransfer(listOf(newRecipientWarning)), shortWindow = true)

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
        compose.setEvm(approvalReview())

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Tick the checkboxes above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWhitelistedCallOpensWithItsApprovedMeaningItsArgumentsAndItsCheck() {
        compose.setEvm(whitelistedReview())

        compose.onNodeWithText(APPROVED_MEANING).assertExists()
        compose.onNodeWithText("Lensly cannot read this call. The signed list approves it: $APPROVED_MEANING").assertExists()
        compose.onNodeWithText("swapExactTokensForTokens(uint256,uint256,address)").assertExists()
        compose.onNodeWithText(groupAddress(BASE_USDC)).assertExists()
        compose.onNodeWithText("amountIn (uint256)").assertExists()
        compose.onNodeWithText("10").assertExists()
        compose.onNodeWithText(CHECK_WHITELIST).assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWhitelistedCallShowsItsContractByTheNameFromTheAddressBook() {
        val notes = mapOf(addressKeyOf(BASE_USDC) to AddressNotes(internal = null, entry = AddressBookEntry("2", BASE_USDC, "Base lending pool", null)))
        compose.setEvm(whitelistedReview(), notes = notes)

        compose.onNodeWithText("Base lending pool").assertExists()
        compose.onNodeWithText("Contract").assertExists()
    }

    @Test
    fun aPermitSummaryNamesItsSpenderFromTheAddressBook() {
        val spender = AddressNotes(internal = null, entry = AddressBookEntry("1", TO, "Uniswap Router", null))
        compose.setEvm(permitReview(), notes = mapOf(addressKeyOf(TO) to spender))

        // The Summary and the Message sections each name the spender.
        compose.onAllNodesWithText("Uniswap Router").assertCountEquals(2)
    }

    @Test
    fun anAccountOfThisDeviceAsAPermitSpenderShowsTheInternalCueAndItsWallet() {
        val spender = AddressNotes(
            internal = InternalAccount(displayName = null, autoName = "Savings 0", label = null, walletName = "Savings"),
            entry = null,
        )
        compose.setEvm(permitReview(), notes = mapOf(addressKeyOf(TO) to spender))

        // The Summary and the Message sections each show the account as the bold line of its address, with its wallet under it.
        compose.onAllNodesWithText("Your account · Savings 0").assertCountEquals(2)
        compose.onAllNodesWithText("Savings").assertCountEquals(2)
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
        compose.setEvm(multicallReview())

        // A call outside the registry earns the same warnings a lone call does, and each sits in the banner with its check.
        compose.onNodeWithText(UNLIMITED_TEXT).assertExists()
        compose.onNodeWithText(DENYLIST_REASON).assertExists()
        compose.onNodeWithText(CHECK_DENYLIST).assertExists()
        compose.onNodeWithText(CHECK_REGISTRY).assertExists()
        compose.onNodeWithText("approve").assertExists()
        compose.onNodeWithText(UNKNOWN_DECIMALS_NOTE).assertExists()
        compose.onNodeWithText("aggregate3").assertExists()
        compose.onNodeWithText("allowFailure (bool)").assertExists()
        compose.onNodeWithText("sweepToken").assertExists()
        // The sweep's target and its token argument are the same contract, so it shows under each.
        compose.onAllNodesWithText(groupAddress(BASE_USDC)).assertCountEquals(2)
        compose.onNodeWithText("token (address)").assertExists()
        compose.onNodeWithText("amountMinimum (uint256)").assertExists()
        // The whitelisted call inside the batch carries its warning in the banner, and the owner must tick its check.
        compose.onAllNodesWithText(CHECK_WHITELIST).assertCountEquals(1)
        compose.assertNoTextIsCutShort()
    }

    @Test
    fun aProxyUnderWarnAndConfirmShowsItsRedWarningAndASecondCheck() {
        compose.setEvm(whitelistedReview(ProxyBinding(implementationAddress = IMPLEMENTATION)))

        compose.onNodeWithText(UPGRADE_WARNING).assertExists()
        compose.onNodeWithText("Implementation").assertExists()
        compose.onNodeWithText(groupAddress(IMPLEMENTATION)).assertExists()
        compose.onNodeWithText(CHECK_WHITELIST).assertExists()
        compose.onNodeWithText(CHECK_UPGRADE).assertExists()
    }

    @Test
    fun anUnlimitedApprovalWarningIsShownInFull() {
        compose.setEvm(approvalReview())

        compose.onNodeWithText(UNLIMITED_TEXT).assertExists()
    }

    @Test
    fun aLongMessageIsShownWholeWithItsSize() {
        compose.setEvm(messageReview())

        compose.onNodeWithText(LONG_MESSAGE).assertExists()
        compose.onNodeWithText("${LONG_MESSAGE.length} characters, ${LONG_MESSAGE.length} bytes").assertExists()
    }

    @Test
    fun noTextOnAnApprovalReviewIsCutShort() {
        compose.setEvm(approvalReview())

        compose.assertNoTextIsCutShort()
    }

    @Test
    fun noTextOnAMessageReviewIsCutShort() {
        compose.setEvm(messageReview())

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

/**
 * The rows a refusal of an undecodable call shows, laid out as the EVM review builds them: the contract, the network with its chain, the native
 * value and the selector, the implementation a bound proxy is bound to, and each calldata word under the Calldata heading.
 */
internal fun undecodedCallRows(
    target: String,
    networkName: String,
    chainId: Long,
    value: String,
    selector: String,
    words: List<String>,
    proxy: ProxyBinding? = null,
): List<ReviewRow> {
    val chain = ChainId("eip155:$chainId")
    return buildList {
        add(ReviewRow("Contract", DisplayValue.Address(chain, target), null))
        add(ReviewRow("Network", DisplayValue.Text(networkName), null, notes = listOf("Chain $chainId")))
        add(ReviewRow("Value", DisplayValue.Text(value, mono = true), null))
        add(ReviewRow("Selector", DisplayValue.Text("0x$selector", mono = true), null))
        proxy?.implementationAddress?.let { add(ReviewRow("Approved Implementation", DisplayValue.Address(chain, it), null)) }
        add(ReviewRow("Calldata", null, null))
        words.forEachIndexed { index, word ->
            add(ReviewRow("WORD ${index + 1}", DisplayValue.Text("0x" + groupHex(word), mono = true), null, depth = 1))
        }
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
        val rows = undecodedCallRows(
            target = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            networkName = "Ethereum",
            chainId = 1L,
            value = "0.5 ETH",
            selector = "70a08231",
            words = words,
        )
        compose.setContent {
            LumenTheme { SignRefusedScreen(reason = "Unknown Contract Call", onDone = {}, rows = rows) }
        }

        compose.onNodeWithText(
            "Lensly cannot decode this call, so it will not sign it. The details below are the whole call.",
        ).assertExists()
        compose.onNodeWithText(groupAddress("0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb")).assertExists()
        compose.onNodeWithText("Ethereum").assertExists()
        compose.onNodeWithText("Chain 1").assertExists()
        compose.onNodeWithText("0.5 ETH").assertExists()
        compose.onNodeWithText("0x70a08231").assertExists()
        words.forEachIndexed { index, word ->
            compose.onNodeWithText("WORD ${index + 1}").assertExists()
            compose.onNodeWithText("0x" + groupHex(word)).assertExists()
        }
    }

    @Test
    fun aRefusedProxyShowsTheImplementationItIsBoundTo() {
        val rows = undecodedCallRows(
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
                SignRefusedScreen(reason = "This contract is an upgradeable proxy.", onDone = {}, rows = rows)
            }
        }

        compose.onNodeWithText("Approved Implementation").assertExists()
        compose.onNodeWithText(groupAddress(IMPLEMENTATION)).assertExists()
    }
}
