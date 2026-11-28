package app.lumio.ui.network

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.network.NetworkMode
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's blocks grow with a larger font (#1742 review). The head, the eyebrows and the control are placed by their designed heights, and the sentence
 * reserves its lines; at a font scale over 1 their text is taller than that, and the block must then take the text's own height. So no text of the panel is
 * cut short of its own height, and none runs into the text under it. The window is 360 x 1400 dp at the phone's 1.75 px a dp, tall enough for the whole panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w630dp-h2450dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePanelFontScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun noTextIsCutOrOverlapsAtFontScaleOnePointThree() = assertNoTextCutOrOverlapping(1.3f)

    @Test
    fun noTextIsCutOrOverlapsAtFontScaleTwo() = assertNoTextCutOrOverlapping(2f)

    private fun assertNoTextCutOrOverlapping(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.75f, fontScale)) {
                ClearAirTheme {
                    ProfilePanel(
                        visible = true,
                        mode = NetworkMode.TESTNET,
                        testNetworks = 4,
                        networkSentence = testNetworkSentence(listOf("Monad Testnet")),
                        wallets = listOf(PanelWallet("air1", "Air 1", "30 accounts · paired 21 Nov"), PanelWallet("air2", "Air 2", "3 accounts · paired 18 Nov")),
                        activeWalletId = "air1",
                        onMode = {},
                        onScan = {},
                        onTestNetworks = {},
                        onWallet = {},
                        onPair = {},
                        onDismiss = {},
                    )
                }
            }
        }

        val texts = textNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        listOf("Lumio", "AirVault Companion", "NETWORKS", "Choose Test Networks", "LENSLY WALLETS", "Air 1", "Pair Another").forEach { name ->
            assertTrue(texts.any { it.first == name }, "\"$name\" is not on the panel at font scale $fontScale")
        }
        texts.forEach { (text, node) ->
            val layout = mutableListOf<TextLayoutResult>().also { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(it) }.single()
            assertTrue(!layout.didOverflowHeight, "\"$text\" is cut: ${layout.multiParagraph.height} px of text in ${layout.size.height} px at font scale $fontScale")
            assertTrue(node.boundsInRoot.height >= node.size.height - 0.5f, "\"$text\" is clipped to ${node.boundsInRoot.height} of ${node.size.height} px at font scale $fontScale")
        }
        val boxes = texts.map { (text, node) -> text to Rect(node.positionInRoot, node.size.toSize()) }
        boxes.forEach { (text, box) ->
            boxes.filter { (other, it) -> other != text && it.top > box.top && it.left < box.right && box.left < it.right }.forEach { (other, under) ->
                assertTrue(under.top >= box.bottom - 0.5f, "\"$other\" (top ${under.top}) runs into \"$text\" (bottom ${box.bottom}) at font scale $fontScale")
            }
        }
    }

    /** Every text in the tree under [node], with its node. */
    private fun textNodes(node: SemanticsNode): List<Pair<String, SemanticsNode>> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text to node } + node.children.flatMap(::textNodes)
}
