package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import app.lumio.ui.screenshot.lumioSnapshot
import app.lumio.ui.theme.LumioGutter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Lumen's kit (section 4, "New kit"), one component or set per golden, at the owner's phone size. They are recorded on the Linux
 * runner, and reduced motion is on, so each golden is a still frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioKitScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun island() = compose.lumioSnapshot("kit_island") { KitColumn { IslandWithRows() } }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun islandLight() = compose.lumioSnapshot("kit_island_light") { KitColumn { IslandWithRows() } }

    @Test
    fun textAction() = compose.lumioSnapshot("kit_text_action") {
        KitColumn { LumioTextAction(text = "Pair a Wallet", onClick = {}) }
    }

    /** A row and a button held down: the golden is the pressed frame, scaled and rippled. */
    @Test
    fun rowPressed() = compose.lumioSnapshot("kit_row_pressed", beforeCapture = { onNodeWithTag(PRESSED).performTouchInput { down(center) } }) {
        KitColumn { LumioRow(title = "Savings", description = "2 chains · 3 accounts", modifier = Modifier.testTag(PRESSED), onClick = {}) }
    }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun rowPressedLight() = compose.lumioSnapshot("kit_row_pressed_light", beforeCapture = { onNodeWithTag(PRESSED).performTouchInput { down(center) } }) {
        KitColumn { LumioRow(title = "Savings", description = "2 chains · 3 accounts", modifier = Modifier.testTag(PRESSED), onClick = {}) }
    }

    @Test
    fun tags() = compose.lumioSnapshot("kit_tags") {
        KitColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
                LumioTag(text = "7 accounts")
                LumioTag(text = "Testnet", color = LocalLumenPalette.current.testnet, background = LocalLumenPalette.current.testnet.copy(alpha = 0.14f))
            }
        }
    }

    @Test
    fun assetIcons() = compose.lumioSnapshot("kit_asset_icons") {
        KitColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.md), verticalAlignment = Alignment.CenterVertically) {
                LumioAssetIcon { LogoStandIn() }
                LumioAssetIcon(testnet = true) { LogoStandIn() }
            }
        }
    }

    /** The column every kit sample sits in: the page gutter on each side, from the top of the frame. */
    @Composable
    private fun KitColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LumioGutter),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.md),
            content = content,
        )
    }

    @Composable
    private fun IslandWithRows() {
        LumioIsland {
            LumioRow(title = "Demo", description = "4 chains · 7 accounts", onClick = {})
            HorizontalDivider(modifier = Modifier.padding(start = LenslySpacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            LumioRow(
                title = "Savings",
                description = "2 chains · 3 accounts",
                trailing = { Text(text = "\$980", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
                onClick = {},
            )
            HorizontalDivider(modifier = Modifier.padding(start = LenslySpacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            LumioRow(title = "Pair another wallet", onClick = {})
        }
    }

    private companion object {
        /** The tag of the sample held down in the pressed goldens. */
        const val PRESSED = "pressed"
    }

    /** A stand-in for a logo: a gold disc, so the frame of the icon shows what it holds. */
    @Composable
    private fun LogoStandIn() {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}
