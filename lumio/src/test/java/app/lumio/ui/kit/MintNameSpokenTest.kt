package app.lumio.ui.kit

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.VerbatimTtsAnnotation
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.chains.ChainHoldings
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendOption
import app.lumio.ui.send.SendOptionSheet
import app.lumio.ui.send.SendReviewPage
import app.lumio.ui.send.SummaryRow
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetScreen
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** An unlisted Solana mint that titles a row is read verbatim, group by group, as an address is (#1692, spec 11.1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MintNameSpokenTest {
    @get:Rule
    val compose = createComposeRule()

    private val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test
    fun aMintRowSaysItsGroupsVerbatimWithToForTheEllipsis() {
        val name = ChainHoldings.mintName(mint)
        compose.setContent { ClearAirTheme { ClearAirRow(title = name, description = "Solana Devnet") } }

        val spoken = compose.onNodeWithText("EPjF, Wdd5, to ZwyT, Dt1v", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].first()
        assertEquals("EPjF, Wdd5, to ZwyT, Dt1v", spoken.text)
        assertEquals(listOf("EPjF", "Wdd5", "ZwyT", "Dt1v"), spoken.getTtsAnnotations(0, spoken.length).map { (it.item as VerbatimTtsAnnotation).verbatim })
    }

    @Test
    fun aListedSymbolIsReadAsWritten() {
        assertNull(spokenMintName("USDC"))
        assertEquals(AnnotatedString("USDC").text, "USDC")
    }

    private val name = ChainHoldings.mintName(mint)
    private val said = "EPjF, Wdd5, to ZwyT, Dt1v"

    /** Every text a screen reader can reach on the screen, as it is read. */
    private fun read(): List<String> = compose
        .onAllNodes(SemanticsMatcher("has text") { SemanticsProperties.Text in it.config }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text.trim() } }

    private fun assertRead(vararg expected: String) {
        val heard = read()
        expected.forEach { assertTrue(it in heard, "\"$it\" is not read; the screen reads $heard") }
        assertTrue(heard.none { name in it }, "the mint is read as one run of letters in $heard")
    }

    @Test
    fun aMintWithinALineIsReadVerbatimAndTheRestAsWritten() {
        val spoken = spokenMintName("1.5 $name available")!!
        assertEquals("1.5 $said available", spoken.text)
        assertEquals(listOf("EPjF", "Wdd5", "ZwyT", "Dt1v"), spoken.getTtsAnnotations(0, spoken.length).map { (it.item as VerbatimTtsAnnotation).verbatim })
        assertNull(spokenMintName("More than your available 1.5 USDC"))
    }

    @Test
    fun theAssetPageHeroEyebrowAndSendActionReadTheMintInGroups() {
        val asset = AssetRowModel(
            assetKey = mint,
            symbol = name,
            name = name,
            network = "Solana Devnet",
            amountText = "1.5",
            usd = null,
            icon = AssetIconSpec.Letter("E"),
            assetId = null,
            isNative = false,
            testnet = false,
            zero = false,
        )
        compose.setContent { ClearAirTheme { AssetScreen(asset = asset, showBalance = true, onSend = {}, onReceive = {}) } }

        assertRead("1.5 $said", said, "Send $said")
    }

    @Test
    fun thePageHeaderReadsTheMintInGroups() {
        compose.setContent { ClearAirTheme { ClearAirPageTopBar(title = name, backLabel = null, onBack = {}, onLock = {}) } }

        assertRead(said)
    }

    @Test
    fun theSendAmountPageReadsTheMintInGroups() {
        compose.setContent {
            ClearAirTheme {
                SendAmountPage(
                    symbol = name,
                    toTitle = "Ana",
                    toDetail = "",
                    toAddress = "7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr",
                    fromTitle = "Felix",
                    fromDetail = "1.5 $name available",
                    asset = name,
                    onAsset = {},
                    amount = "9",
                    onAmount = {},
                    amountError = "More than your available 1.5 $name",
                    leaves = null,
                    onUseMax = null,
                    networkName = "Solana Devnet",
                    feeText = null,
                    onFee = null,
                    note = null,
                    canReview = false,
                    onReview = {},
                    onChangeRecipient = {},
                    backLabel = "Home",
                    offline = null,
                    onBack = {},
                    onLock = {},
                )
            }
        }

        assertRead("Send $said", said, "1.5 $said available", "! More than your available 1.5 $said")
    }

    @Test
    fun theSendReviewReadsTheMintInGroups() {
        compose.setContent {
            ClearAirTheme {
                SendReviewPage(
                    heading = "Send 1.5 $name from Felix to Ana?",
                    toTitle = "Ana",
                    toDetail = "",
                    toAddress = "7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr",
                    validLine = "Address is valid on Solana Devnet",
                    firstTime = null,
                    rows = listOf(SummaryRow("Amount", "1.5 $name")),
                    warnings = emptyList(),
                    notice = null,
                    onShow = {},
                    onEdit = {},
                    backLabel = "Amount",
                    offline = null,
                    onBack = {},
                    onLock = {},
                )
            }
        }

        assertRead("Send 1.5 $said from Felix to Ana?", "1.5 $said")
    }

    @Test
    fun theSendAssetChoiceReadsTheMintInGroups() {
        compose.setContent {
            ClearAirTheme { SendOptionSheet(visible = true, heading = "Asset", options = listOf(SendOption(name, "Balance 1.5 $name")), onPick = {}, onClose = {}) }
        }

        assertRead(said, "Balance 1.5 $said")
    }
}
