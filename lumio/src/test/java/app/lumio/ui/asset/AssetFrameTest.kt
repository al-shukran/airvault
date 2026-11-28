package app.lumio.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Asset page against the approved Ethereum frame: its elements in the frame's order, one filled Send capsule with Receive as a text action, the range
 * row with the chosen range marked, the "Your ETH" and "Activity" sections, and the frame's own measures. The named exceptions are tested too: a price-only
 * page keeps no holdings or actions, a page that no account can send from has Receive as its capsule, and a coin nobody holds does the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1080dp-h2400dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssetFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val page = DpSize(360.dp, 780.dp)
    private val logoSizes = mutableListOf<Dp>()
    private var paper = Color.Unspecified
    private var primary = Color.Unspecified
    private var allTaps = 0

    private fun show(held: CoinHeld? = HELD, priceOnly: Boolean = false, senders: Boolean = true, range: ChartFrame = ChartFrame.D1, priceText: String? = "$4,285.70") {
        logoSizes.clear()
        allTaps = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                ClearAirTheme {
                    val palette = LocalClearAirPalette.current
                    paper = palette.background
                    primary = palette.primary
                    Box(Modifier.requiredSize(page.width, page.height).background(palette.background)) {
                        Page(held, priceOnly, senders, range, priceText)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Page(held: CoinHeld?, priceOnly: Boolean, senders: Boolean, range: ChartFrame, priceText: String?) {
        AssetPage(
            name = "Ethereum",
            symbol = "ETH",
            icon = { size ->
                logoSizes += size
                Spacer(Modifier.size(size))
            },
            priceText = priceText,
            change = PriceChange(text = "▼ 2.41% · −$105.82 Today", rising = false),
            series = PriceSeries(List(64) { i -> PricePoint(timeMs = i * HOUR_MS, usd = 4_200.0 + (i * 37 % 11) * 12.5 - i * 4.0) }),
            range = range,
            onRange = {},
            watched = true,
            onWatch = {},
            priceOnly = priceOnly,
            held = held,
            onHoldings = {},
            accounts = listOf(CoinAccount("Savings", "Air 1 #0", "0x" + "a1".repeat(20), "4 ETH", onSend = if (senders) ({}) else null, onReceive = {})),
            onBack = {},
            onLock = {},
            onAllActivity = { allTaps++ },
            activity = { Text("No activity yet.") },
        )
    }

    private fun top(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top / 3f

    private fun boundsOf(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    @Test
    fun theElementsAreInTheFramesOrder() {
        show()

        val order = listOf(
            "back" to top(hasContentDescription("Back")),
            "eyebrow" to top(hasText("ETHEREUM · ETH")),
            "price" to top(hasText("4,285", substring = true)),
            "change" to top(hasText("2.41%", substring = true)),
            "chart" to compose.onNodeWithTag(CHART_TAG).fetchSemanticsNode().boundsInRoot.top / 3f,
            "range" to top(hasText("5m")),
            "send" to top(hasText("Send ETH")),
            "your" to top(hasText("YOUR ETH")),
            "held" to top(hasText("22.23 ETH")),
            "activity" to top(hasText("ACTIVITY")),
            "empty" to top(hasText("No activity yet.")),
        )
        assertEquals(order.map { it.first }, order.sortedBy { it.second }.map { it.first }, order.toString())
        assertTrue(top(hasContentDescription("Remove Ethereum from the watchlist")) < top(hasText("ETHEREUM · ETH")))
        assertTrue(top(hasContentDescription("Lock")) < top(hasText("ETHEREUM · ETH")))
        compose.onNodeWithText("Ethereum").assertExists()
        compose.onNodeWithText("All").assertExists()
    }

    @Test
    fun theFrameHasTheMeasuresOfTheApprovedFrame() {
        show()

        // The logo disc is 48 dp with a 28 dp logo, and the holdings row's disc is 36 dp with a 21 dp logo.
        assertEquals(listOf(28.dp, 21.dp), logoSizes.distinct())
        // The chart is 120 dp high with the page gutter at each side.
        val chart = compose.onNodeWithTag(CHART_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(120f, chart.height / 3f, 0.5f)
        assertEquals(20f, chart.left / 3f, 0.5f)
        assertEquals(340f, chart.right / 3f, 0.5f)
        // The Send capsule is 52 dp high, and the frame puts its top 400.8 dp under the top bar's top.
        val send = compose.onNode(hasText("Send ETH")).fetchSemanticsNode().boundsInRoot
        assertEquals(52f, send.height / 3f, 0.5f)
        assertEquals(400.8f, send.top / 3f, 1f)
        // Send and Receive sit side by side, the capsule first, 18 dp apart less the text action's own 6 dp.
        val receive = boundsOf("Receive")
        assertTrue(receive.left > send.right)
        assertEquals(36f, send.left / 3f, 1f)
    }

    @Test
    fun sendIsTheOneFilledCapsuleAndReceiveIsATextAction() {
        show()

        compose.onAllNodesWithText("Send ETH").assertCountEquals(1)
        compose.onAllNodesWithText("Receive").assertCountEquals(1)
        assertEquals(primary, pixelOf("Send ETH", dx = 10f, dy = 26f))
        assertEquals(paper, pixelOf("Receive", dx = 4f, dy = 24f))
    }

    @Test
    fun theRangeRowMarksTheChosenRange() {
        show(range = ChartFrame.H4)

        val chosen = compose.onAllNodes(SemanticsMatcher("selected tab") { it.config.getOrNull(SemanticsProperties.Selected) == true }).fetchSemanticsNodes()
        assertEquals(1, chosen.size)
        compose.onNode(SemanticsMatcher("selected tab") { it.config.getOrNull(SemanticsProperties.Selected) == true }).assertTextIs("4h")
        for (frame in ChartFrame.entries) compose.onNodeWithText(frame.label).assertExists()
    }

    @Test
    fun aPageWithNoPriceYetShowsTheDashInsteadOfCrashingOnItsEmptyFigures() {
        show(priceText = null)

        compose.onNodeWithText("—", substring = true).assertExists()
        compose.onNodeWithText("ETHEREUM · ETH").assertExists()
    }

    @Test
    fun aPriceOnlyPageKeepsThePriceChartAndRangesAndNothingElse() {
        show(held = null, priceOnly = true)

        compose.onNodeWithTag(CHART_TAG).assertExists()
        compose.onNodeWithText("5m").assertExists()
        for (gone in listOf("Send ETH", "Receive", "YOUR ETH", "ACTIVITY", "All", "22.23 ETH")) compose.onAllNodesWithText(gone).assertCountEquals(0)
    }

    @Test
    fun whenNoAccountCanSendReceiveIsTheCapsuleAndSendIsHidden() {
        show(senders = false)

        compose.onAllNodesWithText("Send ETH").assertCountEquals(0)
        assertEquals(52f, boundsOf("Receive").height / 3f, 0.5f)
        assertEquals(primary, pixelOf("Receive", dx = 10f, dy = 26f))
    }

    @Test
    fun aCoinNobodyHoldsHasReceiveAsTheCapsuleAndNoHoldingsRow() {
        show(held = null)

        compose.onAllNodesWithText("YOUR ETH").assertCountEquals(0)
        assertEquals(primary, pixelOf("Receive", dx = 10f, dy = 26f))
        // A coin that an account can send is still offered as the text action beside it.
        compose.onNodeWithText("Send ETH").assertExists()
        compose.onNodeWithText("ACTIVITY").assertExists()
    }

    @Test
    fun theReceiveActionSitsOneDpAboveTheCapsulesCentreLine() {
        show()

        val send = compose.onNode(hasText("Send ETH")).fetchSemanticsNode().boundsInRoot
        val receive = boundsOf("Receive")
        val lift = ((send.top + send.bottom) - (receive.top + receive.bottom)) / 2f / 3f
        assertEquals(1f, lift, 0.3f, "Receive is $lift dp above the capsule's centre")
    }

    @Test
    fun theAllActionKeepsItsTouchTargetAndTheFramesLine() {
        show()

        val all = compose.onNode(hasText("All").and(hasClickAction()))
        val box = all.fetchSemanticsNode().boundsInRoot
        assertTrue(box.width / 3f >= 48f && box.height / 3f >= 48f, "the target is ${box.width / 3f} x ${box.height / 3f} dp")
        // The row stays the frame's 21 dp line, not the 48 dp target: the activity content starts under the eyebrow's line, 4 dp below it.
        val gap = top(hasText("No activity yet.")) - top(hasText("ACTIVITY"))
        assertTrue(gap < 30f, "the activity row is $gap dp under the eyebrow")
        // A press 2 dp inside the target's top edge, above the 21 dp line the page lays out, reaches the action.
        all.performTouchInput { click(Offset(width / 2f, 6f)) }
        compose.waitForIdle()
        assertEquals(1, allTaps)
    }

    @Test
    fun aLongAmountWrapsInsteadOfClippingAndALongSummaryEndsInAnEllipsis() {
        val amount = "123,456,789,012,345.123456789012 ETH"
        show(held = CoinHeld(amount = amount, summary = "Savings, Spending, Vault, Travel, Joint and 5 more accounts · 3 chains", value = "$95,271.11"))

        val title = compose.onNode(hasText(amount), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(amount, title.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        val layout = title.textLayout()
        assertTrue(!layout.didOverflowWidth && !layout.didOverflowHeight, "the amount is clipped")
        val summary = compose.onNode(hasText("Savings", substring = true), useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertTrue(summary.isLineEllipsized(summary.lineCount - 1), "the summary is cut without a mark")
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** The colour of the pixel [dx], [dy] dp inside the top left corner of the node with [text]. */
    private fun pixelOf(text: String, dx: Float, dy: Float): Color {
        val image = compose.onNodeWithText(text).captureToImage().asAndroidBitmap()
        return Color(image.getPixel((dx * 3f).toInt(), (dy * 3f).toInt()))
    }

    private fun SemanticsNodeInteraction.assertTextIs(expected: String) {
        assertEquals(expected, fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        val HELD = CoinHeld(amount = "22.23 ETH", summary = "5 accounts · 3 chains", value = "$95,271.11")
    }
}
