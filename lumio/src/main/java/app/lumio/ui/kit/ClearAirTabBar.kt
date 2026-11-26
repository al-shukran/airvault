package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The five tabs of the shell, in the order of the tab bar. A tab's root screen is a place of its own, with its own history. */
enum class LumioTab(val label: String, val glyph: ClearAirGlyph) {
    HOME("Home", ClearAirGlyph.HOME),
    ACCOUNTS("Accounts", ClearAirGlyph.ACCOUNTS),
    PORTFOLIO("Portfolio", ClearAirGlyph.PORTFOLIO),
    EXPLORE("Explore", ClearAirGlyph.EXPLORE),
    SETTINGS("Settings", ClearAirGlyph.SETTINGS),
}

/** The height of the tab bar's row, above the navigation bar. */
private val TAB_BAR_HEIGHT = 58.dp

/** The space at each end of the bar, inside its hairline, as the mock's 4 dp side padding. */
private val TAB_BAR_SIDE = 4.dp

/** The tab icon, as the mock draws it. */
private val TAB_ICON = 23.dp

/** The amber diamond that marks the active tab, above its icon. */
private val SPARK_SIZE = 6.dp

/** The spark sits 4 dp under the bar's top edge and 4 dp over its icon, as the mock lays it out. */
private val SPARK_TOP = 4.dp
private val SPARK_TO_ICON = 4.dp

/**
 * The tab bar at the foot of a tab's root screen. The active tab is ink with the amber spark above its icon; the others are ink2. Each tab is a
 * tab in TalkBack, and the bar clears the navigation bar.
 */
@Composable
fun ClearAirTabBar(selected: LumioTab, onSelect: (LumioTab) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(TAB_BAR_HEIGHT)
            .background(palette.background)
            .drawBehind { drawRect(color = palette.hairline, size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = TAB_BAR_SIDE),
    ) {
        LumioTab.entries.forEach { tab ->
            val active = tab == selected
            val tint = if (active) palette.ink else palette.ink2
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Spacer(Modifier.height(SPARK_TOP))
                Box(modifier = Modifier.size(SPARK_SIZE).rotate(45f).background(if (active) palette.spark else Color.Transparent))
                Spacer(Modifier.height(SPARK_TO_ICON))
                ClearAirIcon(glyph = tab.glyph, color = tint, size = TAB_ICON)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tab.label,
                    style = ClearAirType.label.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}
