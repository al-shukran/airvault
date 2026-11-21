package app.lumio.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import app.lumio.ui.screenshot.clearAirSnapshot
import app.lumio.ui.theme.LumioGutter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Clear Air kit: one golden per primitive, in the dark theme by default and in the light theme with `_light` in its name, at the
 * owner's phone size. They are recorded on the Linux runner, and reduced motion is on, so each golden is a still frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearAirKitScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The enabled capsule above the disabled one. */
    @Test
    fun primaryButton() = compose.clearAirSnapshot("clearair_primary") { GutterColumn { PrimaryButtons() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun primaryButtonLight() = compose.clearAirSnapshot("clearair_primary_light") { GutterColumn { PrimaryButtons() } }

    @Test
    fun textAction() = compose.clearAirSnapshot("clearair_text_action") { GutterColumn { TextActions() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun textActionLight() = compose.clearAirSnapshot("clearair_text_action_light") { GutterColumn { TextActions() } }

    @Test
    fun row() = compose.clearAirSnapshot("clearair_row") { FullBleedColumn { Rows() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun rowLight() = compose.clearAirSnapshot("clearair_row_light") { FullBleedColumn { Rows() } }

    @Test
    fun statusMark() = compose.clearAirSnapshot("clearair_status") { GutterColumn { StatusMarks() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun statusMarkLight() = compose.clearAirSnapshot("clearair_status_light") { GutterColumn { StatusMarks() } }

    @Test
    fun testnetHatch() = compose.clearAirSnapshot("clearair_testnet") { FullBleedColumn { ClearAirTestnetHatch(text = "Testnet · Monad") } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun testnetHatchLight() = compose.clearAirSnapshot("clearair_testnet_light") {
        FullBleedColumn { ClearAirTestnetHatch(text = "Testnet · Monad") }
    }

    @Test
    fun amount() = compose.clearAirSnapshot("clearair_amount") { GutterColumn { Amounts() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun amountLight() = compose.clearAirSnapshot("clearair_amount_light") { GutterColumn { Amounts() } }

    @Composable
    private fun PrimaryButtons() {
        ClearAirPrimaryButton(text = "Review", onClick = {})
        ClearAirPrimaryButton(text = "Review", onClick = {}, enabled = false)
    }

    @Composable
    private fun TextActions() {
        ClearAirTextAction(text = "Pair a Wallet", onClick = {})
        ClearAirTextAction(text = "Pair a Wallet", onClick = {}, enabled = false)
    }

    @Composable
    private fun Rows() {
        ClearAirRow(
            title = "Savings",
            description = "2 chains · 3 accounts",
            trailing = { Text(text = "\$980", style = ClearAirType.rowValue, color = LocalClearAirPalette.current.ink) },
            onClick = {},
        )
        HorizontalDivider(modifier = Modifier.padding(start = ClearAirSpacing.gutter), color = LocalClearAirPalette.current.hairline)
        ClearAirRow(title = "PulsarNova #5", description = "Monad Testnet · 0x1020 3040", onClick = {})
        HorizontalDivider(modifier = Modifier.padding(start = ClearAirSpacing.gutter), color = LocalClearAirPalette.current.hairline)
        ClearAirRow(title = "Demo wallet", description = "Read only")
    }

    @Composable
    private fun StatusMarks() {
        ClearAirStatusMark(glyph = "▲", text = "Received", tone = ClearAirTone.Settled)
        ClearAirStatusMark(glyph = "◆", text = "Waiting for Lensly", tone = ClearAirTone.Pending)
        ClearAirStatusMark(glyph = "!", text = "Not sent", tone = ClearAirTone.Failed)
    }

    @Composable
    private fun Amounts() {
        ClearAirAmount(text = "\$ 149,769.10")
        ClearAirAmount(text = "0.25 MON", style = ClearAirType.amountLarge)
        ClearAirAmount(text = "\$ 149,769.10", hidden = true)
    }

    /** The column every kit sample sits in: the page gutter on each side, from the top of the frame. */
    @Composable
    private fun GutterColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LumioGutter),
            verticalArrangement = Arrangement.spacedBy(ClearAirSpacing.md),
            content = content,
        )
    }

    /** The column of the full-bleed samples: rows and the testnet band run edge to edge, as they do on a screen. */
    @Composable
    private fun FullBleedColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}
