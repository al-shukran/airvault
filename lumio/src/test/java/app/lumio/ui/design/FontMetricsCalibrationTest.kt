package app.lumio.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.ClearAirType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer calibration of the design gate: where Robolectric draws Archivo against where Chromium draws it for the frames, for the same size,
 * weight, width and line height, with the line box CSS sets (the font's own height centred in the line height). The Chromium values were measured
 * with tools/design-frames/render.py's fonts and flags: the top and bottom row of ink of "HHHH", in dp below the top of the line box. Measured here,
 * Robolectric's ink bottom is the same row as Chromium's and its ink top is at most 1 dp lower (anti-aliasing), so Archivo needs no offset: the
 * gate's text tolerance of 2 dp (design/registry.json) covers the renderer, and a difference larger than that is the screen's own line box. The
 * test fails when a Robolectric, Compose or font change moves Archivo by more than that tolerance, so the gate stops comparing across a renderer
 * artefact it does not know of (tools/design-frames/README.md, "What Chromium and Compose cannot agree on").
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1000dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontMetricsCalibrationTest {
    @get:Rule
    val compose = createComposeRule()

    /** A Clear Air style, its line height, and Chromium's ink top and bottom of "HHHH" in dp below the line box. */
    private class Case(val name: String, val style: TextStyle, val top: Int, val bottom: Int)

    // Built on first use, inside the theme: ClearAirType must not initialise before ClearAirTheme does, or another test's Typography reads null.
    private val cases by lazy {
        listOf(
            Case("body 15/22, 92", ClearAirType.body, 5, 16),
            Case("small 13/18, 92", ClearAirType.small, 4, 13),
            Case("label 13/18, 600, 92", ClearAirType.label, 4, 13),
            Case("caption 12.5/17, 92", ClearAirType.caption, 3, 12),
            Case("rowTitle 15/20, 600, 114", ClearAirType.rowTitle, 4, 15),
            Case("titleL 20/25, 700, 114", ClearAirType.titleL, 5, 19),
            Case("titleXL 26/30, 700, 114", ClearAirType.titleXL, 6, 24),
            Case("topBar 18/21, 700, 114", ClearAirType.topBar, 3, 16),
            Case("eyebrow 11/14, 600, 92", ClearAirType.eyebrow, 3, 11),
        )
    }

    @Test
    fun archivoIsDrawnWhereChromiumDrawsItWithinTheGatesTolerance() {
        val rowDp = 70
        val lineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        // In the theme, as every screen is: the type scale and the theme's typography initialise together, in the order the other tests rely on.
        compose.setContent {
            ClearAirTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    cases.forEachIndexed { i, c ->
                        Box(Modifier.offset(10.dp, (i * rowDp + 5).dp).width(300.dp).height(c.style.lineHeight.value.dp)) {
                            Text("HHHH", style = c.style.copy(color = Color.Black, lineHeightStyle = lineBox))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val shot = compose.onRoot().captureToImage()
        val pixels = IntArray(shot.width * shot.height)
        shot.readPixels(pixels)
        val tolerance = 2
        val off = mutableListOf<String>()
        cases.forEachIndexed { i, c ->
            val inked = (i * rowDp until (i + 1) * rowDp).filter { y -> (0 until 320).any { x -> (pixels[y * shot.width + x] shr 8 and 255) < 128 } }
            val top = inked.first() - (i * rowDp + 5)
            val bottom = inked.last() + 1 - (i * rowDp + 5)
            if (kotlin.math.abs(top - c.top) > tolerance || kotlin.math.abs(bottom - c.bottom) > tolerance) {
                off += "${c.name}: ink ${top}..$bottom dp, Chromium ${c.top}..${c.bottom}"
            }
        }
        assertTrue("Archivo is drawn more than $tolerance dp from Chromium's: the gate needs a calibration offset. $off", off.isEmpty())
    }
}
