package app.lumio.ui.network

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.network.NetworkMode
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel keeps its designed spacing at a fractional density (#1742). A row is 62 dp, which is 108.5 px at 1.75 px a dp, and the blocks between the rows are
 * whole dp that are not whole pixels; drawn each as its own rounded pixels they drift down by half a pixel apiece. The window is 360 x 1400 dp, 630 x 2450 px at 1.75 px a dp. The wallet rows must stay within a pixel of
 * the cumulative 62 dp spacing, and the last of them within a pixel of where the designed heights above it put it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w630dp-h2450dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePanelRowDriftTest {
    @get:Rule
    val compose = createComposeRule()

    private val density = 1.75f
    private val names = List(8) { "Wallet $it" }

    @Test
    fun theWalletRowsStayWithinAPixelOfTheirDesignedSpacingAtOnePointSevenFiveDensity() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                ClearAirTheme {
                    ProfilePanel(
                        visible = true,
                        mode = NetworkMode.MAINNET,
                        testNetworks = 4,
                        networkSentence = "",
                        wallets = names.map { PanelWallet(it, it, "1 account") },
                        activeWalletId = null,
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

        val tops = names.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        names.indices.forEach { i ->
            val drift = abs((tops[i] - tops[0]) - i * 62f * density)
            assertTrue(drift <= 1f, "wallet row $i is $drift px from where 62 dp rows put it")
        }
        // Between the title of the Scan to Pay row and that of the first wallet row, both two-line 62 dp rows, lie the row (62), the Networks eyebrow (32), the control (47),
        // Choose Test Networks (48) and the Lensly Wallets eyebrow (28): 217 dp.
        val scan = compose.onNodeWithText("Scan to Pay", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val drift = abs((tops[0] - scan) - 217f * density)
        assertTrue(drift <= 1f, "the first wallet row is $drift px from where the designed heights put it")
    }
}
